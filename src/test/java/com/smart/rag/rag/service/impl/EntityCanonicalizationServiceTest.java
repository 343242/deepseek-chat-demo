package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.entity.RagChunkEntity;
import com.smart.rag.rag.entity.RagEntity;
import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.ChunkEntityMapper.ChunkLink;
import com.smart.rag.rag.mapper.ChunkEntityMapper.NewLink;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper.PairCount;
import com.smart.rag.rag.mapper.EntityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityCanonicalizationService 单元测试（V30 写路径：RETURNING 驱动增量递增）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityCanonicalizationService 单元测试（V30 增量写路径）")
class EntityCanonicalizationServiceTest {

    @Mock
    private EntityMapper entityMapper;
    @Mock
    private ChunkEntityMapper chunkEntityMapper;
    @Mock
    private EntityCooccurrenceMapper cooccurrenceMapper;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ScopeLockTemplate scopeLockTemplate;
    @Mock
    private LockRetryExecutor lockRetryExecutor;
    @Mock
    private ScopeWriteGate scopeWriteGate;

    private RagEntityProperties properties;

    private EntityCanonicalizationService service;

    @BeforeEach
    void setUp() {
        properties = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7,
                0.5, 0.3, 0.2, true, null, true, 0, 0, 0, 0, null);
        service = new EntityCanonicalizationService(entityMapper, chunkEntityMapper, cooccurrenceMapper,
                transactionTemplate, scopeLockTemplate, lockRetryExecutor, scopeWriteGate, properties);

