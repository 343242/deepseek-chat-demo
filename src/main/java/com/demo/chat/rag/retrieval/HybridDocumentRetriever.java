package com.demo.chat.rag.retrieval;

import com.demo.chat.rag.config.RagRetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * 混合检索器 — 向量检索 + BM25 全文检索 + RRF 融合 + 用户隔离
 * <p>
 * 检索流程：
 * <ol>
 *   <li>pgvector HNSW 向量检索（语义相似度，按 userId 过滤）</li>
 *   <li>PostgreSQL tsvector 全文检索（BM25 词频匹配，按 userId 过滤）</li>
 *   <li>RRF (Reciprocal Rank Fusion) 倒数排名融合</li>
 * </ol>
 *
 * <p>RRF 公式：score(d) = Σ 1/(k + rank_i)，k 默认 60。</p>
 *
 * <p>优势：向量检索捕捉语义相关性，BM25 捕捉精确关键词匹配，
 * RRF 融合两者优势，比单一检索方式有更好的召回率。</p>
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);


    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalProperties properties;
    private final Long userId;
    private final String ftsConfig;

    public HybridDocumentRetriever(VectorStore vectorStore,
                                   JdbcTemplate jdbcTemplate,
                                   RagRetrievalProperties properties,
                                   Long userId) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.userId = userId;
        this.ftsConfig = properties.getFtsConfig();
    }

    @Override
    public List<Document> retrieve(Query query) {
        String queryText = query.text();
        int vectorTopK = properties.getVectorTopK();
        int bm25TopK = properties.getBm25TopK();

        if (!properties.isHybridRetrievalEnabled()) {
            // 纯向量检索 fallback
            return vectorSearch(queryText, vectorTopK);
        }

        // 并行两路检索
        List<ScoredDocument> vectorResults = vectorSearchWithScore(queryText, vectorTopK);
        List<ScoredDocument> bm25Results = bm25Search(queryText, bm25TopK);

        // RRF 融合
        List<Document> fused = rrfFusion(vectorResults, bm25Results);

        log.debug("Hybrid retrieval: query='{}', vector={}, bm25={}, fused={}",
                queryText, vectorResults.size(), bm25Results.size(), fused.size());

        return fused;
    }

    // === 向量检索 ===

    private List<Document> vectorSearch(String queryText, int topK) {
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        var userIdFilter = filterBuilder.eq("userId", String.valueOf(userId)).build();

        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(queryText)
                        .topK(topK)
                        .similarityThreshold(properties.getSimilarityThreshold())
                        .filterExpression(userIdFilter)
                        .build()
        );
    }

    /**
     * 向量检索（带排名位置）
     */
    private List<ScoredDocument> vectorSearchWithScore(String queryText, int topK) {
        List<Document> docs = vectorSearch(queryText, topK);
        List<ScoredDocument> results = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            results.add(new ScoredDocument(docs.get(i), i + 1));
        }
        return results;
    }

    // === BM25 全文检索 ===

    /**
     * PostgreSQL tsvector 全文检索
     * <p>
     * 使用 plainto_tsquery 进行简单全文搜索，
     * 结果按 ts_rank_cd 排序（BM25 变体）。
     * </p>
     */
    private List<ScoredDocument> bm25Search(String queryText, int topK) {
        String sanitized = sanitizeQuery(queryText);
        if (sanitized.isBlank()) {
            log.debug("BM25 query empty after sanitization, skipping");
            return List.of();
        }

        try {
            // 检查 content_tsv 列是否存在，同时按 userId 隔离
            String userIdStr = String.valueOf(userId);
            String sql = """
                SELECT id, content, metadata
                FROM vector_store
                WHERE content_tsv @@ plainto_tsquery(?, ?)
                  AND metadata->>'userId' = ?
                ORDER BY ts_rank_cd(content_tsv, plainto_tsquery(?, ?)) DESC
                LIMIT ?
                """;

            List<ScoredDocument> results = jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");

                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        metadata.put("retrievalSource", "bm25");

                        Document doc = new Document(id, content, metadata);
                        return new ScoredDocument(doc, rowNum + 1);
                    },
                    ftsConfig, sanitized, userIdStr, ftsConfig, sanitized, topK
            );

            return results;
        } catch (Exception e) {
            log.warn("BM25 search failed (content_tsv column may not exist): {}", e.getMessage());
            return List.of();
        }
    }

    // === RRF 融合 ===

    /**
     * Reciprocal Rank Fusion (RRF)
     * <p>
     * score(d) = Σ_i 1/(k + rank_i)
     * </p>
     */
    private List<Document> rrfFusion(List<ScoredDocument> vectorResults,
                                     List<ScoredDocument> bm25Results) {
        int k = properties.getRrfK();

        // 文档 ID → 累积 RRF 分数
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Document> docMap = new LinkedHashMap<>();

        // 向量检索贡献
        for (ScoredDocument sd : vectorResults) {
            String docId = sd.document().getId();
            double score = 1.0 / (k + sd.rank());
            rrfScores.merge(docId, score, Double::sum);
            docMap.putIfAbsent(docId, sd.document());
        }

        // BM25 检索贡献
        for (ScoredDocument sd : bm25Results) {
            String docId = sd.document().getId();
            double score = 1.0 / (k + sd.rank());
            rrfScores.merge(docId, score, Double::sum);
            docMap.putIfAbsent(docId, sd.document());
        }

        // 按 RRF 分数降序排列
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    Document doc = docMap.get(entry.getKey());
                    // 注入 RRF 分数到 metadata
                    doc.getMetadata().put("rrfScore", entry.getValue());
                    return doc;
                })
                .toList();
    }

    // === 工具方法 ===

    /**
     * 查询文本清洗，防止 SQL 注入
     */
    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) return "";
        // 去除特殊字符，只保留安全字符
        return query.trim()
                .replaceAll("[^\\p{IsHan}a-zA-Z0-9\\s，。！？、；：\"''（）\\-]", " ")
                .trim();
    }

    /**
     * 解析 metadata JSON 字符串（简单处理，避免引入额外依赖）
     */
    private Map<String, Object> parseMetadata(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isBlank() || "null".equals(json)) {
            return result;
        }
        // PostgreSQL JSON 格式简单解析
        try {
            if (json.startsWith("{") && json.endsWith("}")) {
                String content = json.substring(1, json.length() - 1);
                String[] pairs = content.split(",");
                for (String pair : pairs) {
                    int idx = pair.indexOf(':');
                    if (idx > 0) {
                        String key = pair.substring(0, idx).trim().replace("\"", "");
                        String value = pair.substring(idx + 1).trim();
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            result.put(key, value.substring(1, value.length() - 1));
                        } else {
                            result.put(key, value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse metadata JSON: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 带排名位置的文档记录
     */
    private record ScoredDocument(Document document, int rank) {}
}
