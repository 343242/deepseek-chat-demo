package com.smart.rag.rag.etl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.outbox.OutboxMessageBus;
import com.smart.rag.rag.entity.DocumentImage;
import com.smart.rag.rag.mapper.DocumentImageMapper;
import com.smart.rag.rag.parser.odl.ImageManifest;
import com.smart.rag.rag.parser.odl.OdlConfigs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图片清单短事务服务（design §6.3）——Standard 与 FastTrack 共用的唯一事务形状，
 * 不发明第二套传递惯例：
 * <pre>
 * TransactionTemplate.execute:
 *   ① 调用方状态更新（completeDocument / writeBm25Row，REQUIRED 并入）
 *   ② DELETE FROM document_image WHERE document_id = :id   （幂等重建）
 *   ③ INSERT 新 manifest 全量（含 producer_version）
 *   ④ outboxMessageBus.sendInTransaction(envelope)          （H1：INSERT 同 tx +
 *      afterCommit → tryImmediate；消息不设 dedupKey，严重-2）
 * </pre>
 * 向量库写（外部系统）发生在本事务之外、按各策略现状顺序不变。
 */
@Component
public class ImageManifestService {

    private static final Logger log = LoggerFactory.getLogger(ImageManifestService.class);

    /** 生产侧主题常量——消费侧以 {@code ImageConsumerProperties.getTopic()} 为准 */
    public static final String TOPIC = "rag_extract_images";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DocumentImageMapper documentImageMapper;
    private final OutboxMessageBus outboxMessageBus;
    private final TransactionTemplate transactionTemplate;

    public ImageManifestService(DocumentImageMapper documentImageMapper,
                                OutboxMessageBus outboxMessageBus,
                                TransactionTemplate transactionTemplate) {
        this.documentImageMapper = documentImageMapper;
        this.outboxMessageBus = outboxMessageBus;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 在同一短事务内执行：状态更新 → manifest 幂等重建（DELETE+INSERT）→ outbox 投递。
     *
     * @param statusUpdates ①调用方状态更新（并入本事务；事件经 H2 afterCommit 发布）
     * @param manifest      前台编号的图片清单（空清单仅执行状态更新，不投消息）
     */
    public void rebuildAndDispatch(Long documentId, List<ImageManifest.ImageEntry> manifest,
                                   EtlCandidate candidate, Runnable statusUpdates) {
        transactionTemplate.executeWithoutResult(ts -> {
            statusUpdates.run();
            documentImageMapper.deleteByDocumentId(documentId);
            if (!manifest.isEmpty()) {
                documentImageMapper.insertBatch(toRows(documentId, manifest));
                outboxMessageBus.sendInTransaction(envelope(documentId, candidate));
            }
        });
        if (!manifest.isEmpty()) {
            log.info("Image manifest rebuilt: doc={}, images={}", documentId, manifest.size());
        }
    }

    private List<DocumentImage> toRows(Long documentId, List<ImageManifest.ImageEntry> manifest) {
        List<DocumentImage> rows = new ArrayList<>(manifest.size());
        for (ImageManifest.ImageEntry entry : manifest) {
            DocumentImage row = new DocumentImage();
            row.setDocumentId(documentId);
            row.setPageNumber(entry.pageNumber());
            row.setSeq(entry.seq());
            row.setImgType(entry.type());
            row.setBbox(toJson(entry.bbox()));
            row.setObjectNum(entry.objectNum());
            row.setObjectGen(entry.objectGen());
            row.setXObjectName(entry.xObjectName());
            row.setStorageKey(entry.storageKey(documentId));
            row.setProducerVersion(OdlConfigs.PRODUCER_VERSION);
            row.setStatus(DocumentImage.STATUS_PENDING);
            rows.add(row);
        }
        return rows;
    }

    private static String toJson(double[] bbox) {
        try {
            return JSON.writeValueAsString(bbox);
        } catch (Exception e) {
            throw new IllegalStateException("bbox serialization failed", e);
        }
    }

    /** hashKey=documentId（诊断用，总线不分区无路由语义）；dedupKey=null（严重-2） */
    private static MessageEnvelope<ImageExtractJob> envelope(Long documentId, EtlCandidate c) {
        return new MessageEnvelope<>(null, TOPIC, null,
                new ImageExtractJob(documentId, c.bucket(), c.objectKey(), c.fileName()),
                String.valueOf(documentId), null, Map.of(), System.currentTimeMillis());
    }
}
