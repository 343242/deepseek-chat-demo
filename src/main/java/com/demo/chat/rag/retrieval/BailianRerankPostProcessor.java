package com.demo.chat.rag.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.web.reactive.function.client.WebClient;

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

    /** 问答检索任务指令 */
    private static final String DEFAULT_INSTRUCT = "Given a web search query, retrieve relevant passages that answer the query.";

    private final WebClient webClient;
    private final String model;
    private final int topN;

    public BailianRerankPostProcessor(String baseUrl, String apiKey, String model, int topN) {
        this.model = model;
        this.topN = topN;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
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

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;

    /**
     * 带重试的 Rerank API 调用。
     * 指数退避：500ms → 1000ms → 2000ms。
     * 可重试：429 限流、503 服务不可用、超时、网络错误。
     */
    private Map<String, Object> callWithRetry(Map<String, Object> requestBody) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = webClient.post()
                        .uri("/reranks")
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(TIMEOUT)
                        .block();

                if (response == null) {
                    throw new RuntimeException("Rerank API returned null response");
                }
                return response;
            } catch (Exception e) {
                lastException = e;
                if (isRetryable(e) && attempt < MAX_RETRIES) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                    log.warn("Rerank API call failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt, MAX_RETRIES, backoff, e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during rerank retry backoff", ie);
                    }
                } else {
                    break;
                }
            }
        }
        throw new RuntimeException(
                String.format("Rerank API call failed after %d attempts: %s",
                        MAX_RETRIES, lastException.getMessage()), lastException);
    }

    private boolean isRetryable(Exception e) {
        // 精确类型检查，替代不可靠的字符串匹配
        if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException webEx) {
            var status = webEx.getStatusCode();
            return status == org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
                    || status == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
        }
        return e instanceof java.util.concurrent.TimeoutException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || (e.getCause() instanceof Exception cause && isRetryable(cause));
    }
}
