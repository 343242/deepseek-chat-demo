package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.AdjacencyListGraph;
import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EntityMapper.BridgeUpdate;
import com.smart.rag.rag.mapper.EntityMapper.CommunityAssignment;
import com.smart.rag.rag.mapper.EntityMapper.EntityMeta;
import com.smart.rag.rag.mapper.EntityMapper.WeakTieUpdate;
import com.smart.rag.rag.service.impl.CooccurrenceGraphLoader.ScopeGraphSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link CommunityDetectionJob} 单元测试（V30 统一 derive 编排）。
 * <p>
 * 用真实 {@link WeightedGraph}（合成图）+ 真实 {@link WeakTieScoreCalculator} + mock 写回链，
 * 验证 §6 阶段二编排：锁外 load+计算 → 锁内有序写回（communities / weakTie / bridge / clearStale
 * 单事务原子）。Leiden 是确定性纯算法，合成图上的社区划分可复现。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityDetectionJob — V30 统一 derive 编排")
class CommunityDetectionJobTest {

    @Mock
    private CooccurrenceGraphLoader graphLoader;
    @Mock
    private EntityMapper entityMapper;
    @Mock
    private ScopeLockTemplate scopeLockTemplate;
    @Mock
    private LockRetryExecutor lockRetryExecutor;
    @Mock
    private TransactionTemplate transactionTemplate;

    private CommunityDetectionJob job;

    @BeforeEach
    void setUp() {
        job = new CommunityDetectionJob(graphLoader, entityMapper, new WeakTieScoreCalculator(),
                scopeLockTemplate, lockRetryExecutor, transactionTemplate);

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

    @Test
    @DisplayName("双三角形 + 桥 — Leiden 划分 2 社区，四类写回全执行且按 entityId 升序")
    void run_twoTriangles_fullDeriveWriteBack() {
        WeightedGraph graph = twoTrianglesBridgeGraph();
        List<EntityMeta> entities = List.of(
                new EntityMeta(6L, 2), new EntityMeta(1L, 2), new EntityMeta(3L, 2),
                new EntityMeta(2L, 2), new EntityMeta(4L, 2), new EntityMeta(5L, 2));
        when(graphLoader.loadScopeGraph(10L, null))
                .thenReturn(new ScopeGraphSnapshot(graph, entities));

        job.run(10L, null);

        // 社区分配写回（entityId 升序）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommunityAssignment>> communityCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityMapper).batchUpdateCommunities(eq(10L), eq(null), communityCaptor.capture());
        List<CommunityAssignment> assignments = communityCaptor.getValue();
        assertThat(assignments).hasSize(6);
        assertThat(assignments).extracting(CommunityAssignment::entityId).isSorted();

        // 桥接实体 C、D 在不同社区
        Set<Integer> triangleABC = new HashSet<>();
        Set<Integer> triangleDEF = new HashSet<>();
        for (CommunityAssignment a : assignments) {
            if (a.entityId() <= 3) triangleABC.add(a.communityId());
            else triangleDEF.add(a.communityId());
        }
        assertThat(triangleABC).hasSize(1);
        assertThat(triangleDEF).hasSize(1);
        assertThat(triangleABC).isNotEqualTo(triangleDEF);

        // weak_tie 写回：三角形每个节点有邻居对 → 全部 6 实体进批次（升序）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WeakTieUpdate>> weakTieCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityMapper).updateWeakTieBatch(weakTieCaptor.capture());
        assertThat(weakTieCaptor.getValue()).hasSize(6);
        assertThat(weakTieCaptor.getValue()).extracting(WeakTieUpdate::entityId).isSorted();

        // bridge 写回：覆盖全部实体（升序）；桥节点 C(3)/F(6) 的 bridge = 1，其余 = 0
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BridgeUpdate>> bridgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityMapper).updateBridgeBatch(bridgeCaptor.capture());
        assertThat(bridgeCaptor.getValue()).hasSize(6);
        assertThat(bridgeCaptor.getValue()).extracting(BridgeUpdate::entityId).isSorted();
        Map<Long, Double> bridgeById = new java.util.HashMap<>();
        bridgeCaptor.getValue().forEach(b -> bridgeById.put(b.entityId(), b.bridgeScore()));
        assertThat(bridgeById.get(3L)).isEqualTo(1.0);
        assertThat(bridgeById.get(6L)).isEqualTo(1.0);
        assertThat(bridgeById.get(1L)).isEqualTo(0.0);

        verify(entityMapper).clearStaleFlag(10L, null);
    }

    @Test
    @DisplayName("空图 — 跳过 Leiden/社区/bridge，仍清除 stale（§5.2⑤ 全量语义）")
    void run_emptyGraph_skipsLeiden_clearsStale() {
        when(graphLoader.loadScopeGraph(10L, null))
                .thenReturn(new ScopeGraphSnapshot(new AdjacencyListGraph(), List.of()));

        job.run(10L, null);

        verify(entityMapper, never()).batchUpdateCommunities(anyLong(), any(), anyList());
        verify(entityMapper, never()).updateWeakTieBatch(anyList());
        verify(entityMapper, never()).updateBridgeBatch(anyList());
        verify(entityMapper).clearStaleFlag(10L, null);
    }

    @Test
    @DisplayName("含孤立实体（degree=0、图外）— bridge reset-0 覆盖（§6 阶段二 reset 语义）")
    void run_isolatedEntity_bridgeResetZero() {
        AdjacencyListGraph graph = new AdjacencyListGraph();
        graph.addEdge(1L, 2L, 1.0);
        graph.addEdge(2L, 3L, 1.0);
        graph.addEdge(1L, 3L, 1.0);
        // 实体 99 孤立（图外）
        List<EntityMeta> entities = List.of(
                new EntityMeta(1L, 2), new EntityMeta(2L, 2), new EntityMeta(3L, 2), new EntityMeta(99L, 0));
        when(graphLoader.loadScopeGraph(10L, null))
                .thenReturn(new ScopeGraphSnapshot(graph, entities));

        job.run(10L, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BridgeUpdate>> bridgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityMapper).updateBridgeBatch(bridgeCaptor.capture());
        Map<Long, Double> bridgeById = new java.util.HashMap<>();
        bridgeCaptor.getValue().forEach(b -> bridgeById.put(b.entityId(), b.bridgeScore()));
        assertThat(bridgeById.get(99L)).isEqualTo(0.0);   // 孤立实体 reset 0
    }

    /**
     * 两个三角形由一条桥边连接（权重 1.0，桥 0.1）。Leiden 应将两三角形分入不同社区。
     */
    private static WeightedGraph twoTrianglesBridgeGraph() {
        AdjacencyListGraph g = new AdjacencyListGraph();
        g.addEdge(1L, 2L, 1.0);
        g.addEdge(2L, 3L, 1.0);
        g.addEdge(1L, 3L, 1.0);
        g.addEdge(4L, 5L, 1.0);
        g.addEdge(5L, 6L, 1.0);
        g.addEdge(4L, 6L, 1.0);
        g.addEdge(3L, 6L, 0.1);
        return g;
    }
}
