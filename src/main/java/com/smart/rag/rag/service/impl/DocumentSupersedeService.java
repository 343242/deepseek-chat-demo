package com.smart.rag.rag.service.impl;

import com.smart.rag.common.util.UuidGeneratorUtil;
import com.smart.rag.rag.event.DocumentCreatedEvent;
import com.smart.rag.rag.event.DocumentDeletedEvent;
import com.smart.rag.rag.event.EtlCompletedEvent;
import com.smart.rag.rag.event.EtlFailedEvent;
import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.TeamAccessGate;
import com.smart.rag.infrastructure.exception.ServiceException;
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
 * 文档版本替换服务（SRP：仅处理版本替换）
 * <ul>
 *   <li>监听 DocumentCreatedEvent — 建立版本关系（linkVersion 事务内同时写 superseded_by，崩溃安全）</li>
 *   <li>监听 EtlCompletedEvent — 执行旧版本清理（先查内存 pendingSupersede，再查 DB 兜底）</li>
 *   <li>应用启动补偿 — 恢复因重启丢失的 pendingSupersede</li>
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
    private final TeamAccessGate teamAccessGate;
    private final EntityIndexCleanupService entityIndexCleanupService;

    /** 待替换关系：newDocId → oldDocId（ETL 完成后执行替换）— 内存加速层 */
    private final ConcurrentHashMap<Long, Long> pendingSupersede = new ConcurrentHashMap<>();

    public DocumentSupersedeService(RagDocumentMapper ragDocumentMapper,
                                    VectorStoreMapper vectorStoreMapper,
                                    Loader vectorStoreLoader,
                                    FileStorageService fileStorageService,
                                    TransactionTemplate transactionTemplate,
                                    TeamAccessGate teamAccessGate,
                                    EntityIndexCleanupService entityIndexCleanupService) {
        this.ragDocumentMapper = ragDocumentMapper;
        this.vectorStoreMapper = vectorStoreMapper;
        this.vectorStoreLoader = vectorStoreLoader;
        this.fileStorageService = fileStorageService;
        this.transactionTemplate = transactionTemplate;
        this.teamAccessGate = teamAccessGate;
        this.entityIndexCleanupService = entityIndexCleanupService;
    }

    /**
     * 监听文档创建事件：根据 replaceDocumentId 建立版本关系。
     * 目标不可替换（不存在/已删/已被替代/非本人）时降级为新文档。
     */
    @EventListener
    @Async("etlIoExecutor")
    public void onDocumentCreated(DocumentCreatedEvent event) {
        try {
            if (event.replaceDocumentId() == null) {
                assignNewGroupId(event.documentId());
                return;
            }
            RagDocument oldDoc = resolveReplaceTarget(event);
            if (oldDoc != null) {
                linkVersion(event.documentId(), oldDoc);
            } else {
                assignNewGroupId(event.documentId());
            }
        } catch (Exception e) {
            log.warn("Supersede setup failed for docId={}, degrading to new document: {}",
                     event.documentId(), e.getMessage());
            assignNewGroupId(event.documentId());
        }
    }

    /**
     * 校验替换目标；不可替换时返回 null（调用方降级为新文档）。
     */
    private @Nullable RagDocument resolveReplaceTarget(DocumentCreatedEvent event) {
        RagDocument oldDoc = ragDocumentMapper.selectById(event.replaceDocumentId());
        if (oldDoc == null || oldDoc.getDeleted() == 1) {
            log.warn("Replace target not found or deleted: replaceDocumentId={}, degrading to new document",
                     event.replaceDocumentId());
            return null;
        }
        if (oldDoc.getSupersededBy() != null) {
            log.warn("Replace target is already superseded: replaceDocumentId={}, degrading to new document",
                     event.replaceDocumentId());
            return null;
        }
        if (!isOwner(oldDoc, event.userId(), event.teamId())) {
            log.warn("Replace target ownership mismatch: replaceDocumentId={}, userId={}, teamId={}",
                     event.replaceDocumentId(), event.userId(), event.teamId());
            return null;
        }
        return oldDoc;
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
            recoverFromDb(event);
        } catch (Exception e) {
            log.error("onEtlCompleted failed for docId={}: {}", event.documentId(), e.getMessage(), e);
        }
    }

    /**
     * DB 兜底查找：新文档有 groupId 且 version &gt; 1 时，
     * 查找同 groupId 中 superseded_by = 新文档ID 但 status != SUPERSEDED 的旧文档并替换。
     */
    private void recoverFromDb(EtlCompletedEvent event) {
        RagDocument newDoc = ragDocumentMapper.selectById(event.documentId());
        if (newDoc == null || newDoc.getDocumentGroupId() == null || newDoc.getVersion() <= 1) {
            return;
        }
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
    }

    /** 监听 ETL 失败事件：FAILED 终态 entry 已无意义，清理释放内存（重试成功后 DB 兜底仍可替换） */
    @EventListener
    @Async("etlIoExecutor")
    public void onEtlFailed(EtlFailedEvent event) {
        clearPending(event.documentId(), "ETL failure");
    }

    /** 监听文档删除事件：级联删除后 entry 已无意义，清理释放内存 */
    @EventListener
    @Async("etlIoExecutor")
    public void onDocumentDeleted(DocumentDeletedEvent event) {
        clearPending(event.documentId(), "document delete");
    }

    /** 幂等清理 pendingSupersede：未命中（remove 返回 null）不报错 */
    private void clearPending(Long documentId, String reason) {
        Long removed = pendingSupersede.remove(documentId);
        if (removed != null) {
            log.info("pendingSupersede cleared on {}: newDocId={} → oldDocId={}", reason, documentId, removed);
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
            // 团队文档：必须是活跃成员，且为 CREATOR/ADMIN 或被替换文档的原上传者
            // （与 DocumentOwnershipChecker 的团队文档规则一致）
            if (!teamId.equals(doc.getTeamId())) {
                return false;
            }
            try {
                return teamAccessGate.verifyAccess(teamId, userId).manager()
                        || userId.equals(doc.getUserId());
            } catch (ServiceException e) {
                return false;
            }
        }
        return userId.equals(doc.getUserId());
    }

    private void assignNewGroupId(Long documentId) {
        String groupId = UuidGeneratorUtil.generateCompact();
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
                linkVersionOnce(newDocId, oldDoc);
                return;
            } catch (DuplicateKeyException | GroupIdCasConflictException e) {
                log.info("Version conflict ({}), retrying ({}/{})",
                         e.getClass().getSimpleName(), retryCount + 1, MAX_VERSION_RETRY);
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
     * 单次尝试建立版本关系：事务内同时写入 groupId/version 和 superseded_by。
     * <p>
     * 并发冲突时抛 {@link DuplicateKeyException}（version 唯一约束）或
     * {@link GroupIdCasConflictException}（groupId CAS 失败），由调用方重试。
     */
    private void linkVersionOnce(Long newDocId, RagDocument oldDoc) {
        final Long oldDocId = oldDoc.getId();
        final String oldDocGroupId = oldDoc.getDocumentGroupId();

        String groupId = oldDocGroupId;
        if (groupId == null) {
            groupId = UuidGeneratorUtil.generateCompact();
        }
        final String finalGroupId = groupId;
        final int nextVersion = oldDoc.getVersion() + 1;
        final boolean needsGroupIdCas = (oldDocGroupId == null);

        // 事务内：同时写入新文档的 groupId/version 和旧文档的 superseded_by
        transactionTemplate.executeWithoutResult(status -> {
            // CAS 防护：仅当 document_group_id IS NULL 时才写入（避免并发覆盖）
            if (needsGroupIdCas) {
                int updated = ragDocumentMapper.updateGroupIdCas(oldDocId, finalGroupId);
                if (updated == 0) {
                    // CAS 失败：其他线程已经分配了 groupId
                    throw new GroupIdCasConflictException(oldDocId);
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
    }

    /**
     * 执行旧版本替换
     * <p>
     * 步骤 1 事务内更新状态，步骤 2-4 各自独立 try-catch
     */
    private void supersedeOldVersion(Long oldDocId, Long newDocId) {
        if (!markSuperseded(oldDocId, newDocId)) {
            return;
        }
        cleanupEntityIndex(oldDocId);
        cleanupVectors(oldDocId);
        cleanupStorageFile(oldDocId);
        log.info("Document superseded: oldDocId={} → newDocId={}", oldDocId, newDocId);
    }

    /** 步骤 1: 事务内更新旧文档状态为 SUPERSEDED。false = 失败，跳过后续清理 */
    private boolean markSuperseded(Long oldDocId, Long newDocId) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    ragDocumentMapper.updateSuperseded(oldDocId, newDocId));
            return true;
        } catch (Exception e) {
            log.error("Failed to mark old doc as SUPERSEDED: oldDocId={}, skipping cleanup: {}", oldDocId, e);
            return false;
        }
    }

    /** 步骤 1.5: 清理实体索引（在删向量之前，捕获受影响 entity_id） */
    private void cleanupEntityIndex(Long oldDocId) {
        try {
            entityIndexCleanupService.cleanupByDocumentId(oldDocId);
        } catch (Exception e) {
            log.error("Failed to cleanup entity index for superseded docId={}: {}", oldDocId, e);
        }
    }

    /** 步骤 2: 清理旧文档的向量与 BM25 fastTrack 行 */
    private void cleanupVectors(Long oldDocId) {
        try {
            vectorStoreLoader.deleteByDocumentId(oldDocId);
        } catch (Exception e) {
            log.error("Failed to delete vectors for superseded docId={}: {}", oldDocId, e);
        }
        try {
            vectorStoreMapper.deleteFastTrackRows(oldDocId);
        } catch (Exception e) {
            log.error("Failed to delete BM25 fastTrack for superseded docId={}: {}", oldDocId, e);
        }
    }

    /** 步骤 3: 清理旧文档的 MinIO 文件 */
    private void cleanupStorageFile(Long oldDocId) {
        RagDocument oldDoc = ragDocumentMapper.selectById(oldDocId);
        if (oldDoc == null) {
            return;
        }
        try {
            fileStorageService.delete(oldDoc.getBucket(), oldDoc.getStorageKey());
        } catch (Exception e) {
            log.error("Failed to delete MinIO file for superseded docId={}: {}", oldDocId, e);
        }
    }
}
