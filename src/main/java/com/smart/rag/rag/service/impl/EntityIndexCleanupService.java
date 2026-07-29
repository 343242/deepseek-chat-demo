package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 实体索引清理服务（§8.4）
 * <p>
 * 职责：文档删除/supersede 时级联清理 rag_chunk_entity、rag_event，重算 degree，清除孤儿实体。
 * <p>
 * 清理顺序（Plan B per design note 5）：
 * <ol>
 *   <li>SELECT chunk_ids from vector_store WHERE metadata->>'documentId'</li>
 *   <li>SELECT DISTINCT entity_ids affected</li>
 *   <li>DELETE rag_chunk_entity</li>
 *   <li>DELETE rag_event</li>
 *   <li>UPDATE degree</li>
 *   <li>DELETE orphan entities (degree=0)</li>
 *   <li>Mark community_stale=TRUE</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class EntityIndexCleanupService {

    private static final Logger log = LoggerFactory.getLogger(EntityIndexCleanupService.class);

    private final ChunkEntityMapper chunkEntityMapper;
    private final EventMapper eventMapper;
    private final EntityMapper entityMapper;
    private final VectorStoreMapper vectorStoreMapper;
    private final TransactionTemplate transactionTemplate;

    public EntityIndexCleanupService(ChunkEntityMapper chunkEntityMapper,
                                     EventMapper eventMapper,
                                     EntityMapper entityMapper,
                                     VectorStoreMapper vectorStoreMapper,
                                     TransactionTemplate transactionTemplate) {
        this.chunkEntityMapper = chunkEntityMapper;
        this.eventMapper = eventMapper;
        this.entityMapper = entityMapper;
        this.vectorStoreMapper = vectorStoreMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 按文档 ID 清理实体索引数据
     *
     * @param documentId 文档 ID
     */
    public void cleanupByDocumentId(Long documentId) {
        String docIdStr = String.valueOf(documentId);

        // Step 1: 获取受影响的 entity_ids（通过 vector_store -> rag_chunk_entity）
        List<Long> affectedEntityIds = chunkEntityMapper.selectEntityIdsByDocumentId(docIdStr);

        if (affectedEntityIds.isEmpty()) {
            log.debug("No entities affected for documentId={}, skipping cleanup", documentId);
            return;
        }

        // Step 2: 获取 chunk_ids（从 vector_store）
        List<VectorStoreMapper.VectorStoreRow> chunks =
                vectorStoreMapper.selectChunksByDocumentId(docIdStr);
        List<String> chunkIds = chunks.stream().map(VectorStoreMapper.VectorStoreRow::id).toList();

        if (chunkIds.isEmpty()) {
            // chunk_ids 为空但 entity_ids 不为空 → 脏数据，仍重算 degree
            log.warn("Entity IDs found but no chunk IDs for documentId={}, recalc degree only", documentId);
            transactionTemplate.executeWithoutResult(status -> {
                entityMapper.recalculateDegree(affectedEntityIds);
                entityMapper.deleteOrphans(affectedEntityIds);
                entityMapper.markCommunityStale(affectedEntityIds);
            });
            return;
        }

        // Step 3-7: 在事务中执行清理
        transactionTemplate.executeWithoutResult(status -> {
            // 3. 删除 rag_chunk_entity
            chunkEntityMapper.deleteByChunkIds(chunkIds);

            // 4. 删除 rag_event
            eventMapper.deleteByChunkIds(chunkIds);

            // 5. 重算受影响 entity degree
            entityMapper.recalculateDegree(affectedEntityIds);

            // 6. 删除 degree=0 孤儿实体
            entityMapper.deleteOrphans(affectedEntityIds);

            // 7. 标记 community_stale=TRUE
            entityMapper.markCommunityStale(affectedEntityIds);
        });

        log.info("Entity index cleanup completed for documentId={}: {} chunks, {} entities affected",
                documentId, chunkIds.size(), affectedEntityIds.size());
    }
}
