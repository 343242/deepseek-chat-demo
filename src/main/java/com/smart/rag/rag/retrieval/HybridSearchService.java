package com.smart.rag.rag.retrieval;

import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.TaskState;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/**
 * 混合检索服务 -- RAG 检索域的核心实现
 * <p>
 * 供 HybridDocumentRetriever（Pipeline 模式）和 HybridSearchTool（Agent 模式）共用。
 * userId/teamId 从构造参数改为方法参数，支持按请求动态传入。
 * <p>
 * 检索流程（RetrievalPath 驱动）：
 * <ol>
 *   <li>遍历注入的 {@link RetrievalPath} 列表，通过 ScopedTasks 并发执行</li>
 *   <li>降级逻辑：全部失败抛异常，部分失败 warn + 优雅降级</li>
 *   <li>RRF (Reciprocal Rank Fusion) 按 path.rrfWeighting() 选择加权/纯排名融合</li>
 * </ol>
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private static final long SEARCH_TIMEOUT_SECONDS = 5;

    private final List<RetrievalPath> paths;
    private final RagRetrievalProperties properties;
    private final QueryNormalizer queryNormalizer;
    private final ScopedTasks scopedTasks;

    // ========================================================================
    // Production constructors (List<RetrievalPath> based)
    // ========================================================================

    public HybridSearchService(List<RetrievalPath> paths,
                               RagRetrievalProperties properties,
                               QueryNormalizer queryNormalizer,
                               Executor searchExecutor) {
        this(paths, properties, queryNormalizer, new com.smart.rag.infrastructure.concurrent.DefaultScopedTasks());
    }

    public HybridSearchService(List<RetrievalPath> paths,
                               RagRetrievalProperties properties,
                               QueryNormalizer queryNormalizer,
                               ScopedTasks scopedTasks) {
        this.paths = List.copyOf(paths);
        this.properties = properties;
        this.queryNormalizer = queryNormalizer;
        this.scopedTasks = scopedTasks;
    }

    // ========================================================================
    // Backward-compat constructors (for existing tests — DO NOT REMOVE until
    // HybridDocumentRetrieverTest is migrated to the new API)
    // ========================================================================

    /**
     * @deprecated Use the {@code List<RetrievalPath>} constructor instead.
     * Retained solely for backward compatibility with existing tests.
     */
    @Deprecated
    public HybridSearchService(VectorStore vectorStore,
                               VectorStoreMapper vectorStoreMapper,
                               RagRetrievalProperties properties,
                               QueryNormalizer queryNormalizer,
                               Executor searchExecutor) {
        this(buildPaths(vectorStore, vectorStoreMapper, properties),
                properties, queryNormalizer, searchExecutor);
    }

    /**
     * @deprecated Use the {@code List<RetrievalPath>} constructor instead.
     * Retained solely for backward compatibility with existing tests.
     */
    @Deprecated
    public HybridSearchService(VectorStore vectorStore,
                               VectorStoreMapper vectorStoreMapper,
                               RagRetrievalProperties properties,
                               QueryNormalizer queryNormalizer,
                               ScopedTasks scopedTasks) {
        this(buildPaths(vectorStore, vectorStoreMapper, properties),
                properties, queryNormalizer, scopedTasks);
    }

    /**
     * Static factory that builds the RetrievalPath list from legacy deps.
     * Mirrors the old hybridRetrievalEnabled conditional logic:
     * - Always includes vector-search.
     * - Includes bm25-search only when hybridRetrievalEnabled=true.
     */
    private static List<RetrievalPath> buildPaths(VectorStore vectorStore,
                                                  VectorStoreMapper vectorStoreMapper,
                                                  RagRetrievalProperties properties) {
        List<RetrievalPath> result = new ArrayList<>();
        result.add(new VectorRetrievalPath(vectorStore, properties));
        if (properties.hybridRetrievalEnabled()) {
            result.add(new Bm25RetrievalPath(vectorStoreMapper, new QueryNormalizer(), properties));
        }
        return result;
    }

    // ========================================================================
    // Core search
    // ========================================================================

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

        ScopeOptions options = ScopeOptions.builder("hybrid-search")
                .policy(ScopePolicy.COLLECT_ALL)
                .defaultTimeout(Duration.ofSeconds(SEARCH_TIMEOUT_SECONDS))
                .build();
        try (var scope = scopedTasks.open("hybrid-search", options)) {
            Map<RetrievalPath, Subtask<List<ScoredDocument>>> tasks = new LinkedHashMap<>();
            for (RetrievalPath path : paths) {
                tasks.put(path, scope.fork(path.name(), () -> path.search(normalized, userId, teamId)));
            }

            scope.join();

            // Degradation: count failures
            int failedCount = 0;
            for (var entry : tasks.entrySet()) {
                if (entry.getValue().exception() != null) {
                    failedCount++;
                    log.warn("{} degraded: {}", entry.getValue().name(),
                            entry.getValue().exception().getMessage());
                }
            }

            if (failedCount == paths.size()) {
                log.error("All {} retrieval path(s) failed for queryLen={}", paths.size(), normalized.length());
                throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "向量检索和 BM25 检索均不可用");
            }

            if (failedCount > 0) {
                log.warn("Partial search degradation: {}/{} paths failed", failedCount, paths.size());
            }

            List<Document> fused = rrfFusion(tasks);
            log.debug("Hybrid search: queryLen={}, paths={}, failed={}, fused={}, teamId={}",
                    normalized.length(), paths.size(), failedCount, fused.size(), teamId);

            return fused;
        }
    }

    // ========================================================================
    // RRF Fusion
    // ========================================================================

    private List<Document> rrfFusion(Map<RetrievalPath, Subtask<List<ScoredDocument>>> tasks) {
        int k = properties.rrfK();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        for (var entry : tasks.entrySet()) {
            RetrievalPath path = entry.getKey();
            List<ScoredDocument> docs = taskResultOrEmpty(entry.getValue(), path.name());
            boolean scored = path.rrfWeighting() == RetrievalPath.RrfWeighting.SCORE_WEIGHTED;

            for (ScoredDocument sd : docs) {
                String docId = sd.doc().getId();
                if (docId == null) continue;
                double contribution = scored
                        ? sd.score() * (1.0 / (k + sd.rank()))
                        : 1.0 / (k + sd.rank());
                scores.merge(docId, contribution, Double::sum);
                docMap.putIfAbsent(docId, sd.doc());
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(properties.fusionTopK())
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

    // ========================================================================
    // Utility
    // ========================================================================

    private List<ScoredDocument> taskResultOrEmpty(
            Subtask<List<ScoredDocument>> task,
            String branchName
    ) {
        if (task.state() == TaskState.CANCELLED) {
            throw new java.util.concurrent.CompletionException(
                    new TimeoutException(branchName + " cancelled before completion"));
        }
        Throwable failure = task.exception();
        if (failure == null) {
            return task.result();
        }
        if (failure instanceof TimeoutException) {
            throw new java.util.concurrent.CompletionException(failure);
        }
        return List.of();
    }
}
