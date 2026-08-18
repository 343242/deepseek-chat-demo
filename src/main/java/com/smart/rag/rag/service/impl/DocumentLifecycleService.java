package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.event.DocumentDeletedEvent;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 文档生命周期服务（单一职责）
 * <p>
 * 封装文档删除时的资源清理编排：
 * <ol>
 *   <li>清理向量库中该文档的所有 chunk（失败不阻塞）</li>
 *   <li>清理文件存储（MinIO）</li>
 *   <li>逻辑删除数据库记录</li>
 * </ol>
 * <p>
 * 从 DocumentApplicationServiceImpl 中提取，符合 SRP。
 */
@Service
public class DocumentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLifecycleService.class);

    private final Loader vectorStoreLoader;
    private final FileStorageService fileStorageService;
    private final RagDocumentMapper ragDocumentMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityIndexCleanupService entityIndexCleanupService;

    public DocumentLifecycleService(Loader vectorStoreLoader,
                                    FileStorageService fileStorageService,
                                    RagDocumentMapper ragDocumentMapper,
                                    ApplicationEventPublisher eventPublisher,
                                    EntityIndexCleanupService entityIndexCleanupService) {
        this.vectorStoreLoader = vectorStoreLoader;
        this.fileStorageService = fileStorageService;
        this.ragDocumentMapper = ragDocumentMapper;
        this.eventPublisher = eventPublisher;
        this.entityIndexCleanupService = entityIndexCleanupService;
    }

    /**
     * 级联删除文档：向量 → 文件存储 → 数据库
     * <p>
     * 每步失败不阻塞后续步骤（容错设计）。
     *
     * @param doc 要删除的文档实体
     * @return true 删除成功
     */
    public boolean cascadeDelete(RagDocument doc) {
        Long id = doc.getId();

        // 0. 清理实体索引（在删向量之前）
        try {
            entityIndexCleanupService.cleanupByDocumentId(id);
        } catch (Exception e) {
            log.error("Failed to cleanup entity index for deleted docId={}", id, e);
        }

        boolean vectorDeleted = false;
        try {
            vectorStoreLoader.deleteByDocumentId(id);
            vectorDeleted = true;
        } catch (Exception e) {
            log.error("Failed to delete vectors for documentId={}, will retry on next cleanup pass: {}", id, e);
        }

        // 2. 清理文件存储
        try {
            fileStorageService.delete(doc.getBucket(), doc.getStorageKey());
        } catch (Exception e) {
            log.error("Failed to delete file for documentId={}, storageKey={}: {}", id, doc.getStorageKey(), e);
        }

        // 3. 逻辑删除数据库记录
        ragDocumentMapper.deleteById(id);

        // DB 删除后发布事件，供下游（pendingSupersede 加速层等）清理该文档相关内存状态
        eventPublisher.publishEvent(new DocumentDeletedEvent(id));

        if (!vectorDeleted) {
            log.warn("Document {} deleted from DB/Storage but vectors remain — manual cleanup may be required", id);
        }

        log.info("Document deleted: id={}, file={}, userId={}, vectorClean={}", id, doc.getFileName(), doc.getUserId(), vectorDeleted);
        return true;
    }
}
