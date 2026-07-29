package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.LouvainCommunityDetector;
import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EntityMapper.CommunityAssignment;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 离线社区检测编排任务（§5.2 ⑤）。
 * <p>
 * SRP：仅编排"加载 → 检测 → 写回"，不持有算法逻辑（{@link LouvainCommunityDetector}）或
 * 数据加载逻辑（{@link CooccurrenceGraphLoader}）。
 * DIP：Detector 为无状态纯算法，直接 {@code new} 构造即用（§5.2 注释：无需 Factory 抽象）。
 * <p>
 * 编排序列（§5.2⑤）：load → (nodeCount &lt; 2 跳过 Louvain) → batchUpdateCommunities →
 * updateBridgeScores → clearStaleFlag。
 * <p>
 * 前置条件：调用方须先刷新共现图（{@link EntityIndexService#recomputeWeakTieScores}），
 * 因 {@code load()} 从 rag_entity_cooccurrence 读取。§8.1 Step 6 保证此顺序。
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class CommunityDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(CommunityDetectionJob.class);

    private final CooccurrenceGraphLoader graphLoader;
    private final EntityMapper entityMapper;

    public CommunityDetectionJob(CooccurrenceGraphLoader graphLoader, EntityMapper entityMapper) {
        this.graphLoader = graphLoader;
        this.entityMapper = entityMapper;
    }

    /**
     * 对作用域执行社区检测 + bridge_score 计算 + stale 清除。
     * <p>
     * {@code nodeCount < 2}（孤立/单实体共现图）时跳过 Louvain——单实体无社区意义。
     * 但仍执行 {@code clearStaleFlag}：孤立实体（degree=0、不在共现图中）也应标记非 stale
     * （§5.2⑤ 注释 + §9.2 community_stale_entity_ratio → 0 目标），避免 perpetual stale 状态。
     * 此处偏离 §5.2⑤ 骨架的字面 {@code return}，但符合其 clearStale 全量语义。
     *
     * @param userId 用户作用域
     * @param teamId 团队作用域（可为 null）
     */
    public void run(Long userId, @Nullable Long teamId) {
        WeightedGraph graph = graphLoader.load(userId, teamId);

        if (graph.nodeCount() >= 2) {
            Long2IntMap communities = new LouvainCommunityDetector(graph).detect();
            entityMapper.batchUpdateCommunities(userId, teamId, toAssignments(communities));
            entityMapper.updateBridgeScores(userId, teamId);

            log.info("Community detection completed: {} nodes, {} communities for userId={}, teamId={}",
                    graph.nodeCount(), countCommunities(communities), userId, teamId);
        } else {
            log.info("Skipping Louvain community detection: nodeCount={} < 2 for userId={}, teamId={}",
                    graph.nodeCount(), userId, teamId);
        }

        // 全量清除作用域 stale（§5.2⑤）：含孤立实体，保证 §9.2 stale ratio → 0。
        entityMapper.clearStaleFlag(userId, teamId);
    }

    /**
     * 将 Louvain 输出的 Long2IntMap 转为 MyBatis 可批量写回的列表
     * （fastutil 原始类型 map 不直传 MyBatis，避免类型处理器复杂度）。
     */
    private static List<CommunityAssignment> toAssignments(Long2IntMap communities) {
        List<CommunityAssignment> assignments = new ArrayList<>(communities.size());
        communities.forEach((entityId, communityId) ->
                assignments.add(new CommunityAssignment(entityId, communityId)));
        return assignments;
    }

    private static int countCommunities(Long2IntMap communities) {
        Set<Integer> unique = new HashSet<>();
        communities.forEach((k, v) -> unique.add(v));
        return unique.size();
    }
}
