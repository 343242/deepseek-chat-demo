package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.ModelCircuitOpenException;
import com.smart.rag.infrastructure.fallback.ProbeTimeoutException;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.function.Supplier;

/**
 * 熔断器适配器 — 包装已有的 {@link ModelCircuitBreakerRegistry}
 * <p>
 * 复用已有三态熔断器实现（CLOSED → OPEN → HALF_OPEN），
 * 新增 {@code execute()} / {@code executeStream()} 高层方法，
 * 供 Resilient 装饰器统一调用。
 * <p>
 * <b>异常过滤</b>：仅将基础设施异常（{@link FallbackEligibility#isEligible} 返回 true）
 * 计为熔断失败。用户错误（ContentFilteredException 等）不触发熔断计数。
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final ModelCircuitBreakerRegistry registry;
    private final FallbackEligibility fallbackEligibility;
    private final String candidateId;

    public CircuitBreaker(ModelCircuitBreakerRegistry registry,
                          FallbackEligibility fallbackEligibility,
                          String candidateId,
                          @Nullable LlmMetrics metrics) {
        this.registry = registry;
        this.fallbackEligibility = fallbackEligibility;
        this.candidateId = candidateId;
        if (metrics != null) {
            metrics.registerCircuitBreakerGauge(candidateId, this::getState);
        }
    }

    /**
     * 阻塞式执行（带熔断保护）
     * <p>
     * OPEN → 抛出 {@link ModelCircuitOpenException}
     * HALF_OPEN → 放行（由已有 halfOpenMaxProbes 控制并发数）
     */
    public <T> T execute(RetryPolicy.CheckedSupplier<T> action) throws Exception {
        if (!registry.isCallAllowed(candidateId)) {
            throw new ModelCircuitOpenException(candidateId);
        }
        try {
            T result = action.get();
            registry.recordSuccess(candidateId);
            return result;
        } catch (Exception e) {
            if (fallbackEligibility.isEligible(e)) {
                registry.recordFailure(candidateId);
            }
            throw e;
        }
    }

    /**
     * 流式执行（带熔断保护）
     * <p>
     * 订阅时检查状态，流结束时记录成功，异常时按可降级性记录失败。
     * 首包超时由 {@link ProbeHandler} 施加，本方法不做首包超时检测。
     */
    public <T> Flux<T> executeStream(Supplier<Flux<T>> streamSupplier) {
        if (!registry.isCallAllowed(candidateId)) {
            return Flux.error(new ModelCircuitOpenException(candidateId));
        }
        return Flux.defer(streamSupplier)
            .doOnComplete(() -> {
                registry.recordSuccess(candidateId);
                registry.releaseProbe(candidateId);
            })
            .doOnError(e -> {
                if (!(e instanceof ProbeTimeoutException) && isInfraFailure(e)) {
                    registry.recordFailure(candidateId);
                }
                registry.releaseProbe(candidateId);
            })
            .doOnCancel(() -> registry.releaseProbe(candidateId));
    }

    /**
     * 判断异常是否为基础设施异常（可触发熔断计数）
     * <p>
     * 排除 {@link ProbeTimeoutException}（已由 ProbeStreamHandler 处理）。
     */
    private boolean isInfraFailure(Throwable e) {
        if (e instanceof ProbeTimeoutException) {
            return false;
        }
        return fallbackEligibility.isEligible(e);
    }

    /** 当前状态（委托给已有实现） */
    public CircuitBreakerState getState() {
        return registry.stateOf(candidateId);
    }

    /**
     * 探测成功回调 — 仅在 HALF_OPEN 状态下记录成功（触发 HALF_OPEN → CLOSED 转换）。
     * <p>
     * 在 CLOSED 状态下为空操作（no-op），不影响熔断计数。
     */
    public void recordProbeSuccess() {
        if (registry.tryRecoverFromHalfOpen(candidateId)) {
            log.info("Circuit breaker for '{}' recovered: HALF_OPEN → CLOSED (probe success)", candidateId);
        }
    }
}
