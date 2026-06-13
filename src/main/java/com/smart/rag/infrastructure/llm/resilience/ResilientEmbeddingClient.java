package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.springframework.lang.Nullable;

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
                                      RetryPolicy retryPolicy,
                                      @Nullable LlmMetrics metrics) {
        super(delegate, circuitBreaker, retryPolicy, metrics);
    }

    @Override
    public float[] embed(String text, EmbeddingType type) {
        return executeResilient(
            () -> delegate.embed(text, type),
            (cid, start) -> metrics.recordEmbedLatency(cid, start, "success"),
            (cid, start) -> metrics.recordEmbedLatency(cid, start, "error")
        );
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        return executeResilient(
            () -> delegate.embedBatch(texts, type),
            (cid, start) -> metrics.recordEmbedLatency(cid, start, "success"),
            (cid, start) -> metrics.recordEmbedLatency(cid, start, "error")
        );
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }
}
