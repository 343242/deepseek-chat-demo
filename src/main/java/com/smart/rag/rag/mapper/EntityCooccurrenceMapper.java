package com.smart.rag.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rag_entity_cooccurrence 共现图数据访问（结构分计算输入）。
 * <p>
 * SRP：仅数据访问。所有复杂 SQL（共现投影 §5.4、weak_tie CTE §5.1）走 XML。
 * 调用方：{@code CooccurrenceGraphLoader}（读取）、{@code EntityIndexService}（投影 + weak_tie 编排）。
 * <p>
 * 作用域隔离：所有方法严格按 {@code user_id + COALESCE(team_id, -1)} 限定（§3.2 / §10.1）。
 */
@Mapper
public interface EntityCooccurrenceMapper {

    /**
     * 共现边行（无向，entity_a &lt; entity_b）。
     */
    record CooccurrenceRow(long entityA, long entityB, int coCount) {}

    /**
     * 读取作用域内全部共现边，供 {@code CooccurrenceGraphLoader} 构造 WeightedGraph。
     */
    List<CooccurrenceRow> selectByScope(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 清除作用域内全部共现边。全量重投影前置——去除已失效边（§5.3：
     * "全量重投影自然去除失效边"，但 INSERT...ON CONFLICT 无法删除旧行，故需显式清除）。
     */
    void deleteByScope(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 从 rag_chunk_entity 投影共现图（§5.4 INSERT...ON CONFLICT，LEAST/GREATEST 规范边方向）。
     * 幂等：相同数据重跑不产生重复行、co_count 不变。
     */
    void projectCooccurrence(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 批量计算 weak_tie_score（§5.1 CTE：neighbor_sets → neighbor_pairs → embeddedness → UPDATE）。
     * {@code WHERE degree < 100} 性能预算：hub 实体维持默认 0.5。
     * 无邻居或无邻居对的实体保持默认（不在 embeddedness 结果集中）。
     */
    void updateWeakTieScores(@Param("userId") Long userId, @Param("teamId") Long teamId);
}
