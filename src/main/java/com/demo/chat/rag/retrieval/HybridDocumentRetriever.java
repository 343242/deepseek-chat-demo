package com.demo.chat.rag.retrieval;

import com.demo.chat.rag.config.RagRetrievalProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
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
 * 混合检索器 — 向量检索 + BM25 全文检索 + RRF 融合 + 用户/团队隔离
 * <p>
 * 检索流程：
 * <ol>
 *   <li>pgvector HNSW 向量检索（语义相似度，按 userId 或 teamId 过滤）</li>
 *   <li>PostgreSQL tsvector 全文检索（BM25 词频匹配，按 userId 或 teamId 过滤）</li>
 *   <li>RRF (Reciprocal Rank Fusion) 倒数排名融合</li>
 * </ol>
 *
 * <p>隔离规则：
 * <ul>
 *   <li>teamId != null → 按 teamId 过滤（团队知识库检索）</li>
 *   <li>teamId == null → 按 userId 过滤（个人知识库检索）</li>
 * </ul>
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalProperties properties;
    private final QueryNormalizer queryNormalizer;
    private final Long userId;
    @Nullable
    private final Long teamId;
    private final String ftsConfig;
    private final ObjectMapper objectMapper;

    public HybridDocumentRetriever(VectorStore vectorStore,
                                   JdbcTemplate jdbcTemplate,
                                   RagRetrievalProperties properties,
                                   QueryNormalizer queryNormalizer,
                                   Long userId,
                                   @Nullable Long teamId,
                                   ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.queryNormalizer = queryNormalizer;
        this.userId = userId;
        this.teamId = teamId;
        this.ftsConfig = properties.getFtsConfig();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Document> retrieve(Query query) {
        String queryText = queryNormalizer.normalize(query.text());
        int vectorTopK = properties.getVectorTopK();
        int bm25TopK = properties.getBm25TopK();

        if (!properties.isHybridRetrievalEnabled()) {
            return vectorSearch(queryText, vectorTopK);
        }

        List<ScoredDocument> vectorResults = vectorSearchWithScore(queryText, vectorTopK);
        List<ScoredDocument> bm25Results = bm25Search(queryText, bm25TopK);
        List<Document> fused = rrfFusion(vectorResults, bm25Results);

        log.debug("Hybrid retrieval: query='{}', vector={}, bm25={}, fused={}, teamId={}",
                queryText, vectorResults.size(), bm25Results.size(), fused.size(), teamId);

        return fused;
    }

    // === 向量检索 ===

    private List<Document> vectorSearch(String queryText, int topK) {
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        var filter = teamId != null
                ? filterBuilder.eq("teamId", String.valueOf(teamId)).build()
                : filterBuilder.eq("userId", String.valueOf(userId)).build();

        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(queryText)
                        .topK(topK)
                        .similarityThreshold(properties.getSimilarityThreshold())
                        .filterExpression(filter)
                        .build()
        );
    }

    private List<ScoredDocument> vectorSearchWithScore(String queryText, int topK) {
        List<Document> docs = vectorSearch(queryText, topK);
        List<ScoredDocument> results = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            results.add(new ScoredDocument(docs.get(i), i + 1));
        }
        return results;
    }

    // === BM25 全文检索 ===

    private List<ScoredDocument> bm25Search(String queryText, int topK) {
        String sanitized = sanitizeQuery(queryText);
        if (sanitized.isBlank()) {
            return List.of();
        }

        try {
            // 按 teamId 或 userId 隔离
            String isolationField = teamId != null ? "teamId" : "userId";
            String isolationValue = teamId != null ? String.valueOf(teamId) : String.valueOf(userId);

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

                        Document doc = new Document(id, content, metadata);
                        return new ScoredDocument(doc, rowNum + 1);
                    },
                    ftsConfig, sanitized, isolationField, isolationValue, ftsConfig, sanitized, topK
            );
        } catch (Exception e) {
            log.warn("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // === RRF 融合 ===

    private List<Document> rrfFusion(List<ScoredDocument> vectorResults, List<ScoredDocument> bm25Results) {
        int k = properties.getRrfK();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        for (ScoredDocument sd : vectorResults) {
            scores.merge(sd.doc.getId(), 1.0 / (k + sd.rank), Double::sum);
            docMap.putIfAbsent(sd.doc.getId(), sd.doc);
        }

        for (ScoredDocument sd : bm25Results) {
            scores.merge(sd.doc.getId(), 1.0 / (k + sd.rank), Double::sum);
            docMap.putIfAbsent(sd.doc.getId(), sd.doc);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(properties.getVectorTopK())
                .map(e -> docMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    // === 工具方法 ===

    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) return "";
        return query.replaceAll("[&|!()\\[\\]{}:*\\\\]", " ").trim();
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private record ScoredDocument(Document doc, int rank) {}
}
