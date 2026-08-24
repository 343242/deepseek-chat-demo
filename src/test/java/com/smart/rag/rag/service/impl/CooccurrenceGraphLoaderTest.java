package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper.CooccurrenceRow;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EntityMapper.EntityMeta;
import com.smart.rag.rag.service.impl.CooccurrenceGraphLoader.ScopeGraphSnapshot;
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
 * {@link CooccurrenceGraphLoader} 单元测试（V30：图 + 实体清单单次快照）。
 * <p>
 * Mock mapper 返回合成边/实体，验证"DB → 图快照"转换：节点数、边权（双向）、邻居集合、
 * degree 映射。SQL 正确性由真实 PG 集成测试覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CooccurrenceGraphLoader — DB 边 + 实体清单 → 图快照")
class CooccurrenceGraphLoaderTest {

    @Mock
    private EntityCooccurrenceMapper cooccurrenceMapper;

    @Mock
    private EntityMapper entityMapper;

    @InjectMocks
    private CooccurrenceGraphLoader loader;

    @Test
    @DisplayName("三角形共现图（A-B-C）— 3 节点 3 边，边权双向一致，degree 映射覆盖图外实体")
    void loadScopeGraph_triangleWithEntityMetas() {
        when(cooccurrenceMapper.selectByScope(10L, null)).thenReturn(List.of(
                new CooccurrenceRow(1L, 2L, 3),
                new CooccurrenceRow(2L, 3L, 2),
                new CooccurrenceRow(1L, 3L, 1)
        ));
        // 实体 99 孤立（图外）——bridge reset-0 语义需覆盖
        when(entityMapper.selectScopeEntityMetas(10L, null)).thenReturn(List.of(
                new EntityMeta(1L, 2), new EntityMeta(2L, 2), new EntityMeta(3L, 2), new EntityMeta(99L, 0)));

        ScopeGraphSnapshot snapshot = loader.loadScopeGraph(10L, null);
        WeightedGraph graph = snapshot.graph();

        assertThat(graph.nodeCount()).isEqualTo(3);
        assertThat(graph.edgeWeight(1L, 2L)).isEqualTo(3.0);
        assertThat(graph.edgeWeight(2L, 1L)).isEqualTo(3.0);
        assertThat(graph.edgeWeight(2L, 3L)).isEqualTo(2.0);
        assertThat(graph.edgeWeight(1L, 3L)).isEqualTo(1.0);

        // 实体清单含图外孤立实体；degrees 映射可查
        assertThat(snapshot.entities()).hasSize(4);
        assertThat(snapshot.degrees()).containsEntry(99L, 0);
        assertThat(snapshot.degrees()).containsEntry(1L, 2);
    }

    @Test
    @DisplayName("空共现图 — nodeCount=0，实体清单可为空，不抛异常")
    void loadScopeGraph_empty_noException() {
        when(cooccurrenceMapper.selectByScope(10L, 20L)).thenReturn(List.of());
        when(entityMapper.selectScopeEntityMetas(10L, 20L)).thenReturn(List.of());

        ScopeGraphSnapshot snapshot = loader.loadScopeGraph(10L, 20L);

        assertThat(snapshot.graph().nodeCount()).isZero();
        assertThat(snapshot.entities()).isEmpty();
    }

    @Test
    @DisplayName("重复边累加 — 同一对实体多次投影累加权重（共现图契约）")
    void loadScopeGraph_duplicateEdges_accumulateWeight() {
        when(cooccurrenceMapper.selectByScope(10L, null)).thenReturn(List.of(
                new CooccurrenceRow(1L, 2L, 2),
                new CooccurrenceRow(1L, 2L, 3)
        ));
        when(entityMapper.selectScopeEntityMetas(10L, null))
                .thenReturn(List.of(new EntityMeta(1L, 1), new EntityMeta(2L, 1)));

        ScopeGraphSnapshot snapshot = loader.loadScopeGraph(10L, null);

        assertThat(snapshot.graph().edgeWeight(1L, 2L)).isEqualTo(5.0);
        assertThat(snapshot.graph().nodeCount()).isEqualTo(2);
    }
}
