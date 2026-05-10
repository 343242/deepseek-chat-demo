package com.demo.chat.rag.embedding;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于阿里千问 text-embedding-v4 的 Embedding 模型实现。
 * <p>
 * 通过 DashScope OpenAI 兼容 API (/v1/embeddings) 调用，
 * 实现 Spring AI 的 {@link EmbeddingModel} 接口，
 * 使其可被 PgVectorStore 等组件自动注入使用。
 * </p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>策略模式：通过 EmbeddingModel 接口解耦，未来可替换为其他实现</li>
 *   <li>封装：DashScope API 细节不泄漏到上层</li>
 *   <li>批量分片：DashScope 单次最多 10 条输入，自动分批</li>
 * </ul>
 */
@Component
public class DashScopeEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingModel.class);

    /** DashScope 单次请求最大输入条数 */
    private static final int MAX_BATCH_SIZE = 10;

    private final DashScopeEmbeddingProperties properties;
    private final WebClient webClient;

    public DashScopeEmbeddingModel(DashScopeEmbeddingProperties properties,
                                   WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("DashScopeEmbeddingModel initialized: model={}, dimensions={}, baseUrl={}",
                properties.getModel(), properties.getDimensions(), properties.getBaseUrl());
    }

    @Override
    public @NonNull EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        log.debug("Embedding {} texts via DashScope", texts.size());

        List<Embedding> allEmbeddings = new ArrayList<>();

        // 分批调用（DashScope 限制单次最多 10 条）
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            List<Embedding> batchResult = callBatch(batch);
            allEmbeddings.addAll(batchResult);
        }

        return new EmbeddingResponse(allEmbeddings);
    }

    @Override
    public float @NonNull [] embed(Document document) {
        String content = document.getText();
        float[] result = null;
        if (content != null) {
            result = embed(content);
        }
        return result;
    }

    @Override
    public float @NonNull [] embed(@NonNull String text) {
        EmbeddingResponse response = call(new EmbeddingRequest(List.of(text), null));
        return response.getResult().getOutput();
    }

    @Override
    public @NonNull List<float[]> embed(@NonNull List<String> texts) {
        EmbeddingResponse response = call(new EmbeddingRequest(texts, null));
        return response.getResults().stream()
                .map(Embedding::getOutput)
                .toList();
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    /**
     * 单批次调用 DashScope API
     */
    private List<Embedding> callBatch(List<String> texts) {
        DashScopeEmbeddingApi.Request requestBody = new DashScopeEmbeddingApi.Request(
                properties.getModel(),
                texts,
                properties.getDimensions()
        );

        DashScopeEmbeddingApi.Response response = webClient.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(DashScopeEmbeddingApi.Response.class)
                .block();

        if (response == null || response.getData() == null) {
            throw new RuntimeException("DashScope embedding API returned null response");
        }

        return response.getData().stream()
                .map(data -> toFloatArray(data.getEmbedding()))
                .map(embedding -> new Embedding(embedding, null))
                .toList();
    }

    /**
     * List<Double> → float[]
     */
    private static float[] toFloatArray(List<Double> doubles) {
        float[] floats = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            floats[i] = doubles.get(i).floatValue();
        }
        return floats;
    }
}
