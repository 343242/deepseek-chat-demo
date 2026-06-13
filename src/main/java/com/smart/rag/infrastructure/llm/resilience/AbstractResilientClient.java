package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.springframework.lang.Nullable;

import java.util.function.BiConsumer;

/**
 * AbstractResilientClient — 弹性装饰器公共基类
 * <p>
 * 消除三个 Resilient 装饰器中 {@code CapabilityClient} 委托的 DRY 违反。
 * 泛型 {@code <T>} 约束为 {@code CapabilityClient} 的子接口（如 {@code ChatCapable}），
 * 子类通过 {@code extends AbstractResilientClient<ChatCapable>} 获得统一的委托实现。
 */
public abstract class AbstractResilientClient<T extends CapabilityClient> implements CapabilityClient {

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
    /**
     * Advisory availability check (best-effort).
     * <p>
     * Returns {@code true} when the delegate reports available <b>and</b> the
     * circuit breaker is not in OPEN state. However, the actual operation may
     * still fail (e.g., transient network error, circuit state transitions
     * between the check and the call). Callers must still handle exceptions
     * from the underlying operation — this method is useful for pre-filtering
     * or UI indicators but should not be used as a guarantee of success.
     */
    @Override public boolean isAvailable() {
        return delegate.isAvailable()
            && circuitBreaker.getState() != CircuitBreakerState.OPEN;
    }

    @Override public void close() { delegate.close(); }

    /** 返回被包装的底层客户端，用于需要访问原始接口（如 EmbeddingModel）的场景 */
    public T getDelegate() { return delegate; }

    /**
     * Template method for executing a resilient action with metrics recording.
     * <p>
     * Wraps the action with retry + circuit-breaker, records latency on success
     * via {@code successRecorder}, and records error latency on failure.
     * Preserves the exact same exception-wrapping behavior as the original
     * per-method implementations.
     *
     * @param action           the operation to execute (with retry + circuit-breaker)
     * @param successRecorder  called on success with (candidateId, startNanos);
     *                         typically delegates to a specific metrics method
     * @param errorRecorder    called on failure with (candidateId, startNanos)
     * @param <R>              return type of the action
     * @return result of the action
     */
    protected <R> R executeResilient(RetryPolicy.CheckedSupplier<R> action,
                                      BiConsumer<String, Long> successRecorder,
                                      BiConsumer<String, Long> errorRecorder) {
        long start = metrics != null ? metrics.startNanos() : 0;
        try {
            R result = circuitBreaker.execute(() -> retryPolicy.executeWithBackoff(action));
            if (metrics != null) successRecorder.accept(candidateId(), start);
            return result;
        } catch (Exception e) {
            if (metrics != null) errorRecorder.accept(candidateId(), start);
            if (e instanceof RuntimeException re) throw re;
            // Checked exceptions from circuitBreaker.execute(...) are wrapped as RemoteException
            // to stay within AbstractException hierarchy so GlobalExceptionHandler handles them.
            throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
                "Unexpected checked exception from LLM action: " + e.getMessage(), e);
        }
    }
}
