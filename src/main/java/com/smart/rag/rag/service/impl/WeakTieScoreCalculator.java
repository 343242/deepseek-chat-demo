package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityMapper.WeakTieUpdate;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * weak_tie_score 纯内存算法（V30 §3.2.1 read-compute-write：取代原 updateWeakTieScores O(邻居²) CTE）。
 * <p>
 * 无状态纯算法组件（参照 {@code LeidenCommunityDetector} 形态），语义<b>逐字对齐</b>原 CTE
 * （EntityCooccurrenceMapper 退役语句，V21 §5.1）：
 * <ul>
 *   <li>仅计算 {@code rag_entity.degree < 100} 的实体（hub 预算：hub 维持默认 0.5）；</li>
 *   <li>embeddedness = |N(n1) ∩ N(n2)| / (|N(n1)| + |N(n2)| - |N(n1) ∩ N(n2)|)（Jaccard），
 *       分母为 0 的 pair 不计入 avg（SQL avg 忽略 NULL，第四轮对拍口径）；</li>
 *   <li>仅输出<b>有邻居对</b>的实体（无邻居 / 单邻居实体不在结果集，保持默认值）；</li>
 *   <li>无有效 pair（avg 为 NULL）→ 0.5；否则 1 - avg；</li>
 *   <li>hub（degree ≥ 100）与孤立实体<b>不动</b>（不在批次 → updateWeakTieBatch 不触碰）。</li>
 * </ul>
 * 输出按 entityId 升序（§3.2.1 防线二：写回批次有序）。
 */
@Component
public class WeakTieScoreCalculator {

    /** 原 CTE 的 hub 性能预算（WHERE re.degree &lt; 100）。 */
    static final int DEGREE_BUDGET = 100;

    /**
     * @param graph         scope 共现图（邻接即 v_entity_neighbors 的双向展开）
     * @param entityDegrees rag_entity.degree 列快照（hub 预算判定；图外实体无邻居、天然不出现在结果中）
     * @return 有邻居对且 degree&lt;100 的实体分值（entityId 升序）；其余实体不在批次
     */
    public List<WeakTieUpdate> compute(WeightedGraph graph, Map<Long, Integer> entityDegrees) {
        List<WeakTieUpdate> result = new ArrayList<>();
        for (Long node : graph.nodes()) {
            long entityId = node;
            int degree = entityDegrees.getOrDefault(entityId, 0);
            if (degree >= DEGREE_BUDGET) {
                continue;   // hub 预算：维持默认 0.5（原 CTE WHERE re.degree < 100）
            }
            Long2DoubleMap neighborMap = graph.neighbors(entityId);
            long[] neighbors = neighborMap.keySet().toLongArray();
            if (neighbors.length < 2) {
                continue;   // 无邻居对：不在 embeddedness 结果集，保持默认
            }
            double sum = 0;
            int count = 0;
            for (int i = 0; i < neighbors.length; i++) {
                for (int j = i + 1; j < neighbors.length; j++) {
                    long n1 = neighbors[i];
                    long n2 = neighbors[j];
                    long common = commonNeighborCount(graph, n1, n2);
                    long denom = graph.neighbors(n1).size() + graph.neighbors(n2).size() - common;
                    if (denom == 0) {
                        continue;   // NULLIF(...)：分母 0 的 pair 计 NULL，avg 忽略
                    }
                    sum += (double) common / denom;
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }
            double avg = sum / count;
            result.add(new WeakTieUpdate(entityId, 1.0 - avg));   // COALESCE(1.0 - avg, 0.5)
        }
        result.sort(Comparator.comparingLong(WeakTieUpdate::entityId));
        return result;
    }

    /** |N(n1) ∩ N(n2)|（邻接集合求交；双方至少互为自环邻居——有边才有邻接）。 */
    private static long commonNeighborCount(WeightedGraph graph, long n1, long n2) {
        Long2DoubleMap neighbors1 = graph.neighbors(n1);
        Long2DoubleMap neighbors2 = graph.neighbors(n2);
        // 以较小集遍历、较大集建哈希（neighbors() 每次返回新实例，不能以引用相等判定方向）
        Long2DoubleMap iterate = neighbors1.size() <= neighbors2.size() ? neighbors1 : neighbors2;
        Long2DoubleMap lookup = iterate == neighbors1 ? neighbors2 : neighbors1;
        LongSet lookupSet = new LongOpenHashSet(lookup.keySet());
        long common = 0;
        for (long candidate : iterate.keySet()) {
            if (lookupSet.contains(candidate)) {
                common++;
            }
        }
        return common;
    }
}
