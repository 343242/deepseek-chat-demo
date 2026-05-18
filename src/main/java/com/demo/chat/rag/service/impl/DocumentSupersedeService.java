package com.demo.chat.rag.service.impl;

import com.demo.chat.common.uuid.UuidV7;
import com.demo.chat.rag.event.DocumentCreatedEvent;
import com.demo.chat.rag.event.EtlCompletedEvent;
import com.demo.chat.rag.etl.Loader;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.mapper.VectorStoreMapper;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.service.FileStorageService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档版本替换服务
 * <p>
 * 职责单一：处理文档版本替换逻辑。
 * <ul>
 *   <li>监听 DocumentCreatedEvent — 建立版本关系</li>
 *   <li>监听 EtlCompletedEvent — 执行旧版本清理</li>
 *   <li>应用启动补偿 — 恢复因重启丢失的 pendingSupersede</li>
 * </ul>
 */
@Service
public class DocumentSupersedeService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSupersedeService.class);

    private final RagDocumentMapper ragDocumentMapper;
    private final VectorStoreMapper vectorStoreMapper;
    private final Loader vectorStoreLoader;
    private final FileStorageService fileStorageService;
    private final TransactionTemplate transactionTemplate;

    /** 待替换关系：newDocId → oldDocId（ETL 完成后执行替换） */
    private final ConcurrentHashMap<Long, Long> pendingSupersede = new ConcurrentHashMap<>();

    public DocumentSupersedeService(RagDocumentMapper ragDocumentMapper,
                                    VectorStoreMapper vectorStoreMapper,
                                    Loader vectorStoreLoader,
                                    FileStorageService fileStorageService,
                                    TransactionTemplate transactionTemplate) {
        this.ragDocumentMapper = ragDocumentMapper;
        this.vectorStoreMapper = vectorStoreMapper;
        this.vectorStoreLoader = vectorStoreLoader;
        this.fileStorageService = fileStorageService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 监听文档创建事件：根据 replaceDocumentId 建立版本关系
     */
    @EventListener
    @Async("etlIoExecutor")
    public void onDocumentCreated(DocumentCreatedEvent event) {
        try {
            if (event.replaceDocumentId() == null) {
                assignNewGroupId(event.documentId());
                return;
            }

            RagDocument oldDoc = ragDocumentMapper.selectById(event.replaceDocumentId());
            if (oldDoc == null || oldDoc.getDeleted() == 1) {
                log.warn("Replace target not found or deleted: replaceDocumentId={}, degrading to new document",
                         event.replaceDocumentId());
                assignNewGroupId(event.documentId());
                return;
            }

            if (!isOwner(oldDoc, event.userId(), event.teamId())) {
                log.warn("Replace target ownership mismatch: replaceDocumentId={}, userId={}, teamId={}",
                         event.replaceDocumentId(), event.userId(), event.teamId());
                assignNewGroupId(event.documentId());
                return;
            }

            linkVersion(event.documentId(), oldDoc);

        } catch (Exception e) {
            log.warn("Supersede setup failed for docId={}, degrading to new document: {}",
                     event.documentId(), e.getMessage());
            assignNewGroupId(event.documentId());
        }
    }

    /**
     * 监听 ETL 完成事件：执行旧版本替换
     */
    @EventListener
    @Async("etlIoExecutor")
    public void onEtlCompleted(EtlCompletedEvent event) {
        Long oldDocId = pendingSupersede.remove(event.documentId());
        if (oldDocId == null) {
            return;
        }
        supersedeOldVersion(oldDocId, event.documentId());
    }

    /**
     * 应用启动补偿：扫描未完成的替换并补偿
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingSupersede() {
        List<RagDocument> staleDocs = ragDocumentMapper.findStaleSupersededTargets();
        if (staleDocs.isEmpty()) {
            return;
        }
        log.info("Found {} stale superseded targets, recovering...", staleDocs.size());
        for (RagDocument doc : staleDocs) {
            log.info("Recovering stale supersede: oldDocId={}, supersededBy={}", doc.getId(), doc.getSupersededBy());
            supersedeOldVersion(doc.getId(), doc.getSupersededBy());
        }
    }

    // === 内部方法 ===

    private boolean isOwner(RagDocument doc, Long userId, @Nullable Long teamId) {
        if (teamId != null) {
            return teamId.equals(doc.getTeamId());
        }
        return userId.equals(doc.getUserId());
    }

    private void assignNewGroupId(Long documentId) {
        String groupId = UuidV7.generateCompact();
        ragDocumentMapper.updateGroupId(documentId, groupId);
        log.debug("New document group assigned: docId={}, groupId={}", documentId, groupId);
    }

    private void linkVersion(Long newDocId, RagDocument oldDoc) {
        int retryCount = 0;
        int maxRetry = 3;

        while (retryCount < maxRetry) {
            try {
                String groupId = oldDoc.getDocumentGroupId();
                if (groupId == null) {
                    groupId = UuidV7.generateCompact();
                    ragDocumentMapper.updateGroupId(oldDoc.getId(), groupId);
                }
                int nextVersion = oldDoc.getVersion() + 1;

                ragDocumentMapper.updateGroupIdAndVersion(newDocId, groupId, nextVersion);

                pendingSupersede.put(newDocId, oldDoc.getId());

                log.info("Document version linked: newDocId={}, oldDocId={}, groupId={}, version={}",
                         newDocId, oldDoc.getId(), groupId, nextVersion);
                return;

            } catch (DuplicateKeyException e) {
                log.info("Version conflict for groupId={}, retrying ({}/{})",
                         oldDoc.getDocumentGroupId(), retryCount + 1, maxRetry);
                retryCount++;
                oldDoc = ragDocumentMapper.selectById(oldDoc.getId());
                if (oldDoc == null) {
                    assignNewGroupId(newDocId);
                    return;
                }
            }
        }

        log.warn("Version conflict retry exhausted for newDocId={}, degrading to new document", newDocId);
        assignNewGroupId(newDocId);
    }

    /**
     * 执行旧版本替换
     * <p>
     * 步骤 1 事务内更新状态，步骤 2-4 各自独立 try-catch
     */
    private void supersedeOldVersion(Long oldDocId, Long newDocId) {
        // 步骤 1: 事务内更新旧文档状态
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ragDocumentMapper.updateSuperseded(oldDocId, newDocId);
            });
        } catch (Exception e) {
            log.error("Failed to mark old doc as SUPERSEDED: oldDocId={}, skipping cleanup: {}", oldDocId, e.getMessage());
            return;
        }

        // 步骤 2: 清理旧文档的 vectors
        try {
            vectorStoreLoader.deleteByDocumentId(oldDocId);
        } catch (Exception e) {
            log.error("Failed to delete vectors for superseded docId={}: {}", oldDocId, e.getMessage());
        }

        try {
            vectorStoreMapper.deleteFastTrackRows(oldDocId);
        } catch (Exception e) {
            log.error("Failed to delete BM25 fastTrack for superseded docId={}: {}", oldDocId, e.getMessage());
        }

        // 步骤 3: 清理旧文档的 MinIO 文件
        RagDocument oldDoc = ragDocumentMapper.selectById(oldDocId);
        if (oldDoc != null) {
            try {
                fileStorageService.delete(oldDoc.getBucket(), oldDoc.getStorageKey());
            } catch (Exception e) {
                log.error("Failed to delete MinIO file for superseded docId={}: {}", oldDocId, e.getMessage());
            }
        }

        log.info("Document superseded: oldDocId={} → newDocId={}", oldDocId, newDocId);
    }
}
