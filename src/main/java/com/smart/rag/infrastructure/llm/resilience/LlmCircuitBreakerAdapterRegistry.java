package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 candidateId 管理的熔断器注册表 — 包装已有 {@link ModelCircuitBreakerRegistry}
 * <p>
 * 不创建新的熔断器实例，而是为每个 candidateId 创建 {@link CircuitBreaker} 适配器，
 * 底层委托给已有的 {@link ModelCircuitBreakerRegistry}。
 */
@Component
public class LlmCircuitBreakerAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreakerAdapterRegistry.class);

    /** Warn if the adapter map exceeds this threshold — potential resource leak */
    private static final int WARN_SIZE_THRESHOLD = 100;

    private final ModelCircuitBreakerRegistry delegate;
    private final FallbackEligibility fallbackEligibility;
    @Nullable
    private final LlmMetrics metrics;
    private final ConcurrentHashMap<String, CircuitBreaker> adapters = new ConcurrentHashMap<>();

    public LlmCircuitBreakerAdapterRegistry(ModelCircuitBreakerRegistry delegate,
                                            FallbackEligibility fallbackEligibility,
                                            @Nullable LlmMetrics metrics) {
        this.delegate = delegate;
        this.fallbackEligibility = fallbackEligibility;
        this.metrics = metrics;
    }

    /** 获取或创建指定 candidateId 的熔断器适配器 */
    public CircuitBreaker getOrCreate(String candidateId) {
        CircuitBreaker adapter = adapters.computeIfAbsent(candidateId,
            id -> new CircuitBreaker(delegate, fallbackEligibility, id, metrics));
        if (adapters.size() > WARN_SIZE_THRESHOLD) {
            log.warn("Circuit breaker adapter map has {} entries (threshold {}), possible resource leak",
                adapters.size(), WARN_SIZE_THRESHOLD);
        }
        return adapter;
    }

    /** Remove the adapter for a given candidateId (use when a candidate is unregistered) */
    public void evict(String candidateId) {
        adapters.remove(candidateId);
        log.info("Evicted circuit breaker adapter for candidate '{}'", candidateId);
    }
}
