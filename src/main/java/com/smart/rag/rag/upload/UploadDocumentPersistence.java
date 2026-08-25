package com.smart.rag.rag.upload;

import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.event.DocumentCreatedEvent;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.DocumentDedupService;
import com.smart.rag.rag.service.EtlDispatchService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 上传落库共享组件：insert / dedup 登记 / DocumentCreatedEvent / ETL 投递收敛为一点。
 * <p>
 * 此前 {@code PersonalUploadStrategy} 与 {@code TeamUploadStrategy} 各持一份重复的
 * insert/事件/dedup 逻辑；presigned 直传 commit（docs/design/presigned-direct-upload.md）
 * 复用同一点。空间语义差异由调用方决定：
 * <ul>
 *   <li>个人空间：status=UPLOADED（响应呈 PROCESSING）→ dedup 登记 → 事件 → dispatch；</li>
 *   <li>团队空间：status=auto-approve ? PROCESSING : PENDING_APPROVAL → 事件；
 *       审批记录由调用方创建；仅 PROCESSING 才 dispatch。</li>
 * </ul>
 * insert 统一补齐 deleted=0 与 createTime/updateTime（原个人路径显式设置、团队路径由
 * MyBatisPlus MetaHandler 兜底，统一后语义等价）。
 */
@Component
public class UploadDocumentPersistence {

    private static final Logger log = LoggerFactory.getLogger(UploadDocumentPersistence.class);

    private final RagDocumentMapper ragDocumentMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final EtlDispatchService etlDispatchService;
    private final @Nullable DocumentDedupService documentDedupService;

    public UploadDocumentPersistence(RagDocumentMapper ragDocumentMapper,
                                     ApplicationEventPublisher eventPublisher,
                                     EtlDispatchService etlDispatchService,
                                     @Nullable DocumentDedupService documentDedupService) {
        this.ragDocumentMapper = ragDocumentMapper;
        this.eventPublisher = eventPublisher;
        this.etlDispatchService = etlDispatchService;
        this.documentDedupService = documentDedupService;
    }

    /** 文档插入参数（status 由空间语义决定：个人 UPLOADED / 团队 PROCESSING|PENDING_APPROVAL） */
    public record Insert(String fileName, long fileSize, String mimeType, String storageKey,
                         String bucket, Long userId, @Nullable Long teamId,
                         @Nullable String fileChecksum, EtlStatus status) {}

    /** 插入文档记录（deleted=0 + 显式时间戳）。 */
    public RagDocument insert(Insert insert) {
        RagDocument doc = new RagDocument();
        doc.setFileName(insert.fileName());
        doc.setFileSize(insert.fileSize());
        doc.setMimeType(insert.mimeType());
        doc.setStorageKey(insert.storageKey());
        doc.setBucket(insert.bucket());
        doc.setUserId(insert.userId());
        doc.setTeamId(insert.teamId());
        doc.setFileChecksum(insert.fileChecksum());
        doc.setStatus(insert.status());
        doc.setDeleted(0);
        OffsetDateTime now = OffsetDateTime.now();
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        ragDocumentMapper.insert(doc);
        return doc;
    }

    /** 秒传 BloomFilter 登记（checksum 空 / 服务缺失时安全跳过）。 */
    public void registerDedup(@Nullable String fileChecksum) {
        if (documentDedupService != null && fileChecksum != null) {
            documentDedupService.add(fileChecksum);
        }
    }

    /** 发布 DocumentCreatedEvent（supersede 版本替换由现有监听器处理）。 */
    public void publishCreated(Long documentId, @Nullable Long replaceDocumentId, Long userId, @Nullable Long teamId) {
        eventPublisher.publishEvent(new DocumentCreatedEvent(documentId, replaceDocumentId, userId, teamId));
    }

    /** 异步 ETL 投递（outbox → Redis Stream，不阻塞调用方）。 */
    public void dispatchEtl(Long documentId, String bucket, String objectKey, String fileName,
                            String mimeType, long fileSize, Long userId, @Nullable Long teamId) {
        etlDispatchService.dispatchAsync(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId, teamId);
        log.debug("ETL dispatched for document: id={}, userId={}, teamId={}", documentId, userId, teamId);
    }
}
