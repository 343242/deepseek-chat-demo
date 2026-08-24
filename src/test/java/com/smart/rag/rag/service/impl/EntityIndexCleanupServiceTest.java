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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityIndexCleanupService 单元测试（V30 §5：对称递减删除路径）。
 * <p>
 * 覆盖：scope 从 rag_document 读取（不过滤逻辑删）、始终取锁、锁内快照、
 * 边递减 + 清零删除、document_id 直查删除、degree/孤儿/stale 收尾、闸门配对释放。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityIndexCleanupService 单元测试（V30 对称递减）")
class EntityIndexCleanupServiceTest {

    @Mock
    private ChunkEntityMapper chunkEntityMapper;
    @Mock
    private EventMapper eventMapper;
    @Mock
    private EntityMapper entityMapper;
    @Mock
    private EntityCooccurrenceMapper cooccurrenceMapper;
    @Mock
    private RagDocumentMapper documentMapper;
    @Mock
    private VectorStoreMapper vectorStoreMapper;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ScopeLockTemplate scopeLockTemplate;
    @Mock
    private LockRetryExecutor lockRetryExecutor;
    @Mock
    private ScopeWriteGate scopeWriteGate;

    private RagEntityProperties properties;

    private EntityIndexCleanupService service;

    @BeforeEach
    void setUp() {
        properties = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7,
                0.5, 0.3, 0.2, true, null, true, 0, 0, 0, 0, null);
        service = new EntityIndexCleanupService(chunkEntityMapper, eventMapper, entityMapper,
                cooccurrenceMapper, documentMapper, vectorStoreMapper, transactionTemplate,
                scopeLockTemplate, lockRetryExecutor, scopeWriteGate, properties);

        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().doAnswer(invocation -> {
            Runnable body = invocation.getArgument(2);
            body.run();
            return null;
        }).when(scopeLockTemplate).withinScopeLock(any(), any(), any());
        lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(0);
            action.run();
            return null;
        }).when(lockRetryExecutor).execute(any(Runnable.class));
    }

    @Nested
    @DisplayName("cleanupByDocumentId — 对称递减序列（§5）")
    class CleanupSequenceTests {

        @Test
        @DisplayName("正常清理：边递减 + 清零删除 + 链接/事件删除 + degree/孤儿/stale 收尾")
        void fullDecrementSequence() {
            when(documentMapper.selectScopeById(1L)).thenReturn(new DocumentScope(100L, null));
            when(chunkEntityMapper.selectEntityIdsByDocumentId(1L)).thenReturn(List.of(10L, 20L));
            when(cooccurrenceMapper.selectPairCountsByDocumentId(1L)).thenReturn(List.of(
                    new PairCount(10L, 20L, 3), new PairCount(10L, 30L, 1)));
            when(vectorStoreMapper.countChunksByDocumentId("1")).thenReturn(2);

            service.cleanupByDocumentId(1L);

            // 边对称递减 + 清零删除
            verify(cooccurrenceMapper).decrementByPairs(anyList(), eq(100L), eq(null));
            verify(cooccurrenceMapper).deleteZeroEdges(eq(100L), eq(null));
            // 链接 + 事件按 document_id 直查删除（V30：废除 rag_event 桥接）
            verify(chunkEntityMapper).deleteByDocumentId(eq(1L));
            verify(eventMapper).deleteByDocumentId(eq(1L));
            // degree → 孤儿 → stale（与现状一致；不提交 derive）
            verify(entityMapper).recalculateDegree(eq(List.of(10L, 20L)));
            verify(entityMapper).deleteOrphans(eq(List.of(10L, 20L)));
            verify(entityMapper).markCommunityStale(eq(List.of(10L, 20L)));
        }

        @Test
        @DisplayName("无 pair 贡献（文档实体从未共现）→ 不递减不清零，其余照常")
        void noPairCounts_skipDecrement() {
            when(documentMapper.selectScopeById(1L)).thenReturn(new DocumentScope(100L, 20L));
            when(chunkEntityMapper.selectEntityIdsByDocumentId(1L)).thenReturn(List.of(10L));
            when(cooccurrenceMapper.selectPairCountsByDocumentId(1L)).thenReturn(List.of());

            service.cleanupByDocumentId(1L);

            verify(cooccurrenceMapper, never()).decrementByPairs(anyList(), any(), any());
            verify(cooccurrenceMapper, never()).deleteZeroEdges(any(), any());
            verify(chunkEntityMapper).deleteByDocumentId(eq(1L));
            verify(eventMapper).deleteByDocumentId(eq(1L));
        }

        @Test
        @DisplayName("文档行物理不存在 → 直接返回，零闸门零锁")
        void documentRowGone_noop() {
            when(documentMapper.selectScopeById(1L)).thenReturn(null);

            service.cleanupByDocumentId(1L);

            verify(scopeWriteGate, never()).tryAcquire(any(), any(), anyLong());
            verify(scopeLockTemplate, never()).withinScopeLock(any(), any(), any());
            verify(chunkEntityMapper, never()).deleteByDocumentId(anyLong());
        }

        @Test
        @DisplayName("文档已逻辑删（selectScopeById 不带 deleted 过滤）→ 仍执行完整清理")
        void logicallyDeletedDoc_stillCleans() {
            when(documentMapper.selectScopeById(1L)).thenReturn(new DocumentScope(100L, null));
            when(chunkEntityMapper.selectEntityIdsByDocumentId(1L)).thenReturn(List.of(10L));
            when(cooccurrenceMapper.selectPairCountsByDocumentId(1L)).thenReturn(List.of());

            service.cleanupByDocumentId(1L);

            verify(chunkEntityMapper).deleteByDocumentId(eq(1L));
        }

        @Test
        @DisplayName("始终取锁（即使无任何链接）+ 闸门 acquire/release 配对")
        void alwaysTakesLockAndGate() {
            when(documentMapper.selectScopeById(1L)).thenReturn(new DocumentScope(100L, null));
            when(chunkEntityMapper.selectEntityIdsByDocumentId(1L)).thenReturn(List.of());
            when(cooccurrenceMapper.selectPairCountsByDocumentId(1L)).thenReturn(List.of());

            service.cleanupByDocumentId(1L);

            // §5：advisory 锁始终获取，即使文档当前无任何链接
            verify(scopeLockTemplate).withinScopeLock(eq(100L), eq(null), any());
            verify(scopeWriteGate).tryAcquire(eq(100L), eq(null), anyLong());
            verify(scopeWriteGate).release(eq(100L), eq(null));
            // 无受影响实体：degree/孤儿/stale 跳过
            verify(entityMapper, never()).recalculateDegree(anyList());
        }

        @Test
        @DisplayName("无受影响实体但事件残留 → 事件删除仍执行（fastTrack 孤儿场景）")
        void noAffectedEntities_eventsStillDeleted() {
            when(documentMapper.selectScopeById(1L)).thenReturn(new DocumentScope(100L, null));
            when(chunkEntityMapper.selectEntityIdsByDocumentId(1L)).thenReturn(List.of());
            when(cooccurrenceMapper.selectPairCountsByDocumentId(1L)).thenReturn(List.of());

            service.cleanupByDocumentId(1L);

            verify(eventMapper).deleteByDocumentId(eq(1L));
        }
    }
}
