package com.smart.rag.infrastructure.llm.client.generic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractRerankClient;
import com.smart.rag.infrastructure.llm.client.HttpClientErrorHandler;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import com.smart.rag.infrastructure.llm.client.TimeoutParams;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

/**
 * 通用 Rerank 客户端（Cohere 风格 API）
 * <p>
 * POST 请求体：{@code { model, query, documents }}，
 * 响应格式：{@code { results: [{ index, relevance_score }, ...] }}。
 * 阻塞路径走共享 OkHttp 传输（WS3 统一），超时经 {@link TimeoutParams} 配置化。
 */
public class GenericRerankClient extends AbstractRerankClient {

    private static final Logger log = LoggerFactory.getLogger(GenericRerankClient.class);
    private static final long READ_TIMEOUT_MS = 15_000;

    private final String baseUrl;
    private final String endpoint;
    private final String apiKey;
    private final OkHttpClient okClient;
    private final ObjectMapper objectMapper;

    public GenericRerankClient(HttpClientFactory httpClientFactory, String baseUrl, String endpoint,
                               String apiKey, ModelCandidate candidate) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        TimeoutParams timeouts = TimeoutParams.otherDefaults(READ_TIMEOUT_MS).mergeWithParams(candidate.params());
        this.okClient = httpClientFactory.sharedOkHttpClient(
            Duration.ofMillis(timeouts.connectTimeoutMs()),
            Duration.ofMillis(timeouts.readTimeoutMs()),
            Duration.ofMillis(timeouts.callTimeoutMs()));

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
            String json = HttpClientFactory.executePostJson(okClient, url(), apiKey,
                objectMapper.writeValueAsString(body), "Rerank");
            return parseRerankResponse(objectMapper, json, request.documents());
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw HttpClientErrorHandler.translate("Rerank", url(), e);
        }
    }

    private String url() {
        return baseUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
    }

    @Override
    public void close() {
        // 共享 OkHttp 实例由 HttpClientFactory.closeAll() 统一管理，不在此关闭（WS3）
    }
}
