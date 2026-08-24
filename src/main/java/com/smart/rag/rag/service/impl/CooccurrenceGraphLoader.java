package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.algorithm.graph.AdjacencyListGraph;
import com.smart.rag.infrastructure.algorithm.graph.WeightedGraph;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper.CooccurrenceRow;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EntityMapper.EntityMeta;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 rag_entity_cooccurrence + rag_entity 加载 scope 图快照（§5.2④ / V30 §6 阶段二）。
 * <p>
 * SRP：仅负责"DB → 图快照"转换，不参与检测或写回。
 * V30 扩展：除共现边外一并返回 scope 实体清单（含 degree）——
 * <ul>
 *   <li>degree 供 {@link WeakTieScoreCalculator} 的 hub 预算（degree &lt; 100）判定；</li>
 *   <li>实体清单覆盖图外孤立实体（bridge reset-0 语义需要，V30 §6 阶段二）。</li>
 * </ul>
 * 单次读快照供三个分值（weak_tie / community / bridge）共享——
 * 消除原实现"weak_tie 与 Leiden 各读各的快照"的跨快照混代（V30 §3.2.1）。
 */
@Component
public class CooccurrenceGraphLoader {

    private final EntityCooccurrenceMapper cooccurrenceMapper;
    private final EntityMapper entityMapper;

    public CooccurrenceGraphLoader(EntityCooccurrenceMapper cooccurrenceMapper,
                                   EntityMapper entityMapper) {
        this.cooccurrenceMapper = cooccurrenceMapper;
        this.entityMapper = entityMapper;
    }

    /**
     * scope 图快照：共现图 + 实体清单（含图外孤立实体）。
     */
    public record ScopeGraphSnapshot(WeightedGraph graph, List<EntityMeta> entities) {
        /** entityId → rag_entity.degree（hub 预算判定）。 */
        public Map<Long, Integer> degrees() {
            Map<Long, Integer> degrees = new HashMap<>(entities.size());
            for (EntityMeta meta : entities) {
                degrees.put(meta.id(), meta.degree());
            }
            return degrees;
        }
    }

    /**
     * 加载作用域内全部共现边 + 实体清单，构造只读快照。
     *
     * @param userId 用户作用域
     * @param teamId 团队作用域（可为 null）
     */
    public ScopeGraphSnapshot loadScopeGraph(Long userId, @Nullable Long teamId) {
        WeightedGraph graph = new AdjacencyListGraph();
        for (CooccurrenceRow row : cooccurrenceMapper.selectByScope(userId, teamId)) {
            graph.addEdge(row.entityA(), row.entityB(), row.coCount());
        }
        List<EntityMeta> entities = entityMapper.selectScopeEntityMetas(userId, teamId);
        return new ScopeGraphSnapshot(graph, entities);
    }
}
