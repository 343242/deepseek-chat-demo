package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.AdjacencyListGraph;
import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper.CooccurrenceRow;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 从 rag_entity_cooccurrence 加载共现图（§5.2 ④）。
 * <p>
 * SRP：仅负责"DB → {@link WeightedGraph}"转换，不参与检测或写回。
 * DIP：返回 {@link WeightedGraph} 接口（具体实现选 {@link AdjacencyListGraph}——
 * 共现图天然稀疏，邻接表存储高效；packed long[] 减 GC 压力）。
 * <p>
 * 共现图是无向加权简单图：{@code selectByScope} 返回每条无向边一次（entity_a &lt; entity_b），
 * {@code addEdge} 自动写双向。
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class CooccurrenceGraphLoader {

    private final EntityCooccurrenceMapper cooccurrenceMapper;

    public CooccurrenceGraphLoader(EntityCooccurrenceMapper cooccurrenceMapper) {
        this.cooccurrenceMapper = cooccurrenceMapper;
    }

    /**
     * 加载作用域内全部共现边，构造只读 WeightedGraph。
     * <p>
     * 孤立实体（degree=0、无共现边）不出现在图中——它们无社区归属，由
     * {@code CommunityDetectionJob.clearStaleFlag} 标记为非 stale。
     *
     * @param userId 用户作用域
     * @param teamId 团队作用域（可为 null）
     * @return 加权无向图（可能 nodeCount=0）
     */
    public WeightedGraph load(Long userId, @Nullable Long teamId) {
        WeightedGraph graph = new AdjacencyListGraph();
        for (CooccurrenceRow row : cooccurrenceMapper.selectByScope(userId, teamId)) {
            graph.addEdge(row.entityA(), row.entityB(), row.coCount());
        }
        return graph;
    }
}
