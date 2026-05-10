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

        // 提取文档文本
        List<String> docTexts = documents.stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        if (docTexts.isEmpty()) {
            return documents;
        }

        try {
            // 构建请求体
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("query", queryText);
            requestBody.put("documents", docTexts);
            requestBody.put("top_n", Math.min(topN, docTexts.size()));
            requestBody.put("instruct", DEFAULT_INSTRUCT);

            // 调用 Rerank API
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/reranks")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();

            if (response == null || !response.containsKey("results")) {
                log.warn("Rerank API returned null or no results, returning original order");
                return documents;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

            // 按 rerank 返回的顺序重排文档
            List<Document> reranked = new ArrayList<>(results.size());
            for (Map<String, Object> result : results) {
                Number index = (Number) result.get("index");
                Number score = (Number) result.get("relevance_score");

                if (index != null && index.intValue() < documents.size()) {
                    Document doc = documents.get(index.intValue());
                    // 注入 rerank 分数到 metadata
                    doc.getMetadata().put("rerankScore", score != null ? score.doubleValue() : 0.0);
                    reranked.add(doc);
                }
            }

            log.debug("Rerank: {} docs → {} docs (model={})", documents.size(), reranked.size(), model);
            return reranked;

        } catch (Exception e) {
            log.warn("Rerank API call failed, returning original order: {}", e.getMessage());
            return documents;
        }
    }
}
