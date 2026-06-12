package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.llm.*;
import java.util.List;

/**
 * Rerank 客户端抽象基类
 * <p>
 * 子类只需实现 {@link #rerank(RerankRequest)}。
 * 带 topN 截断的重排序有默认实现。
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientRerankClient} 装饰器在外部施加。
 */
public abstract class AbstractRerankClient implements RerankCapable {

    protected final ModelCandidate candidate;
    protected final String providerId;

    protected AbstractRerankClient(ModelCandidate candidate, String providerId) {
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
    public abstract List<RerankResult> rerank(RerankRequest request);

    @Override
    public List<RerankResult> rerank(RerankRequest request, int topN) {
        return rerank(request).stream()
            .limit(topN)
            .toList();
    }
}
