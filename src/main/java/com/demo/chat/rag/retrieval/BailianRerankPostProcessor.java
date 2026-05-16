package com.demo.chat.rag.retrieval;

import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 百炼 Rerank 精排处理器
 * <p>
 * 调用阿里云百炼 qwen3-rerank 模型对检索结果进行语义级精排。
 * 相比向量相似度，Rerank 模型能更精准地评估查询-文档的相关性。
 * </p>
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
public class BailianRerankPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(BailianRerankPostProcessor.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

    /** 问答检索任务指令 */
    private static final String DEFAULT_INSTRUCT = "Given a web search query, retrieve relevant passages that answer the query.";

    private final WebClient webClient;
    private final String model;
    private final int topN;

    public BailianRerankPostProcessor(String baseUrl, String apiKey, String model, int topN) {
        this.model = model;
        this.topN = topN;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        log.info("BailianRerankPostProcessor initialized: model={}, topN={}", model, topN);
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        String queryText = query.text();

        List<String> docTexts = documents.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        if (docTexts.isEmpty()) {
            return documents;
        }

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("query", queryText);
            requestBody.put("documents", docTexts);
            requestBody.put("top_n", Math.min(topN, docTexts.size()));
            requestBody.put("instruct", DEFAULT_INSTRUCT);

            Map<String, Object> response = callWithRetry(requestBody);

            if (response == null || !response.containsKey("results")) {
                log.warn("Rerank API returned null or no results, returning original order");
                return documents;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

            List<Document> reranked = new ArrayList<>(results.size());
            for (Map<String, Object> result : results) {
                Number index = (Number) result.get("index");
                Number score = (Number) result.get("relevance_score");

                if (index != null && index.intValue() < documents.size()) {
                    Document doc = documents.get(index.intValue());
                    doc.getMetadata().put("rerankScore", score != null ? score.doubleValue() : 0.0);
                    reranked.add(doc);
                }
            }

            log.debug("Rerank: {} docs → {} docs (model={})", documents.size(), reranked.size(), model);
            return reranked;

        } catch (Exception e) {
            log.warn("Rerank API call failed after retries, returning original order: {}", e.getMessage());
            // 标记 fallback，让下游（如 MMR）能感知 Rerank 未生效
            for (Document doc : documents) {
                doc.getMetadata().put("rerankFallback", true);
            }
            return documents;
        }
    }

    // ======================== 重试机制 ========================

    /**
     * 带重试的 Rerank API 调用。
     * 使用 Reactor retryWhen 实现指数退避：500ms → 1000ms → 2000ms。
     * 退避等待在 Reactor 调度器上执行，不阻塞当前线程。
     * 可重试：429 限流、503 服务不可用、超时、网络错误。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callWithRetry(Map<String, Object> requestBody) {
        return webClient.post()
                .uri("/reranks")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(TIMEOUT)
                .retryWhen(Retry.backoff(MAX_RETRIES, INITIAL_BACKOFF)
                        .maxBackoff(Duration.ofSeconds(2))
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal ->
                                log.warn("Rerank API call failed (attempt {}), retrying: {}",
                                        signal.totalRetries() + 1, signal.failure().getMessage())))
                .block();
    }

    private boolean isRetryable(Throwable t) {
        // 解包 Reactor retry 包装的异常
        Throwable e = t;
        while (e != null && e.getClass().getName().startsWith("reactor.")) {
            e = e.getCause();
        }
        if (e == null) return false;

        if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException webEx) {
            var status = webEx.getStatusCode();
            return status == org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
                    || status == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
        }
        return e instanceof java.util.concurrent.TimeoutException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || (e.getCause() != null && isRetryable(e.getCause()));
    }
}
