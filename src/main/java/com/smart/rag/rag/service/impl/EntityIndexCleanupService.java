package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper.PairCount;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper.DocumentScope;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 实体索引清理服务（§8.4）——V30 删除路径：对称递减。
 * <p>
 * 文档删除/supersede 时在 scope advisory 锁事务内：pair 计数快照 → 边对称递减 + 清零边删除 →
 * 链接/事件按 document_id 直查删除（V30 新列，废除 rag_event 桥接）→
 * degree 重算 → 孤儿实体清除 → community_stale 标记（与现状一致，不提交 derive，§5 结构分补充）。
 * <p>
 * 正确性论证（§5）：{@code selectPairCountsByDocumentId} 在锁内取自当前真实链接，
 * 与写路径的 {@code lockScope} 互斥 → 快照即锁内真值；递减后 {@code deleteZeroEdges}
 * 清除归零边，保持不变式 co_count ≡ |共同 chunk 数|。
 * <p>
 * 残余竞态与兜底：删除与在途 ETL 重投递是 last-writer-wins——清理先提交、重投递后执行时会重建
 * 链接，该残留由每日对账的孤儿链接清扫（anti-join）兜底（§6）。
 */
@Service
public class EntityIndexCleanupService {

    private static final Logger log = LoggerFactory.getLogger(EntityIndexCleanupService.class);

    private final ChunkEntityMapper chunkEntityMapper;
    private final EventMapper eventMapper;
    private final EntityMapper entityMapper;
    private final EntityCooccurrenceMapper cooccurrenceMapper;
    private final RagDocumentMapper documentMapper;
    private final VectorStoreMapper vectorStoreMapper;
    private final TransactionTemplate transactionTemplate;
    private final ScopeLockTemplate scopeLockTemplate;
    private final LockRetryExecutor lockRetryExecutor;
    private final ScopeWriteGate scopeWriteGate;
    private final RagEntityProperties properties;

    public EntityIndexCleanupService(ChunkEntityMapper chunkEntityMapper,
                                     EventMapper eventMapper,
                                     EntityMapper entityMapper,
                                     EntityCooccurrenceMapper cooccurrenceMapper,
                                     RagDocumentMapper documentMapper,
                                     VectorStoreMapper vectorStoreMapper,
                                     TransactionTemplate transactionTemplate,
                                     ScopeLockTemplate scopeLockTemplate,
                                     LockRetryExecutor lockRetryExecutor,
                                     ScopeWriteGate scopeWriteGate,
                                     RagEntityProperties properties) {
        this.chunkEntityMapper = chunkEntityMapper;
        this.eventMapper = eventMapper;
        this.entityMapper = entityMapper;
        this.cooccurrenceMapper = cooccurrenceMapper;
        this.documentMapper = documentMapper;
        this.vectorStoreMapper = vectorStoreMapper;
        this.transactionTemplate = transactionTemplate;
        this.scopeLockTemplate = scopeLockTemplate;
        this.lockRetryExecutor = lockRetryExecutor;
        this.scopeWriteGate = scopeWriteGate;
        this.properties = properties;
    }

    /**
     * 按文档 ID 清理实体索引数据（V30 §5：对称递减）。
     *
     * @param documentId 文档 ID
     */
    public void cleanupByDocumentId(Long documentId) {
        // Step 0: scope 从 rag_document 读（稳定来源：链接生命周期不影响它）——不从链接/事件表反查
        //（存在"删除 vs 重投递首写并发 → 读到空 scope → 跳锁 → 残留"竞态）。
        // selectScopeById 不过滤逻辑删列：补偿性/乱序清理仍须能读到 scope 并执行（§5 Step 0）。
        DocumentScope scope = documentMapper.selectScopeById(documentId);
        if (scope == null) {
            log.debug("Document row gone for documentId={}, nothing to clean", documentId);
            return;
        }
        Long userId = scope.userId();
        Long teamId = scope.teamId();

        // chunk 数（日志计数用）
        int chunkCount = vectorStoreMapper.countChunksByDocumentId(String.valueOf(documentId));

        // 写闸门（§3.6 第八轮：删除路径与写路径共用同一 per-scope 信号量——批量/级联删除
        // 与批量上传同构，排队零 DB 连接占用）→ 保险重试 → 事务 → scope 锁
        scopeWriteGate.tryAcquire(userId, teamId, properties.writeGateWaitMillis());
        try {
            lockRetryExecutor.execute(() ->
                    transactionTemplate.executeWithoutResult(status ->
                            scopeLockTemplate.withinScopeLock(userId, teamId, () ->
                                    cleanupWithinLock(documentId, userId, teamId))));
        } finally {
            scopeWriteGate.release(userId, teamId);
        }

        log.info("Entity index cleanup completed for documentId={}: {} chunks, userId={}, teamId={}",
                documentId, chunkCount, userId, teamId);
    }

    /** §5 步骤 2-9：锁内临界区（advisory 锁始终获取，即使文档当前无任何链接）。 */
    private void cleanupWithinLock(Long documentId, Long userId, @Nullable Long teamId) {
        // 2. 锁内快照（TOCTOU 修正：必须在锁后，§3.3）
        List<Long> affectedEntityIds = chunkEntityMapper.selectEntityIdsByDocumentId(documentId);
        List<PairCount> pairCounts = cooccurrenceMapper.selectPairCountsByDocumentId(documentId);

        if (!pairCounts.isEmpty()) {
            // 3-4. 对称递减 + 清零边删除（排序分批，§3.4）
            List<PairCount> sorted = EntityCanonicalizationService.sortedByPair(pairCounts);
            for (int i = 0; i < sorted.size(); i += 500) {
                cooccurrenceMapper.decrementByPairs(
                        sorted.subList(i, Math.min(i + 500, sorted.size())), userId, teamId);
            }
            cooccurrenceMapper.deleteZeroEdges(userId, teamId);
        }

        // 5-6. 删链接（document_id 直查，V30 新列）+ 删事件（rag_event.document_id 仍权威）
        chunkEntityMapper.deleteByDocumentId(documentId);
        eventMapper.deleteByDocumentId(documentId);

        if (!affectedEntityIds.isEmpty()) {
            // 7-9. degree 重算 → 孤儿删除 → stale 标记（与现状一致；不提交 derive，§5 结构分补充）
            entityMapper.recalculateDegree(affectedEntityIds);
            entityMapper.deleteOrphans(affectedEntityIds);
            entityMapper.markCommunityStale(affectedEntityIds);
        }
    }
}
