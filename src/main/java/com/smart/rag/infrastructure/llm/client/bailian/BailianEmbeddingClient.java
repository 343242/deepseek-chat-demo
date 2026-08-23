package com.smart.rag.infrastructure.llm.client.bailian;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.client.AbstractEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 百炼 Embedding 客户端 — dashscope-sdk-java（DashScope 原生协议）
 * <p>
 * 封装 SDK {@link TextEmbedding}（{@code /api/v1/services/embeddings/text-embedding/text-embedding}
 * 路由，与原手写客户端同一路由，协议零切换）。子批次并发分批 / text_index 对位重排 / 零向量
 * 兜底 / instruct 配套 query 等语义与手写实现保持一致（设计 §4.4）。
 * <p>
 * <b>分批归调用方</b>（官方 Java SDK 示例同构佐证）：batchSize 取候选
 * {@code params.batch-size}（qwen3.7-text-embedding=20，未声明默认 10），超出部分按
 * {@link ScopedTasks} 并发分批（{@value #MAX_CONCURRENCY} 并发），向量按
 * {@code text_index} 对位归位。
 * <p>
 * 仅实现 {@link EmbeddingCapable}（SPI 层）。Spring AI {@code EmbeddingModel} 视图由
 * {@link BailianSpringAiEmbeddingAdapter} 桥接。
 * <p>
 * <b>超时说明</b>：{@link TextEmbedding} facade 未暴露 ConnectionOptions 构造器，沿用 SDK
 * 默认超时（connect 120s / read 300s）；重试语义仍唯一归 Resilient 层（SDK HTTP 路径无内置
 * 重试，P0 源码核验）。
 */
public class BailianEmbeddingClient extends AbstractEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(BailianEmbeddingClient.class);
    /** 默认单请求行数上限：百炼同步接口 text-embedding-v3/v4 及 Qwen3-Embedding 系列均为 10 行 */
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MAX_CONCURRENCY = 4;
    private static final String QUERY_INSTRUCT =
        "Given a user question, retrieve the most relevant document passages";

    private final TextEmbedding textEmbedding;
    private final ScopedTasks scopedTasks;
    private final String apiKey;
    private final float[] zeroVector;
    /** 单请求行数上限，来自候选 params.batch-size（如 qwen3.7-text-embedding 官方上限 20） */
    private final int batchSize;

    /**
     * @param sdkBaseUrl SDK 形态 baseUrl（含 {@code /api/v1} 前缀）
     */
    public BailianEmbeddingClient(String sdkBaseUrl, String apiKey,
                                   ModelCandidate candidate, ScopedTasks scopedTasks) {
        this(apiKey, candidate, scopedTasks, new TextEmbedding(sdkBaseUrl));
    }

    /** 测试注入桩 facade 用 */
    BailianEmbeddingClient(String apiKey, ModelCandidate candidate,
                            ScopedTasks scopedTasks, TextEmbedding facade) {
        super(Objects.requireNonNull(candidate, "candidate must not be null"), candidate.provider());
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        Objects.requireNonNull(scopedTasks, "scopedTasks must not be null");
        Objects.requireNonNull(facade, "facade must not be null");
        this.textEmbedding = facade;
        this.scopedTasks = scopedTasks;
        this.apiKey = apiKey;
        this.zeroVector = new float[candidate.dimension()];
        this.batchSize = resolveBatchSize(candidate.params());

        log.info("BailianEmbeddingClient initialized: model={}, dimension={}, candidate={}, batchSize={}",
            candidate.model(), candidate.dimension(), candidate.id(), batchSize);
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
        TextEmbeddingParam.TextType textType = toTextType(type);
        float[][] batch = callApiBatch(List.of(text), textType, type == EmbeddingType.QUERY);
        return batch[0];
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        if (texts == null || texts.isEmpty()) return List.of();
        TextEmbeddingParam.TextType textType = toTextType(type);
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

    private float[][][] executeBatchesConcurrently(List<String> texts,
                                                    TextEmbeddingParam.TextType textType,
                                                    boolean withInstruct) {
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

    // ======================== DashScope Native API（SDK） ========================

    private float[][] callApiBatch(List<String> texts, TextEmbeddingParam.TextType textType,
                                    boolean withInstruct) {
        TextEmbeddingResult result;
        try {
            result = textEmbedding.call(buildParam(texts, textType, withInstruct));
        } catch (ApiException | NoApiKeyException e) {
            throw BailianChatClient.translate("DashScope Embedding", e);
        }
        return extractAll(result.getOutput());
    }

    private TextEmbeddingParam buildParam(List<String> texts, TextEmbeddingParam.TextType textType,
                                           boolean withInstruct) {
        TextEmbeddingParam.TextEmbeddingParamBuilder<?, ?> b = TextEmbeddingParam.builder()
            .apiKey(apiKey)
            .model(candidate.model())
            .texts(texts)
            .textType(textType)
            .dimension(candidate.dimension())
            .outputType(TextEmbeddingParam.OutputType.DENSE);
        if (withInstruct) {
            b.instruct(QUERY_INSTRUCT);
        }
        return b.build();
    }

    /**
     * 解析批量响应。
     * <p>
     * 官方响应契约：{@code embeddings[i].text_index} 为该向量对应输入数组的索引值（正常情况与
     * 数组顺序一致）。按 {@code text_index} 归位并对越界/缺失做防御回退，避免乱序返回时向量与
     * 文本错配——对向量库是静默数据损坏。
     */
    float[][] extractAll(TextEmbeddingOutput output) {
        List<TextEmbeddingResultItem> embeddings = output != null ? output.getEmbeddings() : null;
        if (embeddings == null || embeddings.isEmpty()) throw emptyResponse();

        float[][] result = new float[embeddings.size()][];
        for (int i = 0; i < embeddings.size(); i++) {
            TextEmbeddingResultItem item = embeddings.get(i);
            int idx = item.getTextIndex() != null ? item.getTextIndex() : i;
            if (idx < 0 || idx >= result.length) idx = i;
            result[idx] = toFloatArray(item.getEmbedding());
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
     * 调用方可能修改返回的数组（如写入 PgVector 失败后清零），若不拷贝会污染实例字段
     * {@link #zeroVector}，影响后续调用。
     */
    private float[] getZeroVector() {
        return zeroVector.clone();
    }

    private static TextEmbeddingParam.TextType toTextType(EmbeddingType type) {
        return type == EmbeddingType.DOCUMENT
            ? TextEmbeddingParam.TextType.DOCUMENT : TextEmbeddingParam.TextType.QUERY;
    }

    private static float[] toFloatArray(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            throw new RemoteException(RemoteErrorCode.LLM_RESPONSE_PARSE_ERROR,
                "Expected non-empty embedding vector");
        }
        float[] floats = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            floats[i] = embedding.get(i).floatValue();
        }
        return floats;
    }

    private static RemoteException emptyResponse() {
        return new RemoteException(RemoteErrorCode.LLM_RESPONSE_PARSE_ERROR,
            "DashScope embedding API returned empty response");
    }

    @Override
    public void close() {
        // SDK facade 无 close API（OkHttp daemon 线程自回收），无资源需显式释放
    }
}
