package com.demo.chat.rag.embedding;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于阿里百炼 text-embedding-v4 的 Embedding 模型实现。
 * <p>
 * 通过 DashScope 原生 API 调用（非 OpenAI 兼容接口），
 * 支持 text_type、instruct 等高级参数。
 * </p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>策略模式：通过 EmbeddingModel 接口解耦，未来可替换为其他实现</li>
 *   <li>封装：DashScope API 细节不泄漏到上层</li>
 *   <li>场景识别：embed(Document) → document（入库），embed(String) → query（检索）</li>
 *   <li>批量分片：DashScope 单次最多 10 条输入，自动分批</li>
 *   <li>空文本防护：空/null 文本返回零向量，不调用外部 API</li>
 *   <li>超时保护：每次 API 调用有 30s 超时上限</li>
 * </ul>
 */
@Component
@Primary
public class DashScopeEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingModel.class);

    /** DashScope 单次请求最大输入条数 */
    private static final int MAX_BATCH_SIZE = 10;
    /** 单次 API 调用超时时间 */
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    /** DashScope 原生 Embedding 端点 */
    private static final String EMBEDDING_PATH =
            "/api/v1/services/embeddings/text-embedding/text-embedding";

    private final DashScopeEmbeddingProperties properties;
    private final WebClient webClient;

    /** 缓存的零向量（维度固定，复用避免重复创建） */
    private volatile float[] zeroVector;

    public DashScopeEmbeddingModel(DashScopeEmbeddingProperties properties,
                                   WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("DashScopeEmbeddingModel initialized: model={}, dimensions={}, baseUrl={}, " +
                 "textType={}, instruct='{}'",
                properties.getModel(), properties.getDimensions(), properties.getBaseUrl(),
                properties.getTextType(), properties.getInstruct());
    }

    // ======================== 场景识别入口 ========================

    /**
     * 入库场景：PgVectorStore.add() 调用此方法。
     * RAG 流程中对应文档向量化入库阶段。
     */
    @Override
    public float @NonNull [] embed(@NonNull Document document) {
        String content = document.getText();
        if (content == null || content.isBlank()) {
            log.debug("Embedding called with blank document content, returning zero vector");
            return getZeroVector();
        }
        return embedWithTextType(content, resolveTextType(TextType.DOCUMENT));
    }

    /**
     * 查询场景：PgVectorStore.similaritySearch() 调用此方法。
     * RAG 流程中对应用户提问向量化阶段。
     */
    @Override
    public float @NonNull [] embed(@NonNull String text) {
        if (text.isBlank()) {
            log.debug("Embedding called with blank text, returning zero vector");
            return getZeroVector();
        }
        return embedWithTextType(text, resolveTextType(TextType.QUERY));
    }

    // ======================== EmbeddingModel 接口实现 ========================

    @Override
    public @NonNull EmbeddingResponse call(@NonNull EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        // 直接调用 call 时默认使用 query 类型（用户主动调用通常用于检索）
        TextType textType = resolveTextType(TextType.QUERY);
        log.debug("Embedding {} texts via DashScope (textType={})", texts.size(), textType);

        List<Embedding> allEmbeddings = new ArrayList<>();

        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            List<Embedding> batchResult = callBatch(batch, textType);
            allEmbeddings.addAll(batchResult);
        }

        return new EmbeddingResponse(allEmbeddings);
    }

    @Override
    public @NonNull List<float[]> embed(@NonNull List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        TextType textType = resolveTextType(TextType.QUERY);
        log.debug("Batch embedding {} texts via DashScope (textType={})", texts.size(), textType);

        List<float[]> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            DashScopeEmbeddingApi.Response response = callApi(batch, textType);
            if (response != null && response.getOutput() != null) {
                for (DashScopeEmbeddingApi.EmbeddingData data : response.getOutput().getEmbeddings()) {
                    allEmbeddings.add(toFloatArray(data.getEmbedding()));
                }
            }
        }
        return allEmbeddings;
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    // ======================== 内部方法 ========================

    /**
     * 根据配置策略决定实际使用的 text_type。
     * <ul>
     *   <li>auto → 使用 inferredType（由调用方法决定）</li>
     *   <li>query/document → 强制使用配置值</li>
     *   <li>disabled → null（不传 text_type）</li>
     * </ul>
     */
    private TextType resolveTextType(TextType inferredType) {
        return switch (properties.getTextType()) {
            case QUERY, DOCUMENT -> properties.getTextType();  // 强制模式
            case DISABLED -> TextType.DISABLED;                 // 不传
            default -> inferredType;                            // auto 或 null，使用推断值
        };
    }

    /**
     * 携带 text_type 的单文本向量化。
     */
    private float[] embedWithTextType(String text, TextType textType) {
        DashScopeEmbeddingApi.Response response = callApi(List.of(text), textType);
        if (response == null || response.getOutput() == null
                || response.getOutput().getEmbeddings().isEmpty()) {
            throw new RuntimeException("DashScope embedding API returned empty response for text");
        }
        return toFloatArray(response.getOutput().getEmbeddings().get(0).getEmbedding());
    }

    /**
     * 单批次调用并转换为 Spring AI Embedding 对象。
     */
    private List<Embedding> callBatch(List<String> texts, TextType textType) {
        DashScopeEmbeddingApi.Response response = callApi(texts, textType);

        if (response == null || response.getOutput() == null) {
            throw new RuntimeException("DashScope embedding API returned null response");
        }

        return response.getOutput().getEmbeddings().stream()
                .map(data -> new Embedding(toFloatArray(data.getEmbedding()), data.getTextIndex()))
                .toList();
    }

    /**
     * 调用 DashScope 原生 Embedding API（含超时保护）。
     */
    private DashScopeEmbeddingApi.Response callApi(List<String> texts, TextType textType) {
        String effectiveInstruct = (textType == TextType.QUERY)
                ? properties.getInstruct() : null;

        DashScopeEmbeddingApi.Request request = new DashScopeEmbeddingApi.Request(
                properties.getModel(), texts, properties.getDimensions(),
                textType, effectiveInstruct
        );

        if (log.isDebugEnabled()) {
            log.debug("DashScope embedding request: model={}, texts={}, textType={}, instruct='{}'",
                    properties.getModel(), texts.size(), textType, effectiveInstruct);
        }

        DashScopeEmbeddingApi.Response response;
        try {
            response = webClient.post()
                    .uri(EMBEDDING_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(DashScopeEmbeddingApi.Response.class)
                    .timeout(API_TIMEOUT)
                    .block();
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("DashScope embedding API call failed (timeout=%s, batch=%d texts, textType=%s): %s",
                            API_TIMEOUT, texts.size(), textType, e.getMessage()), e);
        }

        if (response == null) {
            throw new RuntimeException("DashScope embedding API returned null response");
        }

        return response;
    }

    /**
     * 获取零向量（惰性初始化，线程安全）。
     */
    private float[] getZeroVector() {
        if (zeroVector == null) {
            synchronized (this) {
                if (zeroVector == null) {
                    zeroVector = new float[properties.getDimensions()];
                }
            }
        }
        return zeroVector;
    }

    /**
     * List&lt;Double&gt; → float[]
     */
    private static float[] toFloatArray(List<Double> doubles) {
        float[] floats = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            floats[i] = doubles.get(i).floatValue();
        }
        return floats;
    }
}
