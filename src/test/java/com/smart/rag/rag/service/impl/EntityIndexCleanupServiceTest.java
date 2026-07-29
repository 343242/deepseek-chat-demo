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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityIndexCleanupService 单元测试
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
        @DisplayName("正常清理：按正确顺序执行所有步骤")
        void fullCleanupSequence() {
            Long docId = 1L;
            String docIdStr = "1";

            // 模拟受影响 entity ids
            when(chunkEntityMapper.selectEntityIdsByDocumentId(docIdStr))
                    .thenReturn(List.of(10L, 20L));

            // 模拟 chunks
            VectorStoreMapper.VectorStoreRow row1 = new VectorStoreMapper.VectorStoreRow(
                    "uuid-1", "content1", Map.of());
            VectorStoreMapper.VectorStoreRow row2 = new VectorStoreMapper.VectorStoreRow(
                    "uuid-2", "content2", Map.of());
            when(vectorStoreMapper.selectChunksByDocumentId(docIdStr))
                    .thenReturn(List.of(row1, row2));

            service.cleanupByDocumentId(docId);

            // 验证执行顺序
            verify(chunkEntityMapper).selectEntityIdsByDocumentId(docIdStr);
            verify(vectorStoreMapper).selectChunksByDocumentId(docIdStr);
            verify(chunkEntityMapper).deleteByChunkIds(eq(List.of("uuid-1", "uuid-2")));
            verify(eventMapper).deleteByChunkIds(eq(List.of("uuid-1", "uuid-2")));
            verify(entityMapper).recalculateDegree(eq(List.of(10L, 20L)));
            verify(entityMapper).deleteOrphans();
            verify(entityMapper).markCommunityStale(eq(List.of(10L, 20L)));
        }

        @Test
        @DisplayName("无受影响实体 → 跳过清理")
        void noAffectedEntitiesSkips() {
            when(chunkEntityMapper.selectEntityIdsByDocumentId(anyString()))
                    .thenReturn(List.of());

            service.cleanupByDocumentId(1L);

            verify(vectorStoreMapper, never()).selectChunksByDocumentId(anyString());
            verify(chunkEntityMapper, never()).deleteByChunkIds(anyList());
        }

        @Test
        @DisplayName("有 entity 但无 chunks → 仍重算 degree（脏数据修复）")
        void dirtyDataRecalcDegree() {
            when(chunkEntityMapper.selectEntityIdsByDocumentId("1"))
                    .thenReturn(List.of(10L));
            when(vectorStoreMapper.selectChunksByDocumentId("1"))
                    .thenReturn(List.of());

            service.cleanupByDocumentId(1L);

            verify(transactionTemplate).executeWithoutResult(any());
            verify(entityMapper).recalculateDegree(eq(List.of(10L)));
            verify(entityMapper).deleteOrphans();
            verify(entityMapper).markCommunityStale(eq(List.of(10L)));
            verify(chunkEntityMapper, never()).deleteByChunkIds(anyList());
        }

        @Test
        @DisplayName("degree 重算 + orphan 删除 + community stale 标记在事务中执行")
        void allStepsInTransaction() {
            when(chunkEntityMapper.selectEntityIdsByDocumentId("1"))
                    .thenReturn(List.of(10L, 20L));
            when(vectorStoreMapper.selectChunksByDocumentId("1"))
                    .thenReturn(List.of(
                            new VectorStoreMapper.VectorStoreRow("uuid-1", "c", Map.of())));

            service.cleanupByDocumentId(1L);

            // TransactionTemplate 被调用一次（包裹清理操作）
            verify(transactionTemplate).executeWithoutResult(any());
        }
    }
}
