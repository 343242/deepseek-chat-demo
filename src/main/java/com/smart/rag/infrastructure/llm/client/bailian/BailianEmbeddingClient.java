package com.smart.rag.infrastructure.llm.client.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractEmbeddingClient;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/**
 * 百炼 Embedding 客户端 — DashScope 原生 API
 * <p>
 * 使用 DashScope 原生 /api/v1/services/embeddings/text-embedding/text-embedding 端点，
 * 支持 text_type、instruct 等高级参数（OpenAI 兼容接口不支持）。
 * <p>
 * 同时实现 {@link EmbeddingCapable}（SPI 层）和 Spring AI {@link EmbeddingModel}（框架层），
 * 供 PgVectorStore + AnswerRelevanceScorer 直接使用。
 */
public class BailianEmbeddingClient extends AbstractEmbeddingClient implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(BailianEmbeddingClient.class);
    private static final int MAX_BATCH_SIZE = 10;
    private static final String EMBEDDING_PATH =
        "/api/v1/services/embeddings/text-embedding/text-embedding";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private volatile float[] zeroVector;

    public BailianEmbeddingClient(String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.objectMapper = new ObjectMapper();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = RestClient.builder()
            .baseUrl("https://dashscope.aliyuncs.com")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build();

        log.info("BailianEmbeddingClient initialized: model={}, dimension={}, candidate={}",
            candidate.model(), candidate.dimension(), candidate.id());
    }

    // ======================== EmbeddingCapable (SPI) ========================

    @Override
    public float[] embed(String text, EmbeddingType type) {
        if (text == null || text.isBlank()) {
            return getZeroVector();
        }
        String textType = (type == EmbeddingType.DOCUMENT) ? "document" : "query";
        return callApi(List.of(text), textType, type == EmbeddingType.QUERY);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        if (texts == null || texts.isEmpty()) return List.of();
        String textType = (type == EmbeddingType.DOCUMENT) ? "document" : "query";
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            float[][] batchResult = callApiBatch(batch, textType);
            for (float[] f : batchResult) results.add(f);
        }
        return results;
    }

    @Override
    public int dimension() {
        return candidate.dimension();
    }

    // ======================== Spring AI EmbeddingModel ========================

    @Override
    public float @NonNull [] embed(@NonNull Document document) {
        String content = document.getText();
        if (content == null || content.isBlank()) return getZeroVector();
        return callApi(List.of(content), "document", false);
    }

    @Override
    public float @NonNull [] embed(@NonNull String text) {
        if (text.isBlank()) return getZeroVector();
        return callApi(List.of(text), "query", true);
    }

    @Override
    public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<Embedding> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            float[][] vectors = callApiBatch(batch, "query");
            for (int j = 0; j < vectors.length; j++) {
                allEmbeddings.add(new Embedding(vectors[j], i + j));
            }
        }
        return new EmbeddingResponse(allEmbeddings);
    }

    @Override
    public @NonNull List<float[]> embed(@NonNull List<String> texts) {
        return embedBatch(texts, EmbeddingType.QUERY);
    }

    @Override
    public int dimensions() {
        return candidate.dimension();
    }

    // ======================== DashScope Native API ========================

    private float[] callApi(List<String> texts, String textType, boolean withInstruct) {
        Map<String, Object> response = doPost(texts, textType, withInstruct);
        return extractFirst(response);
    }

    private float[][] callApiBatch(List<String> texts, String textType) {
        Map<String, Object> response = doPost(texts, textType, false);
        return extractAll(response, texts.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doPost(List<String> texts, String textType, boolean withInstruct) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("dimension", candidate.dimension());
        parameters.put("text_type", textType);
        parameters.put("output_type", "dense");
        if (withInstruct) {
            parameters.put("instruct",
                "Given a user question, retrieve the most relevant document passages");
        }

        Map<String, Object> body = Map.of(
            "model", candidate.model(),
            "input", Map.of("texts", texts),
            "parameters", parameters
        );

        try {
            String json = restClient.post()
                .uri(EMBEDDING_PATH)
                .body(body)
                .retrieve()
                .body(String.class);

            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "DashScope embedding API failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private float[] extractFirst(Map<String, Object> response) {
        Map<String, Object> output = (Map<String, Object>) response.get("output");
        if (output == null) throw emptyResponse();
        List<Map<String, Object>> embeddings = (List<Map<String, Object>>) output.get("embeddings");
        if (embeddings == null || embeddings.isEmpty()) throw emptyResponse();
        return toFloatArray((List<Number>) embeddings.get(0).get("embedding"));
    }

    @SuppressWarnings("unchecked")
    private float[][] extractAll(Map<String, Object> response, int count) {
        Map<String, Object> output = (Map<String, Object>) response.get("output");
        if (output == null) throw emptyResponse();
        List<Map<String, Object>> embeddings = (List<Map<String, Object>>) output.get("embeddings");
        if (embeddings == null) throw emptyResponse();

        float[][] result = new float[embeddings.size()][];
        for (int i = 0; i < embeddings.size(); i++) {
            result[i] = toFloatArray((List<Number>) embeddings.get(i).get("embedding"));
        }
        return result;
    }

    private float[] getZeroVector() {
        if (zeroVector == null) {
            synchronized (this) {
                if (zeroVector == null) {
                    zeroVector = new float[candidate.dimension()];
                }
            }
        }
        return zeroVector;
    }

    private static float[] toFloatArray(List<Number> doubles) {
        float[] floats = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            floats[i] = doubles.get(i).floatValue();
        }
        return floats;
    }

    private static RemoteException emptyResponse() {
        return new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
            "DashScope embedding API returned empty response");
    }
}
