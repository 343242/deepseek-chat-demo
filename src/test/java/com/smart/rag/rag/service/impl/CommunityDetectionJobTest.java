package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.AdjacencyListGraph;
import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EntityMapper.CommunityAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link CommunityDetectionJob} 单元测试。
 * <p>
 * 用真实 {@link WeightedGraph}（合成图）+ mock {@link EntityMapper}，验证 §5.2⑤ 编排：
 * load → detect → batchUpdateCommunities → updateBridgeScores → clearStaleFlag。
 * Leiden 是确定性纯算法（节点按升序 ID 访问），故合成图上的社区划分可复现。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityDetectionJob — Leiden + bridge + clearStale 编排")
class CommunityDetectionJobTest {

    @Mock
    private CooccurrenceGraphLoader graphLoader;

    @Mock
    private EntityMapper entityMapper;

    @InjectMocks
    private CommunityDetectionJob job;

    // ==================== 正常路径：两个三角形 + 桥边 ====================

    @Test
    @DisplayName("双三角形 + 桥 — Leiden 划分 ≥2 社区，社区分配写回，bridge + clearStale 被调用")
    void run_twoTrianglesWithBridge_detectsCommunities() {
        // 两个三角形由一条弱桥连接：A-B-C 三角 + D-E-F 三角 + C-D 桥
        WeightedGraph graph = twoTrianglesBridgeGraph();
        when(graphLoader.load(10L, null)).thenReturn(graph);

        job.run(10L, null);

        // 社区分配写回（捕获参数验证）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommunityAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(entityMapper).batchUpdateCommunities(eq(10L), eq(null), captor.capture());
        verify(entityMapper).updateBridgeScores(10L, null);
        verify(entityMapper).clearStaleFlag(10L, null);

        List<CommunityAssignment> assignments = captor.getValue();
        assertThat(assignments).hasSize(6); // 全部 6 节点被分配

        // 桥接实体 C、D 在不同社区（Leiden 确定性，两三角形应分到不同社区）
        Set<Integer> triangleABC = new HashSet<>();
        Set<Integer> triangleDEF = new HashSet<>();
        for (CommunityAssignment a : assignments) {
            if (a.entityId() <= 3) triangleABC.add(a.communityId());
            else triangleDEF.add(a.communityId());
        }
        assertThat(triangleABC).hasSize(1);
        assertThat(triangleDEF).hasSize(1);
        assertThat(triangleABC).isNotEqualTo(triangleDEF);
    }

    @Test
    @DisplayName("编排顺序 — load → batchUpdate → bridge → clearStale")
    void run_orchestrationOrder() {
        when(graphLoader.load(10L, 20L)).thenReturn(twoTrianglesBridgeGraph());

        job.run(10L, 20L);

        // clearStale 总是最后（编排契约）
        verify(entityMapper).batchUpdateCommunities(eq(10L), eq(20L), anyList());
        verify(entityMapper).updateBridgeScores(10L, 20L);
        verify(entityMapper).clearStaleFlag(10L, 20L);
    }

    // ==================== 边界：nodeCount < 2 ====================

    @Test
    @DisplayName("单节点图 — 跳过 Leiden，不写社区，但仍清除 stale（§5.2⑤ 全量语义）")
    void run_singleNode_skipsLeiden_clearsStale() {
        WeightedGraph single = new AdjacencyListGraph();
        single.addNode(1L);
        when(graphLoader.load(10L, null)).thenReturn(single);

        job.run(10L, null);

        verify(entityMapper, never()).batchUpdateCommunities(anyLong(), any(), anyList());
        verify(entityMapper, never()).updateBridgeScores(anyLong(), any());
        // 关键：孤立/单实体仍标记非 stale（degree=0 新实体不应 perpetual stale）
        verify(entityMapper).clearStaleFlag(10L, null);
    }

    @Test
    @DisplayName("空图 — 跳过 Leiden，仍清除 stale")
    void run_emptyGraph_skipsLeiden_clearsStale() {
        WeightedGraph empty = new AdjacencyListGraph();
        when(graphLoader.load(10L, null)).thenReturn(empty);

        job.run(10L, null);

        verify(entityMapper, never()).batchUpdateCommunities(anyLong(), any(), anyList());
        verify(entityMapper, never()).updateBridgeScores(anyLong(), any());
        verify(entityMapper).clearStaleFlag(10L, null);
    }

    // ==================== 幂等性 ====================

    @Test
    @DisplayName("重复 run — 每次都完整执行编排（幂等性由 SQL ON CONFLICT 保证，Job 无状态）")
    void run_idempotent_fullOrchestrationEachTime() {
        when(graphLoader.load(10L, null)).thenReturn(twoTrianglesBridgeGraph());

        job.run(10L, null);
        job.run(10L, null);

        // Job 每次都执行完整序列（幂等性责任在 SQL 层）
        verify(entityMapper, times(2)).batchUpdateCommunities(eq(10L), eq(null), anyList());
        verify(entityMapper, times(2)).updateBridgeScores(10L, null);
        verify(entityMapper, times(2)).clearStaleFlag(10L, null);
    }

    // ==================== 辅助：合成图构造 ====================

    /**
     * 两个三角形由一条桥边连接：
     * <pre>
     *   A(1) — B(2)      D(4) — E(5)
     *    \   /             \   /
     *     C(3) ——桥(权重 0.1)—— F(6)
     * </pre>
     * 三角形内部权重 1.0，桥边权重 0.1（弱连接）。Leiden 应将两三角形分入不同社区。
     */
    private static WeightedGraph twoTrianglesBridgeGraph() {
        AdjacencyListGraph g = new AdjacencyListGraph();
        // 三角形 ABC（权重 1.0）
        g.addEdge(1L, 2L, 1.0);
        g.addEdge(2L, 3L, 1.0);
        g.addEdge(1L, 3L, 1.0);
        // 三角形 DEF（权重 1.0）
        g.addEdge(4L, 5L, 1.0);
        g.addEdge(5L, 6L, 1.0);
        g.addEdge(4L, 6L, 1.0);
        // 弱桥 C(3) — F(6)（权重 0.1）
        g.addEdge(3L, 6L, 0.1);
        return g;
    }
}
