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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

/**
 * 百炼 Embedding 客户端 — DashScope 原生 API
 * <p>
 * 使用 DashScope 原生端点（从配置注入），支持 text_type、instruct 等高级参数。
 * 子批次使用结构化并发（{@link ScopedTasks}）并行调用，加速大批量向量化。
 * <p>
 * 仅实现 {@link EmbeddingCapable}（SPI 层）。需要 Spring AI {@code EmbeddingModel}
 * 视图的调用方（PgVectorStore、AnswerRelevanceScorer 等）应通过
 * {@link BailianSpringAiEmbeddingAdapter} 包装本客户端，由
 * {@link com.smart.rag.infrastructure.llm.config.LlmAutoConfiguration#embeddingModel}
 * 自动装配。
 */
public class BailianEmbeddingClient extends AbstractEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(BailianEmbeddingClient.class);
    /** 默认单请求行数上限：百炼同步接口 text-embedding-v3/v4 及 Qwen3-Embedding 系列均为 10 行 */
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MAX_CONCURRENCY = 4;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpClientFactory.HttpHandles http;
    private final ScopedTasks scopedTasks;
    private final float[] zeroVector;
    /** 单请求行数上限，来自候选 params.batch-size（如 qwen3.7-text-embedding 官方上限 20） */
    private final int batchSize;

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
        this.zeroVector = new float[candidate.dimension()];
        this.batchSize = resolveBatchSize(candidate.params());
        this.http = HttpClientFactory.buildRestClient(baseUrl, apiKey,
            Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS), Duration.ofSeconds(READ_TIMEOUT_SECONDS));
        this.restClient = http.restClient();

        log.info("BailianEmbeddingClient initialized: model={}, dimension={}, candidate={}, batchSize={}, baseUrl={}",
            candidate.model(), candidate.dimension(), candidate.id(), batchSize, baseUrl);
    }

    /**
     * 从候选 params 解析单请求行数上限。
     * <p>
     * 百炼各模型官方上限不同（qwen3.7-text-embedding 为 20，text-embedding-v3/v4 为 10），
     * 由候选显式声明 {@code params.batch-size}，未声明时取 {@link #DEFAULT_BATCH_SIZE}。
     * 非法值（非正数、无法解析）同样回退默认值——批量上限只影响吞吐，fail-fast 无收益。
     */
    static int resolveBatchSize(Map<String, Object> params) {
        Object value = params == null ? null : params.get("batch-size");
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                int parsed = Integer.parseInt(s.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // 回退默认值
            }
        }
        return DEFAULT_BATCH_SIZE;
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
        // 批量 QUERY 与单条 embed 保持一致携带 instruct（官方文档：instruct 辅助 query 侧理解，约 +1%~5%）
        float[][][] batches = executeBatchesConcurrently(texts, textType, type == EmbeddingType.QUERY);
        List<float[]> results = new ArrayList<>(texts.size());
        for (float[][] batch : batches) {
            results.addAll(Arrays.asList(batch));
        }
        return results;
    }

    @Override
    public int dimension() {
        return candidate.dimension();
    }

    // ======================== Concurrent Batch Execution ========================

    private float[][][] executeBatchesConcurrently(List<String> texts, String textType, boolean withInstruct) {
        int batchCount = (texts.size() + batchSize - 1) / batchSize;
        if (batchCount <= 1) {
            return new float[][][] { callApiBatch(texts, textType, withInstruct) };
        }

        float[][][] batchResults = new float[batchCount][][];
        ScopeOptions options = ScopeOptions.builder("embed-batch")
            .policy(ScopePolicy.SHUTDOWN_ON_FAILURE)
            .maxConcurrency(MAX_CONCURRENCY)
            .build();

        try (TaskScope scope = scopedTasks.open("embed-batch", options)) {
            for (int i = 0; i < texts.size(); i += batchSize) {
                List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
                int idx = i / batchSize;
                scope.fork("batch-" + idx, () -> {
                    batchResults[idx] = callApiBatch(batch, textType, withInstruct);
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

    private float[][] callApiBatch(List<String> texts, String textType, boolean withInstruct) {
        JsonNode response = doPost(texts, textType, withInstruct);
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

    /**
     * 解析批量响应。
     * <p>
     * 官方响应契约：{@code output.embeddings[i].text_index} 为该向量对应输入数组的索引值
     * （正常情况与数组顺序一致）。按 {@code text_index} 归位并对越界/缺失做防御回退，
     * 避免乱序返回时向量与文本错配——对向量库是静默数据损坏。
     */
    float[][] extractAll(JsonNode response) {
        JsonNode embeddings = response.path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty()) throw emptyResponse();

        float[][] result = new float[embeddings.size()][];
        for (int i = 0; i < embeddings.size(); i++) {
            JsonNode node = embeddings.get(i);
            int idx = node.path("text_index").asInt(i);
            if (idx < 0 || idx >= result.length) idx = i;
            result[idx] = toFloatArray(node.path("embedding"));
        }
        for (float[] vector : result) {
            if (vector == null) throw new RemoteException(RemoteErrorCode.LLM_RESPONSE_PARSE_ERROR,
                "DashScope embedding response text_index misaligned with input texts");
        }
        return result;
    }

    /**
     * 返回零向量的防御性拷贝。
     * <p>
     * 调用方可能修改返回的数组（如写入 PgVector 失败后清零），
     * 若不拷贝会污染实例字段 {@link #zeroVector}，影响后续调用。
     */
    private float[] getZeroVector() {
        return zeroVector.clone();
    }

    private static float[] toFloatArray(JsonNode node) {
        if (!node.isArray()) throw new RemoteException(
            RemoteErrorCode.LLM_RESPONSE_PARSE_ERROR, "Expected JSON array for embedding vector");
        float[] floats = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            floats[i] = (float) node.get(i).asDouble();
        }
        return floats;
    }

    private static RemoteException emptyResponse() {
        return new RemoteException(RemoteErrorCode.LLM_RESPONSE_PARSE_ERROR,
            "DashScope embedding API returned empty response");
    }

    @Override
    public void close() {
        http.close();
    }
}
