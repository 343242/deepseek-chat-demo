package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper.CooccurrenceRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link CooccurrenceGraphLoader} 单元测试。
 * <p>
 * Mock {@link EntityCooccurrenceMapper#selectByScope} 返回合成边，验证"DB → WeightedGraph"转换：
 * 节点数、边权（双向）、邻居集合、加权度数。SQL 正确性由真实 PG 集成测试覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CooccurrenceGraphLoader — DB 边 → WeightedGraph 转换")
class CooccurrenceGraphLoaderTest {

    @Mock
    private EntityCooccurrenceMapper cooccurrenceMapper;

    @InjectMocks
    private CooccurrenceGraphLoader loader;

    @Test
    @DisplayName("三角形共现图（A-B-C）— 3 节点 3 边，边权双向一致")
    void load_triangle_threeNodes_bidirectionalEdges() {
        // 三角形：A(1)-B(2) co=3, B(2)-C(3) co=2, A(1)-C(3) co=1
        when(cooccurrenceMapper.selectByScope(10L, null)).thenReturn(List.of(
                new CooccurrenceRow(1L, 2L, 3),
                new CooccurrenceRow(2L, 3L, 2),
                new CooccurrenceRow(1L, 3L, 1)
        ));

        WeightedGraph graph = loader.load(10L, null);

        assertThat(graph.nodeCount()).isEqualTo(3);
        // 边权双向一致（无向图契约）
        assertThat(graph.edgeWeight(1L, 2L)).isEqualTo(3.0);
        assertThat(graph.edgeWeight(2L, 1L)).isEqualTo(3.0);
        assertThat(graph.edgeWeight(2L, 3L)).isEqualTo(2.0);
        assertThat(graph.edgeWeight(1L, 3L)).isEqualTo(1.0);
        // 无边返回 0
        assertThat(graph.edgeWeight(1L, 99L)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("邻居集合 — 节点 A 的邻居为 {B, C}")
    void load_neighborsCorrect() {
        when(cooccurrenceMapper.selectByScope(10L, null)).thenReturn(List.of(
                new CooccurrenceRow(1L, 2L, 3),
                new CooccurrenceRow(1L, 3L, 1),
                new CooccurrenceRow(2L, 3L, 2)
        ));

        WeightedGraph graph = loader.load(10L, null);

        assertThat(graph.neighbors(1L).keySet()).containsExactlyInAnyOrder(2L, 3L);
        assertThat(graph.neighbors(2L).keySet()).containsExactlyInAnyOrder(1L, 3L);
        // 加权度数 = 入射边权和（双向），k_A = 3+1 = 4
        assertThat(graph.weightedDegree(1L)).isEqualTo(4.0);
    }

    @Test
    @DisplayName("空共现图 — nodeCount=0，不抛异常")
    void load_emptyGraph_zeroNodes() {
        when(cooccurrenceMapper.selectByScope(10L, 20L)).thenReturn(List.of());

        WeightedGraph graph = loader.load(10L, 20L);

        assertThat(graph.nodeCount()).isZero();
        assertThat(graph.nodes()).isEmpty();
    }

    @Test
    @DisplayName("重复边累加 — 同一对实体多次 INSERT 累加权重（共现图契约）")
    void load_duplicateEdges_accumulateWeight() {
        // 同一对 (1,2) 出现两次（模拟重复投影数据）→ 权重累加 2+3=5
        when(cooccurrenceMapper.selectByScope(10L, null)).thenReturn(List.of(
                new CooccurrenceRow(1L, 2L, 2),
                new CooccurrenceRow(1L, 2L, 3)
        ));

        WeightedGraph graph = loader.load(10L, null);

        assertThat(graph.edgeWeight(1L, 2L)).isEqualTo(5.0);
        assertThat(graph.nodeCount()).isEqualTo(2);
    }
}
