package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * weak_tie_score 计算编排（§5.1）。
 * <p>
 * SRP：仅编排"全量重投影 → 弱联系计算"，不持有算法或检测逻辑。
 * 封装 weak_tie 生命周期所需的共现图刷新：clear stale edges（§5.3 全量重投影）→
 * 重新投影（§5.4）→ 计算 weak_tie（§5.1 CTE）。
 * <p>
 * 设计文档 §10.2 定义此类为"weak_tie_score 计算（纯 SQL 驱动）"的薄 Service。
 */
@Component
public class EntityIndexService {

    private final EntityCooccurrenceMapper cooccurrenceMapper;

    public EntityIndexService(EntityCooccurrenceMapper cooccurrenceMapper) {
        this.cooccurrenceMapper = cooccurrenceMapper;
    }

    /**
     * 全量重算作用域内 weak_tie_score。
     * <p>
     * 三步序列保证正确性：
     * <ol>
     *   <li>{@code deleteByScope} — 清除作用域旧共现边（§5.3：全量重投影自然去除失效边；
     *       INSERT...ON CONFLICT 无法删除旧行，故需显式清除）。</li>
     *   <li>{@code projectCooccurrence} — 从 rag_chunk_entity 重新投影（§5.4，幂等）。</li>
     *   <li>{@code updateWeakTieScores} — 在刷新后的共现图上计算 weak_tie（§5.1 CTE）。</li>
     * </ol>
     * delete → project 保证文档删除后的失效边被清除，使 weak_tie 反映当前数据（AC1 删除场景）。
     *
     * @param userId 用户作用域
     * @param teamId 团队作用域（可为 null）
     */
    public void recomputeWeakTieScores(Long userId, @Nullable Long teamId) {
        cooccurrenceMapper.deleteByScope(userId, teamId);
        cooccurrenceMapper.projectCooccurrence(userId, teamId);
        cooccurrenceMapper.updateWeakTieScores(userId, teamId);
    }
}
