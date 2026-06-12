package com.smart.rag.infrastructure.llm.client.generic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractRerankClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/**
 * 通用 Rerank 客户端（Cohere 风格 API）
 * <p>
 * POST 请求体：{@code { model, query, documents }}
 * 响应格式：{@code { results: [{ index, relevance_score }, ...] }}
 */
public class GenericRerankClient extends AbstractRerankClient {

    private static final Logger log = LoggerFactory.getLogger(GenericRerankClient.class);

    protected final String baseUrl;
    protected final String endpoint;
    protected final String apiKey;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GenericRerankClient(String baseUrl, String endpoint,
                               String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.baseUrl = baseUrl;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build();
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

        try {
            String responseJson = restClient.post()
                .uri(endpoint)
                .body(body)
                .retrieve()
                .body(String.class);

            return parseResponse(responseJson, request.documents());
        } catch (Exception e) {
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "Rerank call failed: " + e.getMessage(), e);
        }
    }

    private List<RerankResult> parseResponse(String json, List<String> documents) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return List.of();
            }

            List<RerankResult> rerankResults = new ArrayList<>(results.size());
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(0.0);
                if (index >= 0 && index < documents.size()) {
                    rerankResults.add(new RerankResult(index, score, documents.get(index)));
                }
            }
            return rerankResults;
        } catch (IOException e) {
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "Failed to parse rerank response: " + e.getMessage(), e);
        }
    }
}
