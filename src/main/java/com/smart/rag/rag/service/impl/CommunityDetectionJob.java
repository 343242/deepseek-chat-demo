package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.LeidenCommunityDetector;
import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EntityMapper.BridgeUpdate;
import com.smart.rag.rag.mapper.EntityMapper.CommunityAssignment;
import com.smart.rag.rag.mapper.EntityMapper.WeakTieUpdate;
import com.smart.rag.rag.service.impl.CooccurrenceGraphLoader.ScopeGraphSnapshot;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * derive 统一编排（V30 §6 阶段二 / §6.1：取代旧"recomputeWeakTieScores + CommunityDetectionJob"两段式）。
 * <p>
 * read-compute-write 形态（§3.2.1）：
 * <ol>
 *   <li><b>锁外</b>单次图快照（{@link CooccurrenceGraphLoader#loadScopeGraph}——Leiden 是 CPU 密集
 *       内存计算，不得持有 advisory 锁与连接）；</li>
 *   <li><b>锁外</b>纯内存计算：{@link WeakTieScoreCalculator}（逐字对齐原 CTE）+
 *       {@link LeidenCommunityDetector} + bridge（Leiden 分区 + 邻接计数）；三个分值共享同一快照
 *       （原实现 weak_tie 与 Leiden 各读各的快照，存在跨快照混代）；</li>
 *   <li><b>锁内</b>（{@code ScopeLockTemplate} + {@code LockRetryExecutor} 整事务重试）按 entityId
 *       升序批量写回：communities / weak_tie / bridge / clearStale —— 单写回事务原子，
 *       消除原"四步自动提交链（updateWeakTieScores → Leiden 写回 → bridge → clearStale）
 *       中途失败留半代状态"的漂移根因（V30 §3.2.1）。</li>
 * </ol>
 * 调用方：{@link DeriveDebouncer}（写路径防抖入口）、{@code EntityGraphReconcileJob}（对账阶段二）。
 */
@Component
public class CommunityDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(CommunityDetectionJob.class);

    private final CooccurrenceGraphLoader graphLoader;
    private final EntityMapper entityMapper;
    private final WeakTieScoreCalculator weakTieCalculator;
    private final ScopeLockTemplate scopeLockTemplate;
    private final LockRetryExecutor lockRetryExecutor;
    private final TransactionTemplate transactionTemplate;

    public CommunityDetectionJob(CooccurrenceGraphLoader graphLoader,
                                 EntityMapper entityMapper,
                                 WeakTieScoreCalculator weakTieCalculator,
                                 ScopeLockTemplate scopeLockTemplate,
                                 LockRetryExecutor lockRetryExecutor,
                                 TransactionTemplate transactionTemplate) {
        this.graphLoader = graphLoader;
        this.entityMapper = entityMapper;
        this.weakTieCalculator = weakTieCalculator;
        this.scopeLockTemplate = scopeLockTemplate;
        this.lockRetryExecutor = lockRetryExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 对作用域执行全 derive：锁外 load + 计算 → 锁内有序写回（weak_tie + Leiden + bridge + clearStale）。
     * <p>
     * {@code nodeCount < 2}（孤立/单实体共现图）时跳过 Leiden——单实体无社区意义；
     * 但仍执行 clearStaleFlag（孤立实体也应标记非 stale，§5.2⑤）与 weak_tie 写回（空批次跳过）。
     * 与旧实现差异：bridge 不再以 SQL 全 scope 重算，改为快照内覆盖全 scope 实体（reset-0 语义）。
     *
     * @param userId 用户作用域
     * @param teamId 团队作用域（可为 null）
     */
    public void run(Long userId, @Nullable Long teamId) {
        // —— 锁外：单次读快照 ——
        ScopeGraphSnapshot snapshot = graphLoader.loadScopeGraph(userId, teamId);
        WeightedGraph graph = snapshot.graph();

        // —— 锁外：纯内存计算（三个分值共享同一快照）——
        List<WeakTieUpdate> weakTie = weakTieCalculator.compute(graph, snapshot.degrees());
        List<CommunityAssignment> communities = List.of();
        List<BridgeUpdate> bridges = List.of();
        if (graph.nodeCount() >= 2) {
            Long2IntMap communityMap = new LeidenCommunityDetector(graph).detect();
            communities = toAssignments(communityMap);
            bridges = computeBridges(graph, snapshot.entities(), communityMap);
            log.info("Community detection completed: {} nodes, {} communities for userId={}, teamId={}",
                    graph.nodeCount(), countCommunities(communityMap), userId, teamId);
        } else {
            log.info("Skipping Leiden community detection: nodeCount={} < 2 for userId={}, teamId={}",
                    graph.nodeCount(), userId, teamId);
        }

        // —— 锁内：有序写回（单事务原子，整事务重试）——
        List<CommunityAssignment> c = communities;
        List<BridgeUpdate> b = bridges;
        lockRetryExecutor.execute(() ->
                transactionTemplate.executeWithoutResult(status ->
                        scopeLockTemplate.withinScopeLock(userId, teamId, () -> {
                            if (!c.isEmpty()) {
                                entityMapper.batchUpdateCommunities(userId, teamId, c);
                            }
                            if (!weakTie.isEmpty()) {
                                entityMapper.updateWeakTieBatch(weakTie);
                            }
                            if (!b.isEmpty()) {
                                entityMapper.updateBridgeBatch(b);
                            }
                            // 全量清除作用域 stale（§5.2⑤）：含孤立实体，保证 stale ratio → 0
                            entityMapper.clearStaleFlag(userId, teamId);
                        })));
    }

    /**
     * bridge 内存计算（V30 §3.2.1：取代 updateBridgeScores 全 scope 聚合 SQL）。
     * 语义对齐原 SQL：邻居中属于不同社区的数量（排除自身社区）；
     * 覆盖 scope 全部实体——非桥/图外孤立实体 reset 为 0（重跑幂等，AC6）。
     */
    private static List<BridgeUpdate> computeBridges(WeightedGraph graph,
                                                     List<EntityMapper.EntityMeta> entities,
                                                     Long2IntMap communities) {
        List<BridgeUpdate> updates = new ArrayList<>(entities.size());
        for (EntityMapper.EntityMeta entity : entities) {
            Set<Integer> distinctForeignCommunities = new HashSet<>();
            if (communities.containsKey(entity.id())) {
                int ownCommunity = communities.get(entity.id());
                for (long neighbor : graph.neighbors(entity.id()).keySet()) {
                    if (communities.containsKey(neighbor) && communities.get(neighbor) != ownCommunity) {
                        distinctForeignCommunities.add(communities.get(neighbor));
                    }
                }
            }
            updates.add(new BridgeUpdate(entity.id(), distinctForeignCommunities.size()));
        }
        updates.sort(java.util.Comparator.comparingLong(BridgeUpdate::entityId));
        return updates;
    }

    /**
     * 将 Leiden 输出的 Long2IntMap 转为 MyBatis 可批量写回的列表（entityId 升序——§3.2.1 防线二）。
     */
    private static List<CommunityAssignment> toAssignments(Long2IntMap communities) {
        List<CommunityAssignment> assignments = new ArrayList<>(communities.size());
        communities.forEach((entityId, communityId) ->
                assignments.add(new CommunityAssignment(entityId, communityId)));
        assignments.sort(java.util.Comparator.comparingLong(CommunityAssignment::entityId));
        return assignments;
    }

    private static int countCommunities(Long2IntMap communities) {
        Set<Integer> unique = new HashSet<>();
        communities.forEach((k, v) -> unique.add(v));
        return unique.size();
    }
}