        // 模拟 TransactionTemplate：立即执行回调并返回其结果
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        // 模拟 ScopeLockTemplate：直接执行临界区（跳过断言与取锁——SQL 层行为由集成测试覆盖）
        lenient().doAnswer(invocation -> {
            Runnable body = invocation.getArgument(2);
            body.run();
            return null;
        }).when(scopeLockTemplate).withinScopeLock(any(), any(), any());
        // 模拟 LockRetryExecutor：直接执行 action（不做重试）
        lenient().doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(0);
            return action.get();
        }).when(lockRetryExecutor).execute(any(Supplier.class));
    }

    // ==================== canonicalize ====================

    @Nested
    @DisplayName("canonicalize — Level 1 规范化")
    class CanonicalizeTests {

        @Test
        @DisplayName("标准名称：NFC + lowercase + trim")
        void standardName() {
            assertThat(service.canonicalize("  PostgreSQL  ")).isEqualTo("postgresql");
        }

        @Test
        @DisplayName("null → 空字符串")
        void nullInput() {
            assertThat(service.canonicalize(null)).isEmpty();
        }
    }

    // ==================== aggregateAndUpsert（V30 §4）====================

    @Nested
    @DisplayName("aggregateAndUpsert — RETURNING 驱动增量递增")
    class AggregateAndUpsertTests {

        private RagEntity entity(long id, String nameNorm) {
            RagEntity e = new RagEntity();
            e.setId(id);
            e.setNameNorm(nameNorm);
            return e;
        }

        @Test
        @DisplayName("新链接落库：边按 pair 增量 upsert、degree 重算、stale 标记（验证 #4 单 chunk 去重）")
        void newLinks_incrementEdges() {
            when(entityMapper.selectList(any())).thenReturn(List.of(entity(1L, "a"), entity(2L, "b"), entity(3L, "c")));
            // 单 chunk 内 "a" 重复出现 → 候选去重只插一次（验证 #4）
            List<EntityCanonicalizationService.ParsedExtraction> extractions = List.of(
                    new EntityCanonicalizationService.ParsedExtraction("c1", "e1",
                            List.of(pe("A", "d1"), pe("a", "d1"), pe("B", "d2"), pe("C", "d3"))));
            // 既有链接为空（新 chunk）；RETURNING 返回全部 3 条新落库链接
            when(chunkEntityMapper.selectByChunkIds(anyList())).thenReturn(List.of());
            when(chunkEntityMapper.insertBatchReturning(anyList())).thenAnswer(invocation -> {
                List<RagChunkEntity> links = invocation.getArgument(0);
                return links.stream().map(l -> new NewLink(l.getChunkId(), l.getEntityId())).toList();
            });

            EntityCanonicalizationService.AggregateResult result =
                    service.aggregateAndUpsert(extractions, 100L, null, 1L);

            assertThat(result.graphChanged()).isTrue();
            assertThat(result.entityIds()).containsExactlyInAnyOrder(1L, 2L, 3L);

            // 链接插入携带 documentId
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<RagChunkEntity>> linkCaptor = ArgumentCaptor.forClass(List.class);
            verify(chunkEntityMapper).insertBatchReturning(linkCaptor.capture());
            assertThat(linkCaptor.getValue()).hasSize(3);   // a 去重后 1 条 + b + c
            assertThat(linkCaptor.getValue()).allSatisfy(l -> assertThat(l.getDocumentId()).isEqualTo(1L));

            // 边递增：单 chunk 3 实体 → 3 对，各 +1
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PairCount>> pairCaptor = ArgumentCaptor.forClass(List.class);
            verify(cooccurrenceMapper).upsertIncrement(pairCaptor.capture(), eq(100L), eq(null));
            assertThat(pairCaptor.getValue()).containsExactlyInAnyOrder(
                    new PairCount(1L, 2L, 1), new PairCount(1L, 3L, 1), new PairCount(2L, 3L, 1));

            verify(entityMapper).recalculateDegree(anyList());
            verify(entityMapper).markCommunityStale(anyList());
        }

        @Test
        @DisplayName("纯重投递（RETURNING 空）→ graphChanged=false：无边递增、无 stale 标记（验证 #2/#14）")
        void pureRedelivery_zeroDelta() {
            when(entityMapper.selectList(any())).thenReturn(List.of(entity(1L, "a"), entity(2L, "b")));
            List<EntityCanonicalizationService.ParsedExtraction> extractions = List.of(
                    new EntityCanonicalizationService.ParsedExtraction("c1", "e1",
                            List.of(pe("A", "d1"), pe("B", "d2"))));
            // 全部撞主键被吞 → RETURNING 空
            when(chunkEntityMapper.selectByChunkIds(anyList())).thenReturn(List.of(
                    new ChunkLink("c1", 1L), new ChunkLink("c1", 2L)));
            when(chunkEntityMapper.insertBatchReturning(anyList())).thenReturn(List.of());

            EntityCanonicalizationService.AggregateResult result =
                    service.aggregateAndUpsert(extractions, 100L, null, 1L);

            assertThat(result.graphChanged()).isFalse();
            verify(cooccurrenceMapper, never()).upsertIncrement(anyList(), any(), any());
            verify(entityMapper, never()).markCommunityStale(anyList());
            verify(entityMapper).recalculateDegree(anyList());   // degree 仍重算（实体可能新增 description）
        }

        @Test
        @DisplayName("空抽取结果 → 零闸门/零锁/零事务（§4.1 早退先于闸门）")
        void emptyExtractions_earlyExit() {
            EntityCanonicalizationService.AggregateResult result =
                    service.aggregateAndUpsert(List.of(), 100L, null, 1L);

            assertThat(result.entityIds()).isEmpty();
            assertThat(result.graphChanged()).isFalse();
            verify(scopeWriteGate, never()).tryAcquire(any(), any(), anyLong());
            verify(scopeLockTemplate, never()).withinScopeLock(any(), any(), any());
            verify(transactionTemplate, never()).execute(any());
        }

        @Test
        @DisplayName("写闸门 acquire/release 配对（finally 释放）")
        void writeGateAcquiredAndReleased() {
            when(entityMapper.selectList(any())).thenReturn(List.of(entity(1L, "a")));
            when(chunkEntityMapper.selectByChunkIds(anyList())).thenReturn(List.of());
            when(chunkEntityMapper.insertBatchReturning(anyList())).thenReturn(List.of());

            service.aggregateAndUpsert(List.of(new EntityCanonicalizationService.ParsedExtraction(
                    "c1", "e1", List.of(pe("A", "d1")))), 100L, null, 1L);

            verify(scopeWriteGate).tryAcquire(eq(100L), eq(null), anyLong());
            verify(scopeWriteGate).release(eq(100L), eq(null));
        }

        private EntityCanonicalizationService.ParsedEntity pe(String name, String desc) {
            return new EntityCanonicalizationService.ParsedEntity(name, desc, "topic");
        }
    }

    // ==================== computePairDeltas（§4.3 精确 pair 计算）====================

    @Nested
    @DisplayName("computePairDeltas — trueSet = 既有 ∪ 新增（验证 #3）")
    class ComputePairDeltasTests {

        @Test
        @DisplayName("部分新增：chunk 既有 {1,2}，新增 {3} → (1,3)、(2,3) 各 +1，(1,2) 不变")
        void partialNew_pairesWithExisting() {
            Map<String, Set<Long>> existing = new LinkedHashMap<>();
            existing.put("c1", new LinkedHashSet<>(List.of(1L, 2L)));

            List<PairCount> deltas = EntityCanonicalizationService.computePairDeltas(
                    existing, List.of(new NewLink("c1", 3L)));

            assertThat(deltas).containsExactlyInAnyOrder(
                    new PairCount(1L, 3L, 1), new PairCount(2L, 3L, 1));
        }

        @Test
        @DisplayName("纯既有 chunk（不在 newLinks）贡献 0")
        void existingOnlyChunks_contributeZero() {
            Map<String, Set<Long>> existing = new LinkedHashMap<>();
            existing.put("c-old", new LinkedHashSet<>(List.of(1L, 2L)));

            List<PairCount> deltas = EntityCanonicalizationService.computePairDeltas(
                    existing, List.of(new NewLink("c-new", 5L)));

            assertThat(deltas).isEmpty();
        }

        @Test
        @DisplayName("多 chunk 跨实体合并：同 pair 跨 chunk 累加")
        void multiChunk_accumulate() {
            Map<String, Set<Long>> existing = new LinkedHashMap<>();
            List<NewLink> newLinks = List.of(
                    new NewLink("c1", 1L), new NewLink("c1", 2L),
                    new NewLink("c2", 1L), new NewLink("c2", 2L));

            List<PairCount> deltas = EntityCanonicalizationService.computePairDeltas(existing, newLinks);

            assertThat(deltas).containsExactly(new PairCount(1L, 2L, 2));   // (1,2) 在两个 chunk 各 +1
        }

        @Test
        @DisplayName("输出按 (a, b) 升序（§3.4 排序分批）")
        void outputSorted() {
            Map<String, Set<Long>> existing = new LinkedHashMap<>();
            List<NewLink> newLinks = List.of(
                    new NewLink("c1", 9L), new NewLink("c1", 1L), new NewLink("c1", 5L));

            List<PairCount> deltas = EntityCanonicalizationService.computePairDeltas(existing, newLinks);

            assertThat(deltas).containsExactly(
                    new PairCount(1L, 5L, 1), new PairCount(1L, 9L, 1), new PairCount(5L, 9L, 1));
        }
    }
}
