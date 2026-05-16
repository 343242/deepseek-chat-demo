package com.demo.chat.rag.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * vector_store 表统一数据访问
 * <p>
 * 集中管理所有对 PGvector 托管表 vector_store 的直接 JDBC 查询，
 * 包括 BM25 全文检索、Parent-Child 回查、FastTrack 快速写入/清理。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 只负责 vector_store 表的 CRUD，不包含业务逻辑</li>
 *   <li>封装 — SQL 细节不泄漏到调用方</li>
 *   <li>参数化查询 — 所有用户输入通过 PreparedStatement 参数绑定</li>
 * </ul>
 */
@Repository
public class VectorStoreMapper {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreMapper.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public VectorStoreMapper(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ======================== BM25 全文检索 ========================

    /**
     * 执行 BM25 全文检索（PostgreSQL tsvector）
     *
     * @param ftsConfig      全文检索配置名（如 "jiebacfg"）
     * @param sanitizedQuery 已净化的查询文本
     * @param isolationField metadata 中隔离字段名（"userId" 或 "teamId"）
     * @param isolationValue 隔离字段值
     * @param topK           返回数量上限
     * @return 按 BM25 排名降序排列的文档列表
     */
    public List<Document> bm25Search(String ftsConfig, String sanitizedQuery,
                                     String isolationField, String isolationValue, int topK) {
        String sql = """
                SELECT id, content, metadata
                FROM vector_store
                WHERE content_tsv @@ plainto_tsquery(?::regconfig, ?)
                  AND metadata->> ? = ?
                ORDER BY ts_rank_cd(content_tsv, plainto_tsquery(?::regconfig, ?)) DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    String metadataJson = rs.getString("metadata");

                    Map<String, Object> metadata = parseMetadata(metadataJson);
                    metadata.put("retrievalSource", "bm25");

                    return new Document(id, content, metadata);
                },
                ftsConfig, sanitizedQuery, isolationField, isolationValue,
                ftsConfig, sanitizedQuery, topK
        );
    }

    // ======================== Parent-Child 回查 ========================

    /**
     * 批量回查父文档（Parent-Child 切分策略）
     *
     * @param parentIds 需要回查的父文档 ID 集合
     * @return parentId → Document 的映射
     */
    public Map<String, Document> batchFetchParents(Set<String> parentIds) {
        if (parentIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Document> result = new HashMap<>();
        String placeholders = String.join(",", Collections.nCopies(parentIds.size(), "?"));
        String sql = """
                SELECT id, content, metadata
                FROM vector_store
                WHERE metadata->>'parentId' IN (%s)
                  AND metadata->>'isParent' = 'true'
                """.formatted(placeholders);

        List<Document> docs = jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    String metadataJson = rs.getString("metadata");
                    return new Document(id, content, parseMetadata(metadataJson));
                },
                parentIds.toArray()
        );

        for (Document doc : docs) {
            Object pid = doc.getMetadata().get("parentId");
            if (pid != null) {
                result.put(pid.toString(), doc);
            }
        }

        log.debug("Batch fetched {} parent docs for {} parentIds", docs.size(), parentIds.size());
        return result;
    }

    // ======================== FastTrack 快速写入 ========================

    /**
     * 写入 FastTrack BM25 原文行
     * <p>
     * embedding 设为 NULL，BM25 检索通过 content_tsv 命中。
     */
    public void insertFastTrackRow(Long documentId, String content, Long userId, Long teamId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", String.valueOf(documentId));
        metadata.put("userId", String.valueOf(userId));
        metadata.put("fastTrack", true);
        if (teamId != null) {
            metadata.put("teamId", String.valueOf(teamId));
        }

        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadata);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to serialize BM25 metadata for documentId={}", documentId, e);
            throw new RuntimeException(e);
        }

        jdbcTemplate.update("""
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (gen_random_uuid(), ?, ?::json, NULL)
                """, content, metadataJson);

        log.debug("BM25 fast-track row written for documentId={}", documentId);
    }

    /**
     * 删除指定文档的 FastTrack BM25 原文行
     */
    public void deleteFastTrackRows(Long documentId) {
        jdbcTemplate.update("""
                DELETE FROM vector_store
                WHERE metadata->>'documentId' = ?
                  AND metadata->>'fastTrack' = 'true'
                """, String.valueOf(documentId));
        log.debug("BM25 fast-track rows deleted for documentId={}", documentId);
    }

    // ======================== 文档间 Cosine 距离 ========================

    private static final int MAX_PAIRWISE_DOCS = 50;

    /**
     * 批量计算文档间 cosine 距离矩阵。
     * <p>
     * 利用 pgvector 的 {@code embedding <=> embedding} 运算符在数据库层计算，
     * 避免额外调用 Embedding API。
     *
     * @param docIds 文档 ID 列表（通常 5-10 条）
     * @return 距离矩阵 key = "idA|idB", value = cosine distance (0=相同, 2=相反)
     */
    public Map<String, Double> pairwiseCosineDistance(List<String> docIds) {
        if (docIds == null || docIds.size() < 2) {
            return Map.of();
        }

        // 防御性截断：O(n²) SQL，超过上限时截断并告警
        if (docIds.size() > MAX_PAIRWISE_DOCS) {
            log.warn("pairwiseCosineDistance: truncating {} docs to {} (O(n²) SQL defense)",
                    docIds.size(), MAX_PAIRWISE_DOCS);
            docIds = docIds.subList(0, MAX_PAIRWISE_DOCS);
        }

        String placeholders = String.join(",", Collections.nCopies(docIds.size(), "?"));
        String sql = """
                SELECT a.id AS id_a, b.id AS id_b, a.embedding <=> b.embedding AS distance
                FROM vector_store a, vector_store b
                WHERE a.id IN (%s) AND b.id IN (%s) AND a.id < b.id
                """.formatted(placeholders, placeholders);

        // 参数：两组 id
        Object[] params = new Object[docIds.size() * 2];
        for (int i = 0; i < docIds.size(); i++) {
            params[i] = docIds.get(i);
            params[docIds.size() + i] = docIds.get(i);
        }

        Map<String, Double> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String idA = rs.getString("id_a");
            String idB = rs.getString("id_b");
            double distance = rs.getDouble("distance");
            result.put(idA + "|" + idB, distance);
            result.put(idB + "|" + idA, distance);
        }, params);

        log.debug("Computed {} pairwise distances for {} docs", result.size() / 2, docIds.size());
        return result;
    }

    // ======================== 工具方法 ========================

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse vector_store metadata JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
