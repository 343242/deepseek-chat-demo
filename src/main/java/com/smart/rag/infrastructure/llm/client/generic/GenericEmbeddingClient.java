package com.smart.rag.infrastructure.llm.client.generic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractEmbeddingClient;
import com.smart.rag.infrastructure.llm.client.HttpClientErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/**
 * 通用 Embedding 客户端（OpenAI 兼容 API）
 * <p>
 * 基于 OpenAI 兼容的 /v1/embeddings 端点。
 */
public class GenericEmbeddingClient extends AbstractEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GenericEmbeddingClient.class);
    private static final int BATCH_SIZE = 10;

    protected final String baseUrl;
    protected final String endpoint;
    protected final String apiKey;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GenericEmbeddingClient(String baseUrl, String endpoint,
                                  String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.baseUrl = baseUrl;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();

        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build();
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
        String url = baseUrl + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
        try {
            return restClient.post()
                .uri(endpoint)
                .body(body)
                .retrieve()
                .body(String.class);
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw HttpClientErrorHandler.translate("Embedding", url, e);
        }
    }

    @Override
    public void close() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    private float[] extractEmbedding(String json, int index) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() <= index) {
                // LLM_STREAM_ERROR used as catch-all for parse failures (no dedicated parse error code)
                throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
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
            // LLM_STREAM_ERROR used as catch-all for parse failures (no dedicated parse error code)
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "Failed to parse embedding response: " + e.getMessage(), e);
        }
    }
}
