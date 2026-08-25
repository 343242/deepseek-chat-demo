package com.smart.rag.infrastructure.llm.client.generic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractEmbeddingClient;
import com.smart.rag.infrastructure.llm.client.HttpClientErrorHandler;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import com.smart.rag.infrastructure.llm.client.TimeoutParams;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * 通用 Embedding 客户端（OpenAI 兼容 API）
 * <p>
 * 基于 OpenAI 兼容的 /v1/embeddings 端点。阻塞路径走共享 OkHttp 传输（WS3 统一），
 * 超时经 {@link TimeoutParams} 从 candidate params 配置化。
 */
public class GenericEmbeddingClient extends AbstractEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GenericEmbeddingClient.class);
    private static final int BATCH_SIZE = 10;
    private static final long READ_TIMEOUT_MS = 30_000;

    private final String baseUrl;
    private final String endpoint;
    private final String apiKey;
    private final OkHttpClient okClient;
    private final ObjectMapper objectMapper;

    public GenericEmbeddingClient(HttpClientFactory httpClientFactory, String baseUrl, String endpoint,
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

        log.info("GenericEmbeddingClient initialized: model={}, endpoint={}", candidate.model(), endpoint);
    }

    @Override
    public float[] embed(String text, EmbeddingType type) {
        if (text == null || text.isBlank()) {
            return new float[dimension()];
        }

        Map<String, Object> body = Map.of(
            "model", candidate.model(),
            "input", List.of(text)
        );

        String responseJson = callApi(body);
        return extractEmbedding(responseJson, 0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        log.debug("embedBatch called with EmbeddingType={}, but OpenAI-compatible /v1/embeddings does not support text_type; ignoring", type);

        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            Map<String, Object> body = Map.of(
                "model", candidate.model(),
                "input", batch
            );

            String responseJson = callApi(body);
            for (int j = 0; j < batch.size(); j++) {
                results.add(extractEmbedding(responseJson, j));
            }
        }
        return results;
    }

    @Override
    public int dimension() {
        return candidate.dimension();
    }

    private String callApi(Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            return HttpClientFactory.executePostJson(okClient, url(), apiKey, json, "Embedding");
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw HttpClientErrorHandler.translate("Embedding", url(), e);
        }
    }

    private String url() {
        return baseUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
    }

    @Override
    public void close() {
        // 共享 OkHttp 实例由 HttpClientFactory.closeAll() 统一管理，不在此关闭（WS3）
    }

    private float[] extractEmbedding(String json, int index) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() <= index) {
                throw new RemoteException(RemoteErrorCode.LLM_RESPONSE_PARSE_ERROR,
                    "Embedding response missing data at index " + index);
            }
            JsonNode embeddingNode = data.get(index).path("embedding");
            float[] result = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                result[i] = (float) embeddingNode.get(i).asDouble();
            }
            return result;
        } catch (RemoteException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteException(RemoteErrorCode.LLM_RESPONSE_PARSE_ERROR,
                "Failed to parse embedding response: " + e.getMessage(), e);
        }
    }
}
