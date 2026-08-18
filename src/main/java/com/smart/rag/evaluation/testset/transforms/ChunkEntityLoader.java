package com.smart.rag.evaluation.testset.transforms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * chunk 实体装载器：KG 实体的唯一来源（{@code rag_chunk_entity} JOIN {@code rag_entity}）。
 * <p>
 * 实体由实体中心索引层在 ETL 时抽取入库（实体层无条件装配）；本类只读不抽，
 * 语料需在实体层上线后导入才有实体数据。
 * 无实体行的 chunk 不参与实体边（多跳按数据自然降级），无兜底路径。
 * </p>
 */
@Component
public class ChunkEntityLoader {

    private final JdbcTemplate jdbc;

    public ChunkEntityLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 装载给定 chunk 的实体规范名（name_norm），按 chunkId 分组。
     * 实体侧带 user_id 过滤（实体层安全隔离双轨，V21）。
     */
    public Map<String, Set<String>> loadEntities(List<String> chunkIds, long userId) {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        var uuids = chunkIds.stream().map(UUID::fromString).toArray(UUID[]::new);
        return jdbc.query(
                """
                        SELECT ce.chunk_id::text AS chunk_id, e.name_norm
                        FROM rag_chunk_entity ce
                        JOIN rag_entity e ON ce.entity_id = e.id
                        WHERE ce.chunk_id = ANY(?) AND e.user_id = ?
                        """,
                ps -> {
                    try {
                        ps.setArray(1, ps.getConnection().createArrayOf("uuid", uuids));
                    } catch (SQLException e) {
                        throw new IllegalStateException("构造 uuid 数组参数失败", e);
                    }
                    ps.setLong(2, userId);
                },
                rs -> {
                    Map<String, Set<String>> result = new HashMap<>();
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString("chunk_id"), k -> new HashSet<>())
                                .add(rs.getString("name_norm"));
                    }
                    return result;
                });
    }
}
