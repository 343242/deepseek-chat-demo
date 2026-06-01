package com.smart.rag.rag.retrieval;

import com.smart.rag.config.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 百炼 Rerank 精排处理器
 * <p>
 * 调用阿里云百炼 qwen3-rerank 模型对检索结果进行语义级精排。
 * 相比向量相似度，Rerank 模型能更精准地评估查询-文档的相关性。
 * <p>
 * 高并发设计：
 * <ul>
 *   <li>内部维护独立线程池 {@link #rerankExecutor}，避免阻塞 Reactor 线程</li>
 *   <li>使用 {@link Future#get(long, TimeUnit)} 显式超时控制</li>
 *   <li>{@link ThreadPoolExecutor.CallerRunsPolicy} 兜底：线程池满时由调用线程执行</li>
 * </ul>
 *
 * <p>API 格式（OpenAI 兼容）：</p>
 * <pre>
 * POST {baseUrl}/reranks
 * {
 *   "model": "qwen3-rerank",
 *   "query": "用户问题",
 *   "documents": ["文档1", "文档2", ...],
 *   "top_n": 5
 * }
 * </pre>
 */
public class BailianRerankPostProcessor implements DocumentPostProcessor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BailianRerankPostProcessor.class);
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final long RERANK_TIMEOUT_SECONDS = 15;

    /** 问答检索任务指令 */
    private static final String DEFAULT_INSTRUCT = "Given a web search query, retrieve relevant passages that answer the query.";

    private final RestClient restClient;
    private final String model;
    private final int topN;

    /** Rerank 专用线程池 — 遵循 EtlExecutorConfig 规范：全 7 参数 + NamedThreadFactory */
    private final ExecutorService rerankExecutor;

    public BailianRerankPostProcessor(String baseUrl, String apiKey, String model, int topN) {
        this.model = model;
        this.topN = topN;

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(requestFactory)
                .build();

        // 遵循项目线程池规约：全 7 参数 + NamedThreadFactory + CallerRunsPolicy
        this.rerankExecutor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50),
                new NamedThreadFactory("rerank", true),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        log.info("BailianRerankPostProcessor initialized: model={}, topN={}, timeout={}s",
                model, topN, RERANK_TIMEOUT_SECONDS);
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        String queryText = query.text();

        List<String> docTexts = documents.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (docTexts.isEmpty()) {
            return documents;
        }

        // future 声明在 try 外，确保 catch 块中可 cancel
        Future<Map<String, Object>> future = null;

        try {
            Map<String, Object> requestBody = buildRequestBody(queryText, docTexts);

            // 在独立线程池中执行阻塞调用，避免占请求线程
            future = rerankExecutor.submit(() -> callWithRetry(requestBody));

            Map<String, Object> response = future.get(RERANK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (response == null || !response.containsKey("results")) {
                log.warn("Rerank API returned null or no results, returning original order");
                return documents;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

            List<Document> reranked = buildRerankedList(results, documents);

            log.debug("Rerank: {} docs → {} docs (model={})", documents.size(), reranked.size(), model);

            // Rerank 模型过滤掉所有文档时降级到原始列表
            if (reranked.isEmpty()) {
                log.warn("Rerank returned empty results, falling back to original documents");
                return documents;
            }

            return reranked;

        } catch (TimeoutException e) {
            // 超时后取消任务，释放线程池资源
            if (future != null) {
                future.cancel(true);
            }
            log.warn("Rerank timed out ({}s), degrading: queryLen={}",
                    RERANK_TIMEOUT_SECONDS, queryText.length());
            markFallback(documents);
            return documents;
        } catch (ExecutionException e) {
            log.warn("Rerank failed after retries, degrading: {}", e.getCause().getMessage());
            markFallback(documents);
            return documents;
        } catch (InterruptedException e) {
            if (future != null) {
                future.cancel(true);
            }
            Thread.currentThread().interrupt();
            log.warn("Rerank interrupted, degrading");
            markFallback(documents);
            return documents;
        } catch (Exception e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("Rerank unexpected error, degrading: {}", e.getMessage());
            markFallback(documents);
            return documents;
        }
    }

    /**
     * 构建 Rerank 请求体
     */
    private Map<String, Object> buildRequestBody(String queryText, List<String> docTexts) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("query", queryText);
        requestBody.put("documents", docTexts);
        requestBody.put("top_n", Math.min(topN, docTexts.size()));
        requestBody.put("instruct", DEFAULT_INSTRUCT);
        return requestBody;
    }

    /**
     * 构建重排后的文档列表。
     * <p>
     * 遵循 Spring AI 框架约定：直接修改 Document metadata，不创建新 Document。
     * L2 修复：增加 index >= 0 校验。
     */
    private List<Document> buildRerankedList(List<Map<String, Object>> results,
                                              List<Document> source) {
        List<Document> reranked = new ArrayList<>(results.size());
        for (Map<String, Object> result : results) {
            Number index = (Number) result.get("index");
            Number score = (Number) result.get("relevance_score");

            if (index != null && index.intValue() >= 0 && index.intValue() < source.size()) {
                Document doc = source.get(index.intValue());
                doc.getMetadata().put("rerankScore", score != null ? score.doubleValue() : 0.0);
                reranked.add(doc);
            }
        }
        return reranked;
    }

    /**
     * Spring 容器销毁回调 — 关闭 Rerank 线程池，等待进行中的任务完成。
     * <p>
     * 由 {@link com.smart.rag.rag.config.RagConfig} 中声明的 Bean 生命周期管理，
     * 不再需要调用方手动 shutdown。
     */
    @Override
    public void destroy() {
        rerankExecutor.shutdown();
        try {
            if (!rerankExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                rerankExecutor.shutdownNow();
                log.warn("Rerank executor did not terminate in 30s, forced shutdown");
            }
        } catch (InterruptedException e) {
            rerankExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 标记 fallback 状态，让下游（如 MMR）能感知 Rerank 未生效
     */
    private void markFallback(List<Document> documents) {
        for (Document doc : documents) {
            doc.getMetadata().put("rerankFallback", true);
        }
    }

    // ======================== 重试机制 ========================

    /**
     * 带重试的 Rerank API 调用。
     * 使用同步 RestClient + 手动指数退避：500ms → 1000ms → 2000ms。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callWithRetry(Map<String, Object> requestBody) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return restClient.post()
                        .uri("/reranks")
                        .body(requestBody)
                        .retrieve()
                        .body(Map.class);
            } catch (RuntimeException e) {
                last = e;
                if (attempt >= MAX_RETRIES || !isRetryable(e)) {
                    throw e;
                }
                sleepBeforeRetry(attempt, e);
            }
        }
        throw last;
    }

    private void sleepBeforeRetry(int attempt, RuntimeException e) {
        long backoffMs = Math.min(
                INITIAL_BACKOFF.toMillis() * (1L << (attempt - 1)),
                Duration.ofSeconds(2).toMillis());
        log.warn("Rerank API call failed (attempt {}), retrying: {}", attempt, e.getMessage());
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Rerank retry interrupted", interrupted);
        }
    }

    private boolean isRetryable(Throwable t) {
        return isRetryable0(t, 0);
    }

    private boolean isRetryable0(Throwable t, int depth) {
        if (t == null || depth > 10) return false;

        Throwable e = t;
        if (e == null) return false;

        if (e instanceof RestClientResponseException webEx) {
            var status = webEx.getStatusCode();
            return status == org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
                    || status == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
        }
        return e instanceof java.util.concurrent.TimeoutException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || (e.getCause() != null && isRetryable0(e.getCause(), depth + 1));
    }
}
