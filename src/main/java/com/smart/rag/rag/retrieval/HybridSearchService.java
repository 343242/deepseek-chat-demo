package com.smart.rag.rag.retrieval;

import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.TaskState;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.trace.TraceRecorder;
import com.smart.rag.rag.config.RagRetrievalProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
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
    private final @Nullable TraceRecorder traceRecorder;

    // ========================================================================
    // 唯一构造器：RetrievalPath 列表由 Spring 注入（OCP：新路径注册即生效），
    // ScopedTasks 由 {@code ScopedTaskAutoConfiguration} 提供。
    // ========================================================================

    public HybridSearchService(List<RetrievalPath> paths,
                               RagRetrievalProperties properties,
                               QueryNormalizer queryNormalizer,
                               ScopedTasks scopedTasks,
                               @Nullable TraceRecorder traceRecorder) {
        this.paths = List.copyOf(paths);
        this.properties = properties;
        this.queryNormalizer = queryNormalizer;
        this.scopedTasks = scopedTasks;
        this.traceRecorder = traceRecorder;
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
            // 记录每路独立召回结果（②trace_event PATH_RECALL + ①结构化日志）
            recordPathRecall(userId, normalized, tasks);

            List<Document> fused = rrfFusion(tasks);
            log.info("Hybrid search: queryLen={}, paths={}, failed={}, fused={}, teamId={}",
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
        // 按 chunkId 累积命中的分路名（provenance）：同一 chunk 被多路命中 -> sources 为多值
        Map<String, List<String>> sourcesByDoc = new HashMap<>();

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
                sourcesByDoc.computeIfAbsent(docId, x -> new ArrayList<>()).add(sd.pathName());
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(properties.fusionTopK())
                .map(e -> {
                    Document doc = docMap.get(e.getKey());
                    if (doc != null) {
                        doc.getMetadata().put("rrfScore", e.getValue());
                        doc.getMetadata().put("sources", sourcesByDoc.getOrDefault(e.getKey(), List.of()));
                    }
                    return doc;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // ========================================================================
    // Per-path recall tracing（② trace_event PATH_RECALL — 每路独立召回明细）
    // ========================================================================

    /**
     * 记录每路独立召回结果：①结构化日志 + ②trace_event 持久化（step_type=PATH_RECALL）。
     * <p>
     * 在 rrfFusion 之前调用——此时每路结果完整、失败状态可查。
     * traceRecorder 可空（EvaluationRunner 构造时传 null，跳过持久化）。
     *
     * @param userId    用户 ID（隔离条件 + trace_event.user_id）
     * @param queryText 归一化后的查询（写入 input_summary）
     * @param tasks     各路 subtask（key=path, value=该路召回的 ScoredDocument 列表）
     */
    private void recordPathRecall(long userId, String queryText,
                                  Map<RetrievalPath, Subtask<List<ScoredDocument>>> tasks) {
        String traceId = mdcTraceId();
        String sessionId = mdcSessionId();
        for (var entry : tasks.entrySet()) {
            RetrievalPath path = entry.getKey();
            Subtask<List<ScoredDocument>> task = entry.getValue();
            boolean success = task.exception() == null;
            List<ScoredDocument> docs = success ? safePathResults(task) : List.of();

            // documents JSONB（仅元数据，不含正文——与 trace_event 约定一致）
            List<Map<String, Object>> documents = new ArrayList<>(docs.size());
            for (ScoredDocument sd : docs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chunkId", sd.doc().getId());
                m.put("rank", sd.rank());
                m.put("score", sd.score());
                Map<String, Object> md = sd.doc().getMetadata();
                if (md != null) {
                    Object documentId = md.get("documentId");
                    if (documentId != null) m.put("documentId", documentId);
                    Object fileName = md.get("fileName");
                    if (fileName != null) m.put("fileName", fileName);
                    Object page = md.get("page_number");
                    if (page != null) m.put("page", page);
                }
                documents.add(m);
            }

            log.info("Path recall: path={}, ok={}, count={}, chunks={}",
                    path.name(), success, docs.size(),
                    documents.stream().map(d ->
                        "[" + d.get("rank") + "]" + d.get("chunkId")
                        + " doc=" + d.getOrDefault("documentId", "?")
                        + " file=" + d.getOrDefault("fileName", "?"))
                    .toList());

            if (traceRecorder != null) {
                traceRecorder.record(traceId, sessionId, userId, "PATH_RECALL", path.name(),
                        success, null, queryText, null,
                        docs.size(), null, documents);
            }
        }
    }

    /** 安全提取 subtask 结果（不抛异常，供 trace 记录用；rrfFusion 仍用 taskResultOrEmpty 做严格错误传播） */
    private List<ScoredDocument> safePathResults(Subtask<List<ScoredDocument>> task) {
        try {
            List<ScoredDocument> r = task.result();
            return r != null ? r : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static @Nullable String mdcTraceId() {
        String tid = MDC.get("traceId");
        return (tid != null && !tid.isBlank()) ? tid : null;
    }

    private static String mdcSessionId() {
        String sid = MDC.get("ragSessionId");
        return (sid != null && !sid.isBlank()) ? sid : "unknown";
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
