package com.smart.rag.agent.service;

import com.smart.rag.exception.BusinessException;
import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.retrieval.QueryNormalizer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 混合检索服务 -- 提取 HybridDocumentRetriever 核心逻辑为独立 Service
 * <p>
 * 供 HybridDocumentRetriever（Pipeline 模式）和 HybridSearchTool（Agent 模式）共用。
 * userId/teamId 从构造参数改为方法参数，支持按请求动态传入。
 * <p>
 * 检索流程：
 * <ol>
 *   <li>pgvector HNSW 向量检索（语义相似度，按 userId 或 teamId 过滤）</li>
 *   <li>PostgreSQL tsvector 全文检索（BM25 词频匹配，按 userId 或 teamId 过滤）</li>
 *   <li>RRF (Reciprocal Rank Fusion) 倒数排名融合</li>
 * </ol>
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private static final long SEARCH_TIMEOUT_SECONDS = 5;

    private final VectorStore vectorStore;
    private final VectorStoreMapper vectorStoreMapper;
    private final RagRetrievalProperties properties;
    private final QueryNormalizer queryNormalizer;
    private final Executor searchExecutor;

    public HybridSearchService(VectorStore vectorStore,
                               VectorStoreMapper vectorStoreMapper,
                               RagRetrievalProperties properties,
                               QueryNormalizer queryNormalizer,
                               @Qualifier("ragSearchExecutor") Executor searchExecutor) {
        this.vectorStore = vectorStore;
        this.vectorStoreMapper = vectorStoreMapper;
        this.properties = properties;
        this.queryNormalizer = queryNormalizer;
        this.searchExecutor = searchExecutor;
    }

    /**
     * 混合检索入口
     *
     * @param queryText 查询文本（原始文本，内部会归一化）
     * @param userId    用户 ID（隔离条件）
     * @param teamId    团队 ID（可空，非空时优先按 teamId 隔离）
     * @return 融合排序后的文档列表
     */
    public List<Document> hybridSearch(String queryText, long userId, @Nullable Long teamId) {
        String normalized = queryNormalizer.normalize(queryText);
        int vectorTopK = properties.vectorTopK();
        int bm25TopK = properties.bm25TopK();

        if (!properties.hybridRetrievalEnabled()) {
            return vectorSearch(normalized, vectorTopK, userId, teamId);
        }

        CompletableFuture<List<ScoredDocument>> vectorFuture =
                CompletableFuture.supplyAsync(() -> vectorSearchWithScore(normalized, vectorTopK, userId, teamId), searchExecutor);
        CompletableFuture<List<ScoredDocument>> bm25Future =
                CompletableFuture.supplyAsync(() -> bm25Search(normalized, bm25TopK, userId, teamId), searchExecutor);

        // Track per-branch failure to detect total failure after thenCombine
        var vectorFailed = new AtomicBoolean(false);
        var bm25Failed = new AtomicBoolean(false);

        try {
            List<Document> fused = vectorFuture
                    .exceptionally(ex -> {
                        vectorFailed.set(true);
                        log.warn("Vector search degraded: {}", ex.getMessage());
                        return Collections.emptyList();
                    })
                    .thenCombine(
                            bm25Future.exceptionally(ex -> {
                                bm25Failed.set(true);
                                log.warn("BM25 search degraded: {}", ex.getMessage());
                                return Collections.emptyList();
                            }),
                            (vec, bm25) -> rrfFusion(vec, bm25))
                    .orTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();

            // Both branches recovered via exceptionally -> signal total failure to caller
            if (vectorFailed.get() && bm25Failed.get()) {
                log.error("Both vector and BM25 search failed for queryLen={}", normalized.length());
                throw new BusinessException("向量检索和 BM25 检索均不可用");
            }

            if (vectorFailed.get() || bm25Failed.get()) {
                log.warn("Partial search degradation: vector={}, bm25={}",
                        vectorFailed.get() ? "FAILED" : "OK", bm25Failed.get() ? "FAILED" : "OK");
            }

            log.debug("Hybrid search: queryLen={}, vectorFailed={}, bm25Failed={}, fused={}, teamId={}",
                    normalized.length(), vectorFailed.get(), bm25Failed.get(), fused.size(), teamId);

            return fused;
        } catch (CompletionException e) {
            // TimeoutException or unexpected error from the combined pipeline
            throw e;
        }
    }

    // === 向量检索 ===

    private List<Document> vectorSearch(String queryText, int topK,
                                        long userId, @Nullable Long teamId) {
        try {
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
        } catch (Exception e) {
            log.warn("Vector search failed, falling back to BM25-only: {}", e.getMessage());
            log.debug("Vector search exception detail", e);
            return List.of();
        }
    }

    private List<ScoredDocument> vectorSearchWithScore(String queryText, int topK,
                                                       long userId, @Nullable Long teamId) {
        List<Document> docs = vectorSearch(queryText, topK, userId, teamId);
        List<ScoredDocument> results = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            double vectorScore = doc.getScore() != null ? doc.getScore() : 0.5;
            results.add(new ScoredDocument(doc, i + 1, vectorScore));
        }
        return results;
    }

    // === BM25 全文检索 ===

    private List<ScoredDocument> bm25Search(String queryText, int topK,
                                            long userId, @Nullable Long teamId) {
        String sanitized = queryNormalizer.sanitizeForTsQuery(queryText);
        if (sanitized.isBlank()) {
            return List.of();
        }

        try {
            String isolationField = teamId != null ? "teamId" : "userId";
            String isolationValue = teamId != null ? String.valueOf(teamId) : String.valueOf(userId);
            String ftsConfig = properties.ftsConfig();

            List<Document> docs = vectorStoreMapper.bm25Search(
                    ftsConfig, sanitized, isolationField, isolationValue, topK);

            List<ScoredDocument> results = new ArrayList<>(docs.size());
            for (int i = 0; i < docs.size(); i++) {
                results.add(new ScoredDocument(docs.get(i), i + 1, 0.0));
            }
            return results;
        } catch (Exception e) {
            log.error("BM25 search failed, falling back to vector-only: {}", e.getMessage());
            log.debug("BM25 search exception detail", e);
            return List.of();
        }
    }

    // === RRF 融合 ===

    private List<Document> rrfFusion(List<ScoredDocument> vectorResults, List<ScoredDocument> bm25Results) {
        int k = properties.rrfK();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        // 向量检索：加权 RRF -- score * 1/(k + rank)
        for (ScoredDocument sd : vectorResults) {
            String docId = sd.doc.getId();
            if (docId == null) continue;
            double weighted = sd.score * (1.0 / (k + sd.rank));
            scores.merge(docId, weighted, Double::sum);
            docMap.putIfAbsent(docId, sd.doc);
        }

        // BM25：纯排名 RRF
        for (ScoredDocument sd : bm25Results) {
            String docId = sd.doc.getId();
            if (docId == null) continue;
            scores.merge(docId, 1.0 / (k + sd.rank), Double::sum);
            docMap.putIfAbsent(docId, sd.doc);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(properties.vectorTopK())
                .map(e -> {
                    Document doc = docMap.get(e.getKey());
                    if (doc != null) {
                        doc.getMetadata().put("rrfScore", e.getValue());
                    }
                    return doc;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // === 工具方法 ===

    private record ScoredDocument(Document doc, int rank, double score) {}
}
