package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.AdjacencyListGraph;
import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityMapper.WeakTieUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link WeakTieScoreCalculator} 单元测试（验证 #18 语义口径：逐字对齐原 updateWeakTieScores CTE）。
 * <p>
 * 期望值手算自 CTE 语义：embeddedness = common / (d1 + d2 - common)（Jaccard），
 * weak_tie = 1 - avg(pairs)；hub（degree ≥ 100）/ 单邻居 / 孤立实体不动（不在批次）。
 */
@DisplayName("WeakTieScoreCalculator — 原 CTE 语义对齐")
class WeakTieScoreCalculatorTest {

    private final WeakTieScoreCalculator calculator = new WeakTieScoreCalculator();

    private static Map<Long, Integer> degrees(long... pairs) {
        Map<Long, Integer> degrees = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            degrees.put(pairs[i], (int) pairs[i + 1]);
        }
        return degrees;
    }

    @Test
    @DisplayName("三角形：每对邻居 emb = 1/3 → weak_tie = 2/3（全对称）")
    void triangle_symmetricTwoThirds() {
        AdjacencyListGraph g = new AdjacencyListGraph();
        g.addEdge(1L, 2L, 1);
        g.addEdge(2L, 3L, 1);
        g.addEdge(1L, 3L, 1);

        List<WeakTieUpdate> result = calculator.compute(g, degrees(1L, 2, 2L, 2, 3L, 2));

        // 实体 1 的邻居对 (2,3)：N(2)={1,3}，N(3)={1,2}，common=1，emb=1/(2+2-1)=1/3 → 1-1/3=2/3
        assertThat(result).hasSize(3);
        for (WeakTieUpdate u : result) {
            assertThat(u.weakTieScore()).isCloseTo(2.0 / 3.0, within(1e-9));
        }
    }

    @Test
    @DisplayName("路径 1-2-3：仅中间实体有邻居对，emb = 1/1 → weak_tie = 0；端点单邻居不动")
    void path_onlyMiddleEntityScored() {
        AdjacencyListGraph g = new AdjacencyListGraph();
        g.addEdge(1L, 2L, 1);
        g.addEdge(2L, 3L, 1);

        List<WeakTieUpdate> result = calculator.compute(g, degrees(1L, 1, 2L, 2, 3L, 1));

        // 实体 2 邻居对 (1,3)：N(1)={2}，N(3)={2}，common=1，emb=1/(1+1-1)=1 → 1-1=0
        assertThat(result).hasSize(1);
        assertThat(result.get(0).entityId()).isEqualTo(2L);
        assertThat(result.get(0).weakTieScore()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("hub（degree ≥ 100）不动——不在批次（原 CTE WHERE re.degree < 100）")
    void hubExcluded() {
        AdjacencyListGraph g = new AdjacencyListGraph();
        g.addEdge(1L, 2L, 1);
        g.addEdge(2L, 3L, 1);
        g.addEdge(1L, 3L, 1);

        // 实体 1 为 hub（degree 列 = 100）→ 跳过；2、3 正常计算
        List<WeakTieUpdate> result = calculator.compute(g, degrees(1L, 100, 2L, 2, 3L, 2));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(WeakTieUpdate::entityId).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("degree 缺省（图外孤立实体不在 graph.nodes()，天然跳过）+ 单邻居实体不动")
    void isolatedAndSingleNeighborExcluded() {
        AdjacencyListGraph g = new AdjacencyListGraph();
        g.addEdge(1L, 2L, 1);   // 1、2 互为唯一邻居 → 无邻居对
        g.addNode(99L);          // 孤立节点（不在 degree 映射也无所谓）

        List<WeakTieUpdate> result = calculator.compute(g, degrees(1L, 1, 2L, 1, 99L, 0));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("输出按 entityId 升序（§3.2.1 防线二：有序写回批次）")
    void outputSortedByEntityId() {
        AdjacencyListGraph g = new AdjacencyListGraph();
        g.addEdge(9L, 5L, 1);
        g.addEdge(5L, 1L, 1);
        g.addEdge(9L, 1L, 1);

        List<WeakTieUpdate> result = calculator.compute(g, degrees(9L, 2, 5L, 2, 1L, 2));

        assertThat(result).extracting(WeakTieUpdate::entityId).containsExactly(1L, 5L, 9L);
    }

    @Test
    @DisplayName("四节点菱形：中心实体混合 embeddedness 精确均值")
    void diamond_mixedAverage() {
        // 菱形：2-3-4 之间互连（三角），1 只连 2、3
        AdjacencyListGraph g = new AdjacencyListGraph();
        g.addEdge(1L, 2L, 1);
        g.addEdge(1L, 3L, 1);
        g.addEdge(2L, 3L, 1);
        g.addEdge(2L, 4L, 1);
        g.addEdge(3L, 4L, 1);

        List<WeakTieUpdate> result = calculator.compute(g,
                degrees(1L, 2, 2L, 3, 3L, 3, 4L, 2));

        Map<Long, Double> byId = new HashMap<>();
        result.forEach(u -> byId.put(u.entityId(), u.weakTieScore()));

        // 实体 1：邻居对 (2,3)：N(2)={1,3,4}，N(3)={1,2,4}，common={1,4}=2，emb=2/(3+3-2)=0.5 → 0.5
        assertThat(byId.get(1L)).isCloseTo(1 - 0.5, within(1e-9));
        // 实体 2：邻居 {1,3,4}；N(1)={2,3}, N(3)={1,2,4}, N(4)={2,3}
        //   对 (1,3)：交集={2}=1，emb=1/(2+3-1)=0.25；对 (1,4)：交集={2,3}=2，emb=2/(2+2-2)=1.0；
        //   对 (3,4)：交集={2}=1，emb=1/(3+2-1)=0.25 → avg=(0.25+1.0+0.25)/3=0.5 → 0.5
        assertThat(byId.get(2L)).isCloseTo(1.0 - 0.5, within(1e-9));
    }
}
