package com.smart.rag.rag.etl;

import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.rag.entity.DocumentImage;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.DocumentImageMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 图片链路 P3 补偿与对账（design §6.4 M2/§6.8/§10）：
 * <ul>
 *   <li>积压指标刷新（中-4）+ 超龄 PENDING 告警（15 分钟，M2）——P2 起可观测；</li>
 *   <li>补偿扫描：超龄 PENDING 且文档仍存在 → 重新投递触发消息（outbox 兜底）；</li>
 *   <li>终态化：重试预算耗尽（DLQ 后行留 PENDING，v1.7 低-1）的超龄行置 FAILED；</li>
 *   <li>三方对账（§6.8）：文档表 ↔ document_image ↔ MinIO 前缀对象。</li>
 * </ul>
 * 对账范围披露：硬删除文档的 bucket 已不可知（行内未持久化 bucket），其前缀对象清理
 * 依赖删除路径的同步清理；SUPERSEDED 文档行仍可查（deleted=0），前缀对象正常回收。
 */
@Component
public class ImageReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImageReconciliationScheduler.class);

    /** 超龄 PENDING 告警阈值 */
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(15);
    /** 补偿重投阈值：PENDING 停留超过该时长视为驱动消息丢失 */
    private static final Duration REPUBLISH_THRESHOLD = Duration.ofMinutes(15);
    /** 终态化阈值：远超 max-attempts 退避窗口（最长档 1800s×16）仍未完成 → FAILED */
    private static final Duration FAIL_THRESHOLD = Duration.ofHours(8);
    private static final int BATCH_LIMIT = 500;

    private final DocumentImageMapper documentImageMapper;
    private final RagDocumentMapper ragDocumentMapper;
    private final FileStorageService fileStorageService;
    private final ImageCleanupService imageCleanupService;
    private final ImageMetrics imageMetrics;
    private final MessageBus messageBus;

    public ImageReconciliationScheduler(DocumentImageMapper documentImageMapper,
                                        RagDocumentMapper ragDocumentMapper,
                                        FileStorageService fileStorageService,
                                        ImageCleanupService imageCleanupService,
                                        ImageMetrics imageMetrics,
                                        MessageBus messageBus) {
        this.documentImageMapper = documentImageMapper;
        this.ragDocumentMapper = ragDocumentMapper;
        this.fileStorageService = fileStorageService;
        this.imageCleanupService = imageCleanupService;
        this.imageMetrics = imageMetrics;
        this.messageBus = messageBus;
    }

    /** 积压指标 + 超龄告警 + 补偿扫描（每 5 分钟） */
    @Scheduled(fixedDelayString = "${app.etl.image.reconcile-interval-ms:300000}",
            initialDelayString = "${app.etl.image.reconcile-initial-delay-ms:120000}")
    public void scan() {
        imageMetrics.refreshGauges();
        OffsetDateTime now = OffsetDateTime.now();

        // 终态化超龄 PENDING（v1.7 低-1：DLQ 后无 FAILED 挂点，由此收口）
        int failed = documentImageMapper.failStalePending(now.minus(FAIL_THRESHOLD), BATCH_LIMIT);
        if (failed > 0) {
            log.error("rag.image.extract_dead: {} stale PENDING rows terminalized to FAILED", failed);
        }

        // 超龄告警 + 补偿重投（按文档聚合）
        OffsetDateTime staleBefore = now.minus(STALE_THRESHOLD);
        List<DocumentImage> staleRows = findStalePending(staleBefore);
        if (staleRows.isEmpty()) {
            return;
        }
        imageMetrics.extractStale();
        log.error("rag.image.extract_stale: {} PENDING image rows older than {}",
                staleRows.size(), STALE_THRESHOLD);

        Map<Long, List<DocumentImage>> byDoc = staleRows.stream()
                .collect(Collectors.groupingBy(DocumentImage::getDocumentId,
                        Collectors.toList()));
        for (Map.Entry<Long, List<DocumentImage>> e : byDoc.entrySet()) {
            Long docId = e.getKey();
            RagDocument doc = ragDocumentMapper.selectById(docId);
            if (doc == null || "SUPERSEDED".equals(String.valueOf(doc.getStatus()))) {
                // 对账维度①：文档不存在/SUPERSEDED 但行残留 → 删行（对象随前缀清理）
                imageCleanupService.cleanupByDocumentId(docId);
                continue;
            }
            // 补偿重投：文档仍可处理 → 重发触发消息（消息不设 dedupKey，§6.3 严重-2）
            try {
                messageBus.send(new MessageEnvelope<>(null, ImageManifestService.TOPIC, null,
                        new ImageExtractJob(docId, doc.getBucket(), doc.getStorageKey(), doc.getFileName()),
                        String.valueOf(docId), null, Map.of(), System.currentTimeMillis()));
                log.info("Republished image extract trigger for stale doc: {}", docId);
            } catch (Exception ex) {
                log.error("Failed to republish image extract trigger: doc={}", docId, ex);
            }
        }

        reconcileOrphanObjects();
    }

    /**
     * 对账维度②：活文档的前缀下存在未被任何行引用的对象（重解析缩水、高-2 代际失效
     * 中止后的跨代对象）→ 删除前以当前行快照二次核对引用（防与在途消费者竞争）。
     */
    void reconcileOrphanObjects() {
        for (Long docId : selectDistinctDocIds()) {
            try {
                RagDocument doc = ragDocumentMapper.selectById(docId);
                if (doc == null || doc.getBucket() == null) {
                    continue;
                }
                List<DocumentImage> rows = documentImageMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentImage>()
                                .eq(DocumentImage::getDocumentId, docId));
                Set<String> referenced = rows.stream()
                        .map(DocumentImage::getStorageKey)
                        .collect(Collectors.toSet());
                List<String> objects = fileStorageService.listKeysByPrefix(
                        doc.getBucket(), ImageCleanupService.imagePrefix(docId));
                List<String> orphans = objects.stream()
                        .filter(key -> !referenced.contains(key))
                        .toList();
                if (orphans.isEmpty()) {
                    continue;
                }
                // 二次核对：重读行快照后再删（提交后的行为准）
                Set<String> referencedAgain = documentImageMapper.selectList(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentImage>()
                                        .eq(DocumentImage::getDocumentId, docId))
                        .stream().map(DocumentImage::getStorageKey).collect(Collectors.toSet());
                for (String key : orphans) {
                    if (!referencedAgain.contains(key)) {
                        fileStorageService.delete(doc.getBucket(), key);
                    }
                }
                log.info("Reconciled {} orphan image objects for doc={}", orphans.size(), docId);
            } catch (Exception e) {
                log.error("Orphan object reconciliation failed for doc={}", docId, e);
                imageMetrics.orphanCleanFailed();
            }
        }
    }

    private List<DocumentImage> findStalePending(OffsetDateTime before) {
        return documentImageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentImage>()
                        .eq(DocumentImage::getStatus, DocumentImage.STATUS_PENDING)
                        .lt(DocumentImage::getCreatedAt, before)
                        .orderByAsc(DocumentImage::getCreatedAt)
                        .last("LIMIT " + BATCH_LIMIT));
    }

    private List<Long> selectDistinctDocIds() {
        List<DocumentImage> rows = documentImageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentImage>()
                        .select(DocumentImage::getDocumentId));
        return new ArrayList<>(new HashSet<>(
                rows.stream().map(DocumentImage::getDocumentId).toList()));
    }
}
