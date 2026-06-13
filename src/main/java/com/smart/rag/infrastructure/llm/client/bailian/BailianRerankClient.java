package com.smart.rag.infrastructure.llm.client.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractRerankClient;
import com.smart.rag.infrastructure.llm.client.HttpClientErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/**
 * 百炼 Rerank 客户端 — OpenAI 兼容 API
 * <p>
 * 使用百炼 /reranks 端点（OpenAI 兼容格式）。
 * 不含线程池和重试——由 Resilient 装饰器层统一处理。
 */
public class BailianRerankClient extends AbstractRerankClient {

    private static final Logger log = LoggerFactory.getLogger(BailianRerankClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public BailianRerankClient(String baseUrl, String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.objectMapper = new ObjectMapper();

        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build();

        log.info("BailianRerankClient initialized: model={}, candidate={}",
            candidate.model(), candidate.id());
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        if (request.documents() == null || request.documents().isEmpty()) {
            return List.of();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", candidate.model());
        body.put("query", request.query());
        body.put("documents", request.documents());
        body.put("top_n", request.documents().size());

        try {
            String json = restClient.post()
                .uri("/reranks")
                .body(body)
                .retrieve()
                .body(String.class);

            return parseResponse(json, request.documents());
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw HttpClientErrorHandler.translate("Bailian Rerank", "/reranks", e);
        }
    }

    private List<RerankResult> parseResponse(String json, List<String> documents) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray()) return List.of();

            List<RerankResult> rerankResults = new ArrayList<>(results.size());
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(0.0);
                if (index >= 0 && index < documents.size()) {
                    rerankResults.add(new RerankResult(index, score, documents.get(index)));
                }
            }
            return rerankResults;
        } catch (Exception e) {
            // LLM_STREAM_ERROR used as catch-all for parse failures (no dedicated parse error code)
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "Failed to parse rerank response: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (httpClient != null) {
            httpClient.close();
        }
    }
}
