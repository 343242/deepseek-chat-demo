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
 * <p>
 * 崩溃安全设计：
 * <ul>
 *   <li>linkVersion 在事务中同时写入 superseded_by 到旧文档（标记 PENDING）</li>
 *   <li>onEtlCompleted 双重查找：先查内存 pendingSupersede，再查 DB</li>
 *   <li>recoverPendingSupersede 在启动时补偿未完成的替换</li>
 * </ul>
 */
@Service
public class DocumentSupersedeService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSupersedeService.class);
    private static final int MAX_VERSION_RETRY = 3;

    private final RagDocumentMapper ragDocumentMapper;
    private final VectorStoreMapper vectorStoreMapper;
    private final Loader vectorStoreLoader;
    private final FileStorageService fileStorageService;
    private final TransactionTemplate transactionTemplate;

    /** 待替换关系：newDocId → oldDocId（ETL 完成后执行替换）— 内存加速层 */
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

            if (oldDoc.getSupersededBy() != null) {
                log.warn("Replace target is already superseded: replaceDocumentId={}, degrading to new document",
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
     * <p>
     * 双重查找策略：先查内存 pendingSupersede（快），再查 DB（崩溃恢复安全）。
     * 解决 B4（崩溃丢失）和 B5（时序竞争）。
     */
    @EventListener
    @Async("etlIoExecutor")
    public void onEtlCompleted(EtlCompletedEvent event) {
        try {
            // 策略 1：内存查找（正常路径）
            Long oldDocId = pendingSupersede.remove(event.documentId());
            if (oldDocId != null) {
                supersedeOldVersion(oldDocId, event.documentId());
                return;
            }

            // 策略 2：DB 查找（崩溃恢复 + 时序竞争兜底）
            // 如果新文档有 groupId 且 version > 1，查找同组中需要被替换的旧版本
            RagDocument newDoc = ragDocumentMapper.selectById(event.documentId());
            if (newDoc == null || newDoc.getDocumentGroupId() == null || newDoc.getVersion() <= 1) {
                return;
            }

            // 查找同 groupId 中 superseded_by = 新文档ID 但 status != SUPERSEDED 的旧文档
            List<RagDocument> pendingDocs = ragDocumentMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RagDocument>()
                            .eq(RagDocument::getDocumentGroupId, newDoc.getDocumentGroupId())
                            .eq(RagDocument::getSupersededBy, event.documentId())
                            .ne(RagDocument::getStatus, EtlStatus.SUPERSEDED)
                            .eq(RagDocument::getDeleted, 0)
            );

            for (RagDocument pending : pendingDocs) {
                log.info("DB-based supersede recovery: oldDocId={} → newDocId={}", pending.getId(), event.documentId());
                supersedeOldVersion(pending.getId(), event.documentId());
            }
        } catch (Exception e) {
            log.error("onEtlCompleted failed for docId={}: {}", event.documentId(), e.getMessage(), e);
        }
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

    /**
     * 建立版本关系：分配 groupId + version，同时在事务中标记旧文档的 superseded_by。
     * <p>
     * 关键改动（B4 修复）：superseded_by 的写入与 groupId/version 在同一事务中，
     * 确保崩溃后 recoverPendingSupersede 能通过 superseded_by IS NOT NULL 查到。
     */
    private void linkVersion(Long newDocId, RagDocument oldDoc) {
        int retryCount = 0;

        while (retryCount < MAX_VERSION_RETRY) {
            try {
                // 在 lambda 外部解析所有需要的值，确保 effectively final
                final Long oldDocId = oldDoc.getId();
                final String oldDocGroupId = oldDoc.getDocumentGroupId();
                final int oldDocVersion = oldDoc.getVersion();

                String groupId = oldDocGroupId;
                if (groupId == null) {
                    groupId = UuidV7.generateCompact();
                }
                final String finalGroupId = groupId;
                final int nextVersion = oldDocVersion + 1;
                final boolean needsGroupIdCas = (oldDocGroupId == null);

                // 事务内：同时写入新文档的 groupId/version 和旧文档的 superseded_by
                transactionTemplate.executeWithoutResult(status -> {
                    // CAS 防护：仅当 document_group_id IS NULL 时才写入（避免并发覆盖）
                    if (needsGroupIdCas) {
                        int updated = ragDocumentMapper.updateGroupIdCas(oldDocId, finalGroupId);
                        if (updated == 0) {
                            // CAS 失败：其他线程已经分配了 groupId
                            throw new RuntimeException("CAS groupId conflict for oldDocId=" + oldDocId);
                        }
                    }
                    ragDocumentMapper.updateGroupIdAndVersion(newDocId, finalGroupId, nextVersion);
                    // 在同一事务中标记旧文档的 superseded_by（崩溃安全）
                    ragDocumentMapper.updateSupersededByOnly(oldDocId, newDocId);
                });

                // 事务提交成功后，加入内存加速层
                pendingSupersede.put(newDocId, oldDocId);

                log.info("Document version linked: newDocId={}, oldDocId={}, groupId={}, version={}",
                         newDocId, oldDocId, finalGroupId, nextVersion);
                return;

            } catch (DuplicateKeyException e) {
                log.info("Version conflict for groupId={}, retrying ({}/{})",
                         oldDoc.getDocumentGroupId(), retryCount + 1, MAX_VERSION_RETRY);
                retryCount++;
                oldDoc = ragDocumentMapper.selectById(oldDoc.getId());
                if (oldDoc == null) {
                    assignNewGroupId(newDocId);
                    return;
                }
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("CAS groupId conflict")) {
                    log.info("CAS groupId conflict for oldDocId={}, retrying ({}/{})",
                             oldDoc.getId(), retryCount + 1, MAX_VERSION_RETRY);
                    retryCount++;
                    oldDoc = ragDocumentMapper.selectById(oldDoc.getId());
                    if (oldDoc == null) {
                        assignNewGroupId(newDocId);
                        return;
                    }
                    continue;
                }
                throw e;
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
        // 步骤 1: 事务内更新旧文档状态为 SUPERSEDED
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
