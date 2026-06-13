package com.smart.rag.infrastructure.llm.client.generic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
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
 * 通用 Rerank 客户端（Cohere 风格 API）
 * <p>
 * POST 请求体：{@code { model, query, documents }}
 * 响应格式：{@code { results: [{ index, relevance_score }, ...] }}
 */
public class GenericRerankClient extends AbstractRerankClient {

    private static final Logger log = LoggerFactory.getLogger(GenericRerankClient.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 15;

    private final String baseUrl;
    private final String endpoint;
    private final String apiKey;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GenericRerankClient(String baseUrl, String endpoint,
                               String apiKey, ModelCandidate candidate) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.objectMapper = new ObjectMapper();

        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS));

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build();

        log.info("GenericRerankClient initialized: model={}, endpoint={}", candidate.model(), endpoint);
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

            return parseRerankResponse(objectMapper, responseJson, request.documents());
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            String url = baseUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
            throw HttpClientErrorHandler.translate("Rerank", url, e);
        }
    }

    @Override
    public void close() {
        if (httpClient != null) {
            httpClient.close();
        }
    }
}
