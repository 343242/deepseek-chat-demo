package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityIndexCleanupService 单元测试
 * <p>
 * 覆盖 §8.4 级联清理：chunk-entity 删除、event 删除（document_id 权威归属）、
 * degree 重算、孤儿实体清除、community_stale 标记，以及 fastTrack 孤儿兜底分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityIndexCleanupService 单元测试")
class EntityIndexCleanupServiceTest {

    @Mock
    private ChunkEntityMapper chunkEntityMapper;
    @Mock
    private EventMapper eventMapper;
    @Mock
    private EntityMapper entityMapper;
    @Mock
    private VectorStoreMapper vectorStoreMapper;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private EntityIndexCleanupService service;

    @BeforeEach
    void setUp() {
        // 模拟 TransactionTemplate：立即执行 Consumer 回调
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Nested
    @DisplayName("cleanupByDocumentId — 清理序列")
    class CleanupSequenceTests {

        @Test
        @DisplayName("正常清理：chunk-entity + event 按 document_id 删除，degree 重算 + 孤儿清除")
        void fullCleanupSequence() {
            Long docId = 1L;

            when(chunkEntityMapper.selectEntityIdsByDocumentId(docId))
                    .thenReturn(List.of(10L, 20L));
            when(vectorStoreMapper.countChunksByDocumentId("1")).thenReturn(2);

            service.cleanupByDocumentId(docId);

            // 受影响实体经 rag_event 关联查询（document_id 权威归属）
            verify(chunkEntityMapper).selectEntityIdsByDocumentId(eq(1L));

            // chunk-entity 关联按 document_id 删除（覆盖孤儿 chunk，非按 chunkIds）
            verify(chunkEntityMapper).deleteByDocumentId(eq(1L));
            // event 按 document_id 删除（权威归属）
            verify(eventMapper).deleteByDocumentId(eq(1L));
            // 旧的按 chunkIds 删除路径不再使用
            verify(chunkEntityMapper, never()).deleteByChunkIds(anyList());
            verify(eventMapper, never()).deleteByChunkIds(anyList());

            verify(entityMapper).recalculateDegree(eq(List.of(10L, 20L)));
            verify(entityMapper).deleteOrphans(eq(List.of(10L, 20L)));
            verify(entityMapper).markCommunityStale(eq(List.of(10L, 20L)));
        }

        @Test
        @DisplayName("无受影响实体 → event 兜底删除仍执行（fastTrack 孤儿场景）")
        void noAffectedEntitiesStillCleansEvents() {
            when(chunkEntityMapper.selectEntityIdsByDocumentId(1L))
                    .thenReturn(List.of());

            service.cleanupByDocumentId(1L);

            // 无 chunk-entity 关联时仍删除残留 event（如 fastTrack 行产生的 event）
            verify(eventMapper).deleteByDocumentId(eq(1L));
            verify(transactionTemplate).executeWithoutResult(any());
            verify(chunkEntityMapper, never()).deleteByDocumentId(anyLong());
            verify(vectorStoreMapper, never()).countChunksByDocumentId(anyString());
        }

        @Test
        @DisplayName("有 entity 但 vector_store 无现存 chunk → 仍按 document_id 完整清理（脏数据修复）")
        void dirtyDataStillCleansByDocumentId() {
            when(chunkEntityMapper.selectEntityIdsByDocumentId(1L))
                    .thenReturn(List.of(10L));
            when(vectorStoreMapper.countChunksByDocumentId("1")).thenReturn(0);

            service.cleanupByDocumentId(1L);

            verify(transactionTemplate).executeWithoutResult(any());
            verify(chunkEntityMapper).deleteByDocumentId(eq(1L));
            verify(eventMapper).deleteByDocumentId(eq(1L));
            verify(entityMapper).recalculateDegree(eq(List.of(10L)));
            verify(entityMapper).deleteOrphans(eq(List.of(10L)));
            verify(entityMapper).markCommunityStale(eq(List.of(10L)));
        }

        @Test
        @DisplayName("清理步骤在事务中执行")
        void allStepsInTransaction() {
            when(chunkEntityMapper.selectEntityIdsByDocumentId(1L))
                    .thenReturn(List.of(10L, 20L));
            when(vectorStoreMapper.countChunksByDocumentId("1")).thenReturn(1);

            service.cleanupByDocumentId(1L);

            verify(transactionTemplate).executeWithoutResult(any());
        }
    }
}
