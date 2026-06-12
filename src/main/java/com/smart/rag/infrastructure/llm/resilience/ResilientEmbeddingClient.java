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
        long start = metrics != null ? metrics.startNanos() : 0;
        try {
            float[] result = circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    delegate.embed(text, type)
                )
            );
            if (metrics != null) metrics.recordEmbedLatency(candidateId(), start, "success");
            return result;
        } catch (Exception e) {
            if (metrics != null) metrics.recordEmbedLatency(candidateId(), start, "error");
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        long start = metrics != null ? metrics.startNanos() : 0;
        try {
            List<float[]> result = circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    delegate.embedBatch(texts, type)
                )
            );
            if (metrics != null) metrics.recordEmbedLatency(candidateId(), start, "success");
            return result;
        } catch (Exception e) {
            if (metrics != null) metrics.recordEmbedLatency(candidateId(), start, "error");
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }
}
