package com.smart.rag.infrastructure.llm.client.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractEmbeddingClient;
import com.smart.rag.infrastructure.llm.client.HttpClientErrorHandler;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

/**
 * 百炼 Embedding 客户端 — DashScope 原生 API
 * <p>
 * 使用 DashScope 原生端点（从配置注入），支持 text_type、instruct 等高级参数。
 * 子批次使用结构化并发（{@link ScopedTasks}）并行调用，加速大批量向量化。
 * <p>
 * 同时实现 {@link EmbeddingCapable}（SPI 层）和 Spring AI {@link EmbeddingModel}（框架层），
 * 供 PgVectorStore + AnswerRelevanceScorer 直接使用。
 */
public class BailianEmbeddingClient extends AbstractEmbeddingClient implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(BailianEmbeddingClient.class);
    private static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_CONCURRENCY = 4;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpClientFactory.HttpHandles http;
    private final ScopedTasks scopedTasks;
    // DCL pattern: volatile guarantees the float[] reference is safely published.
    // The array contents (all zeros) are read-only after construction, so no further synchronization is needed.
    private volatile float[] zeroVector;

    private final String baseUrl;
    private final String endpoint;

    public BailianEmbeddingClient(String baseUrl, String endpoint, String apiKey,
                                   ModelCandidate candidate, ScopedTasks scopedTasks) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        Objects.requireNonNull(scopedTasks, "scopedTasks must not be null");
        this.baseUrl = baseUrl;
        this.endpoint = endpoint;
        this.scopedTasks = scopedTasks;
        this.objectMapper = new ObjectMapper();
        this.http = HttpClientFactory.buildRestClient(baseUrl, apiKey,
            Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS), Duration.ofSeconds(READ_TIMEOUT_SECONDS));
        this.restClient = http.restClient();

        log.info("BailianEmbeddingClient initialized: model={}, dimension={}, candidate={}, baseUrl={}",
            candidate.model(), candidate.dimension(), candidate.id(), baseUrl);
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
        float[][][] batches = executeBatchesConcurrently(texts, textType);
        List<float[]> results = new ArrayList<>(texts.size());
        for (float[][] batch : batches) {
            for (float[] f : batch) results.add(f);
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
        float[][][] batches = executeBatchesConcurrently(texts, "query");
        List<Embedding> allEmbeddings = new ArrayList<>(texts.size());
        int globalIndex = 0;
        for (float[][] batch : batches) {
            for (float[] vector : batch) {
                allEmbeddings.add(new Embedding(vector, globalIndex++));
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

    // ======================== Concurrent Batch Execution ========================

    private float[][][] executeBatchesConcurrently(List<String> texts, String textType) {
        int batchCount = (texts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
        if (batchCount <= 1) {
            return new float[][][] { callApiBatch(texts, textType) };
        }

        float[][][] batchResults = new float[batchCount][][];
        ScopeOptions options = ScopeOptions.builder("embed-batch")
            .policy(ScopePolicy.SHUTDOWN_ON_FAILURE)
            .maxConcurrency(MAX_CONCURRENCY)
            .build();

        try (TaskScope scope = scopedTasks.open("embed-batch", options)) {
            for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
                List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
                int idx = i / MAX_BATCH_SIZE;
                scope.fork("batch-" + idx, () -> {
                    batchResults[idx] = callApiBatch(batch, textType);
                    return null;
                });
            }
            scope.join();
        }

        return batchResults;
    }

    // ======================== DashScope Native API ========================

    private float[] callApi(List<String> texts, String textType, boolean withInstruct) {
        JsonNode response = doPost(texts, textType, withInstruct);
        return extractFirst(response);
    }

    private float[][] callApiBatch(List<String> texts, String textType) {
        JsonNode response = doPost(texts, textType, false);
        return extractAll(response);
    }

    private JsonNode doPost(List<String> texts, String textType, boolean withInstruct) {
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
                .uri(endpoint)
                .body(body)
                .retrieve()
                .body(String.class);

            return objectMapper.readTree(json);
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw HttpClientErrorHandler.translate("DashScope Embedding",
                baseUrl + endpoint, e);
        }
    }

    private float[] extractFirst(JsonNode response) {
        JsonNode embeddings = response.path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty()) throw emptyResponse();
        return toFloatArray(embeddings.get(0).path("embedding"));
    }

    private float[][] extractAll(JsonNode response) {
        JsonNode embeddings = response.path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty()) throw emptyResponse();

        float[][] result = new float[embeddings.size()][];
        for (int i = 0; i < embeddings.size(); i++) {
            result[i] = toFloatArray(embeddings.get(i).path("embedding"));
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

    private static float[] toFloatArray(JsonNode node) {
        if (!node.isArray()) throw new RemoteException(
            RemoteErrorCode.LLM_STREAM_ERROR, "Expected JSON array for embedding vector");
        float[] floats = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            floats[i] = (float) node.get(i).asDouble();
        }
        return floats;
    }

    private static RemoteException emptyResponse() {
        return new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
            "DashScope embedding API returned empty response");
    }

    @Override
    public void close() {
        http.close();
    }
}
