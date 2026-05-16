package com.demo.chat.rag.retrieval;

import com.demo.chat.rag.config.RagRetrievalProperties;
import com.demo.chat.rag.mapper.VectorStoreMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

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
    private final VectorStoreMapper vectorStoreMapper;
    private final RagRetrievalProperties properties;
    private final QueryNormalizer queryNormalizer;
    private final Long userId;
    @Nullable
    private final Long teamId;
    private final String ftsConfig;

    public HybridDocumentRetriever(VectorStore vectorStore,
                                   VectorStoreMapper vectorStoreMapper,
                                   RagRetrievalProperties properties,
                                   QueryNormalizer queryNormalizer,
                                   Long userId,
                                   @Nullable Long teamId) {
        this.vectorStore = vectorStore;
        this.vectorStoreMapper = vectorStoreMapper;
        this.properties = properties;
        this.queryNormalizer = queryNormalizer;
        this.userId = userId;
        this.teamId = teamId;
        this.ftsConfig = properties.ftsConfig();
    }

    @Override
    public List<Document> retrieve(Query query) {
        String queryText = queryNormalizer.normalize(query.text());
        int vectorTopK = properties.vectorTopK();
        int bm25TopK = properties.bm25TopK();

        if (!properties.hybridRetrievalEnabled()) {
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
                        .similarityThreshold(properties.similarityThreshold())
                        .filterExpression(filter)
                        .build()
        );
    }

    private List<ScoredDocument> vectorSearchWithScore(String queryText, int topK) {
        List<Document> docs = vectorSearch(queryText, topK);
        List<ScoredDocument> results = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            // PgVectorStore DocumentRowMapper 已将 distance 写入 metadata，score = 1 - distance
            double vectorScore = doc.getScore() != null ? doc.getScore() : 0.5;
            results.add(new ScoredDocument(doc, i + 1, vectorScore));
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
            String isolationField = teamId != null ? "teamId" : "userId";
            String isolationValue = teamId != null ? String.valueOf(teamId) : String.valueOf(userId);

            List<Document> docs = vectorStoreMapper.bm25Search(
                    ftsConfig, sanitized, isolationField, isolationValue, topK);

            List<ScoredDocument> results = new ArrayList<>(docs.size());
            for (int i = 0; i < docs.size(); i++) {
                results.add(new ScoredDocument(docs.get(i), i + 1, 0.0));
            }
            return results;
        } catch (Exception e) {
            log.warn("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // === RRF 融合 ===

    private List<Document> rrfFusion(List<ScoredDocument> vectorResults, List<ScoredDocument> bm25Results) {
        int k = properties.rrfK();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        // 向量检索：加权 RRF — score * 1/(k + rank)，利用 cosine 相似度提升高质量命中的权重
        for (ScoredDocument sd : vectorResults) {
            double weighted = sd.score * (1.0 / (k + sd.rank));
            scores.merge(sd.doc.getId(), weighted, Double::sum);
            docMap.putIfAbsent(sd.doc.getId(), sd.doc);
        }

        // BM25：纯排名 RRF（BM25 无标准化分数可用）
        for (ScoredDocument sd : bm25Results) {
            scores.merge(sd.doc.getId(), 1.0 / (k + sd.rank), Double::sum);
            docMap.putIfAbsent(sd.doc.getId(), sd.doc);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(properties.vectorTopK())
                .map(e -> docMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    // === 工具方法 ===

    /**
     * 净化查询文本：只去掉 PostgreSQL tsquery 运算符，保留完整中文文本交给 pg_jieba 分词。
     * <p>
     * pg_jieba 的 plainto_tsquery('jiebacfg', text) 会自动调用 jieba 分词，
     * 因此只需去除 tsquery 特殊运算符即可。
     * </p>
     */
    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) return "";
        // 去掉 tsquery 运算符：& | ! ( ) : * \ 和 ASCII 引号
        // 同时 normalize Unicode 引号 → 空格（plainto_tsquery 会自行处理中文分词）
        return query
                .replace('\u201C', ' ').replace('\u201D', ' ')  // " " (curly double)
                .replace('\u2018', ' ').replace('\u2019', ' ')  // ' ' (curly single)
                .replace('\u00AB', ' ').replace('\u00BB', ' ')  // « » (guillemets)
                .replaceAll("[&|!()\\[\\]{}:*\\\\\"']", " ")
                .trim();
    }

    private record ScoredDocument(Document doc, int rank, double score) {}
}
