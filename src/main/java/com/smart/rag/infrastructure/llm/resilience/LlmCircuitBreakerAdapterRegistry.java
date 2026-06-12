package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
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

    private final ModelCircuitBreakerRegistry delegate;
    private final FallbackEligibility fallbackEligibility;
    private final ConcurrentHashMap<String, CircuitBreaker> adapters = new ConcurrentHashMap<>();

    public LlmCircuitBreakerAdapterRegistry(ModelCircuitBreakerRegistry delegate,
                                            FallbackEligibility fallbackEligibility) {
        this.delegate = delegate;
        this.fallbackEligibility = fallbackEligibility;
    }

    /** 获取或创建指定 candidateId 的熔断器适配器 */
    public CircuitBreaker getOrCreate(String candidateId) {
        return adapters.computeIfAbsent(candidateId,
            id -> new CircuitBreaker(delegate, fallbackEligibility, id));
    }
}
