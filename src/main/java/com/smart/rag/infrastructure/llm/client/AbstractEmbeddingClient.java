package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.llm.*;
import java.util.List;

/**
 * Embedding 客户端抽象基类
 * <p>
 * 子类只需实现 {@link #embed} 和 {@link #dimension}，
 * 批量嵌入的默认实现为逐条调用，子类可覆写为批量 API 以提升性能。
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientEmbeddingClient} 装饰器在外部施加。
 */
public abstract class AbstractEmbeddingClient implements EmbeddingCapable {

    protected final ModelCandidate candidate;
    protected final String providerId;

    protected AbstractEmbeddingClient(ModelCandidate candidate, String providerId) {
        this.candidate = candidate;
        this.providerId = providerId;
    }

    @Override
    public final String candidateId() { return candidate.id(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelName() { return candidate.model(); }

    @Override
    public final LlmCapability capability() { return candidate.capability(); }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public abstract float[] embed(String text, EmbeddingType type);

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        return texts.stream()
            .map(text -> embed(text, type))
            .toList();
    }

    @Override
    public abstract int dimension();
}
