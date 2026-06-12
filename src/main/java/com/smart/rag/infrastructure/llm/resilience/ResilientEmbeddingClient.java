package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.EmbeddingType;

import java.util.List;

/**
 * ResilientEmbeddingClient — Embedding 能力的弹性装饰器
 *
 * 策略矩阵：
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ embed        │ 指数退避重试   │ ✓            │ ✗            │
 * │ embedBatch   │ 指数退避重试   │ ✓            │ ✗            │
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 */
public class ResilientEmbeddingClient extends AbstractResilientClient<EmbeddingCapable> implements EmbeddingCapable {

    public ResilientEmbeddingClient(EmbeddingCapable delegate,
                                      CircuitBreaker circuitBreaker,
                                      RetryPolicy retryPolicy) {
        super(delegate, circuitBreaker, retryPolicy);
    }

    @Override
    public float[] embed(String text, EmbeddingType type) {
        try {
            return circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    delegate.embed(text, type)
                )
            );
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        try {
            return circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    delegate.embedBatch(texts, type)
                )
            );
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }
}
