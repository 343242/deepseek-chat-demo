package com.smart.rag.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * vector_store 表统一数据访问（MyBatis 接口 + XML）。
 * <p>
 * 集中管理所有对 PGvector 托管表 vector_store 的查询：BM25 全文检索、Parent-Child 回查、
 * FastTrack 快速写入/清理、MMR 文档间 cosine 距离、ts_headline 高亮、知识库统计。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 只负责 vector_store 表的 CRUD，不包含业务逻辑</li>
 *   <li>封装 — SQL 细节不泄漏到调用方</li>
 *   <li>参数化查询 — 所有用户输入通过 {@code #{}} 绑定；id 列上的 IN 一律 {@code #{id}::uuid}
 *       （vector_store.id 为 UUID 列，String 不显式转型会触发 PostgreSQL 42883
 *       "operator does not exist: uuid = text"）</li>
 * </ul>
 * <p>
 * 行映射：SQL 返回轻量 row record，由 {@code default} 方法组装成 Document / 对称距离矩阵等业务结构。
 * 公共方法签名与原 {@code @Repository} 类保持一致 → 所有调用方零改动。
 */
@Mapper
public interface VectorStoreMapper {

    Logger LOG = LoggerFactory.getLogger(VectorStoreMapper.class);

    /** MMR pairwise 距离 SQL 是 O(n²)，超过此阈值截断（防御性） */
    int MAX_PAIRWISE_DOCS = 50;

    // ======================== row records（仅供 XML 映射）========================

    /** vector_store 行：id + content + metadata(json) */
    record VectorStoreRow(String id, String content, Map<String, Object> metadata) {}

    /** 文档间 cosine 距离行 */
    record PairwiseDistanceRow(String idA, String idB, double distance) {}

    /** ts_headline 高亮行 */
    record HighlightRow(String id, String highlight) {}

    // ======================== BM25 全文检索 ========================

    /**
     * 执行 BM25 全文检索（PostgreSQL tsvector）。
     *
     * @param ftsConfig      全文检索配置名（如 "jiebacfg"）
     * @param sanitizedQuery 已净化的查询文本
     * @param isolationField metadata 中隔离字段名（"userId" 或 "teamId"）
     * @param isolationValue 隔离字段值
     * @param topK           返回数量上限
     * @return 按 BM25 排名降序排列的文档列表（每条 metadata 带 {@code retrievalSource=bm25}）
     */
    default List<Document> bm25Search(String ftsConfig, String sanitizedQuery,
                                      String isolationField, String isolationValue, int topK) {
        List<VectorStoreRow> rows = selectBm25Rows(ftsConfig, sanitizedQuery, isolationField, isolationValue, topK);
        List<Document> docs = new ArrayList<>(rows.size());
        for (VectorStoreRow row : rows) {
            Map<String, Object> metadata = row.metadata() != null ? row.metadata() : new HashMap<>();
            metadata.put("retrievalSource", "bm25");
            docs.add(new Document(row.id(), row.content(), metadata));
        }
        return docs;
    }

    List<VectorStoreRow> selectBm25Rows(@Param("ftsConfig") String ftsConfig,
                                        @Param("sanitizedQuery") String sanitizedQuery,
                                        @Param("isolationField") String isolationField,
                                        @Param("isolationValue") String isolationValue,
                                        @Param("topK") int topK);

    // ======================== Parent-Child 回查 ========================

    /**
     * 批量回查父文档（Parent-Child 切分策略）。
     *
     * @param parentIds 需要回查的父文档 ID 集合
     * @return parentId → Document 的映射
     */
    default Map<String, Document> batchFetchParents(Set<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of();
        }
        List<VectorStoreRow> rows = selectParentRows(parentIds);
        Map<String, Document> result = new HashMap<>();
        for (VectorStoreRow row : rows) {
            Map<String, Object> metadata = row.metadata() != null ? row.metadata() : new HashMap<>();
            Object pid = metadata.get("parentId");
            if (pid != null) {
                result.put(pid.toString(), new Document(row.id(), row.content(), metadata));
            }
        }
        LOG.debug("Batch fetched {} parent docs for {} parentIds", rows.size(), parentIds.size());
        return result;
    }

    List<VectorStoreRow> selectParentRows(@Param("parentIds") Set<String> parentIds);

    // ======================== FastTrack 快速写入 ========================

    /**
     * 写入 FastTrack BM25 原文行。
     * <p>
     * embedding 设为 NULL，BM25 检索通过 content_tsv 命中。
     */
    default void insertFastTrackRow(Long documentId, String content, Long userId, Long teamId, String fileName) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", String.valueOf(documentId));
        metadata.put("userId", String.valueOf(userId));
        metadata.put("fastTrack", true);
        metadata.put("fileName", (fileName != null && !fileName.isBlank()) ? fileName : String.valueOf(documentId));
        if (teamId != null) {
            metadata.put("teamId", String.valueOf(teamId));
        }
        insertFastTrackRowInternal(content, metadata);
        LOG.debug("BM25 fast-track row written for documentId={}", documentId);
    }

    void insertFastTrackRowInternal(@Param("content") String content,
                                    @Param("metadata") Map<String, Object> metadata);

    /**
     * 删除指定文档的 FastTrack BM25 原文行
     */
    default void deleteFastTrackRows(Long documentId) {
        deleteFastTrackRowsInternal(String.valueOf(documentId));
        LOG.debug("BM25 fast-track rows deleted for documentId={}", documentId);
    }

    void deleteFastTrackRowsInternal(@Param("documentId") String documentId);

    // ======================== 文档间 Cosine 距离 ========================

    /**
     * 批量计算文档间 cosine 距离矩阵（截断阈值 = {@link #MAX_PAIRWISE_DOCS}）。
     * <p>
     * 向后兼容重载：等价于 {@code pairwiseCosineDistance(docIds, MAX_PAIRWISE_DOCS)}。
     *
     * @param docIds 文档 ID 列表
     * @return 对称距离矩阵 key = "idA|idB", value = cosine distance (0=相同, 2=相反)
     */
    default Map<String, Double> pairwiseCosineDistance(List<String> docIds) {
        return pairwiseCosineDistance(docIds, MAX_PAIRWISE_DOCS);
    }

    /**
     * 批量计算文档间 cosine 距离矩阵，可指定截断阈值。
     * <p>
     * 利用 pgvector 的 {@code embedding <=> embedding} 运算符在数据库层计算，避免额外调用 Embedding API。
     *
     * @param docIds  文档 ID 列表
     * @param maxDocs 截断阈值；实际下限 = {@code max(MAX_PAIRWISE_DOCS, maxDocs)}。
     *                fusionTopK 联动场景传 fusionTopK（如 60），召回 60 时避免 50 截断导致 MMR distance key miss。
     * @return 对称距离矩阵 key = "idA|idB", value = cosine distance (0=相同, 2=相反)
     */
    default Map<String, Double> pairwiseCosineDistance(List<String> docIds, int maxDocs) {
        if (docIds == null || docIds.size() < 2) {
            return Map.of();
        }

        // 防御性截断：O(n²) SQL，超过上限时截断并告警；下限保留 MAX_PAIRWISE_DOCS（fusionTopK 联动取 max）
        int limit = Math.max(MAX_PAIRWISE_DOCS, maxDocs);
        if (docIds.size() > limit) {
            LOG.warn("pairwiseCosineDistance: truncating {} docs to {} (O(n²) SQL defense)",
                    docIds.size(), limit);
            docIds = docIds.subList(0, limit);
        }

        List<PairwiseDistanceRow> rows = selectPairwiseDistance(docIds);
        Map<String, Double> result = new HashMap<>(rows.size() * 2);
        for (PairwiseDistanceRow row : rows) {
            result.put(row.idA() + "|" + row.idB(), row.distance());
            result.put(row.idB() + "|" + row.idA(), row.distance());
        }

        LOG.debug("Computed {} pairwise distances for {} docs", rows.size(), docIds.size());
        return result;
    }

    List<PairwiseDistanceRow> selectPairwiseDistance(@Param("docIds") List<String> docIds);

    // ======================== 文档详情（ts_headline 高亮）========================

    /**
     * 按文档 ID 获取内容片段，使用 ts_headline 高亮查询关键词。
     *
     * @param docIds    文档 ID 列表
     * @param queryText 查询文本（用于 ts_headline 高亮）
     * @param ftsConfig 全文检索配置名（如 "jiebacfg"）
     * @return 文档 ID -> 高亮内容片段的映射（保持 DB 返回顺序）
     */
    default Map<String, String> fetchDocHighlights(List<String> docIds, String queryText, String ftsConfig) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        List<HighlightRow> rows = selectHighlightRows(docIds, queryText, ftsConfig);
        Map<String, String> result = new LinkedHashMap<>();
        for (HighlightRow row : rows) {
            result.put(row.id(), row.highlight());
        }
        LOG.debug("Fetched highlights for {} docIds, got {} results", docIds.size(), result.size());
        return result;
    }

    List<HighlightRow> selectHighlightRows(@Param("docIds") List<String> docIds,
                                           @Param("queryText") String queryText,
                                           @Param("ftsConfig") String ftsConfig);

    // ======================== 知识库统计 ========================

    /**
     * 统计指定用户/团队的向量文档数量。
     *
     * @param isolationField 隔离字段名（"userId" 或 "teamId"）
     * @param isolationValue 隔离字段值
     * @return 文档总数
     */
    default int countDocs(String isolationField, String isolationValue) {
        return countDocsInternal(isolationField, isolationValue);
    }

    int countDocsInternal(@Param("isolationField") String isolationField,
                          @Param("isolationValue") String isolationValue);
    // ======================== Entity Extraction Support ========================

    /**
     * 查询指定文档的所有 chunk（id + content），按 metadata->>'documentId' 过滤。
     *
     * @param documentId 文档 ID
     * @return chunk 行列表
     */
    List<VectorStoreRow> selectChunksByDocumentId(@Param("documentId") String documentId);
}
