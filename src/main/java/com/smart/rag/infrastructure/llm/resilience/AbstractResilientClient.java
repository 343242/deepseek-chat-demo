package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.springframework.lang.Nullable;

/**
 * AbstractResilientClient — 弹性装饰器公共基类
 * <p>
 * 消除三个 Resilient 装饰器中 {@code CapabilityClient} 委托的 DRY 违反。
 * 泛型 {@code <T>} 约束为 {@code CapabilityClient} 的子接口（如 {@code ChatCapable}），
 * 子类通过 {@code extends AbstractResilientClient<ChatCapable>} 获得统一的委托实现。
 */
abstract class AbstractResilientClient<T extends CapabilityClient> implements CapabilityClient {

    protected final T delegate;
    protected final CircuitBreaker circuitBreaker;
    protected final RetryPolicy retryPolicy;
    @Nullable
    protected final LlmMetrics metrics;

    protected AbstractResilientClient(T delegate, CircuitBreaker circuitBreaker, RetryPolicy retryPolicy, @Nullable LlmMetrics metrics) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
    }

    @Override public String candidateId() { return delegate.candidateId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelName() { return delegate.modelName(); }
    @Override public LlmCapability capability() { return delegate.capability(); }
    @Override public boolean isAvailable() {
        return delegate.isAvailable()
            && circuitBreaker.getState() != CircuitBreakerState.OPEN;
    }

    @Override public void close() { delegate.close(); }
}
