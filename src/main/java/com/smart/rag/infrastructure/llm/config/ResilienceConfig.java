package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.llm.LlmCapability;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 弹性配置聚合 — 对应 YAML {@code app.llm.resilience}
 * <p>
 * 聚合重试、熔断、探测三层配置，支持按能力覆盖重试参数。
 */
public record ResilienceConfig(
    /** 基础重试配置 */
    @Nullable RetryConfig retry,

    /** 熔断器配置 */
    @Nullable CircuitBreakerProperties circuitBreaker,

    /** 探测配置 */
    @Nullable ProbeProperties probe,

    /** 按能力覆盖重试参数（key 为能力名小写） */
    @Nullable Map<String, RetryConfig> retryOverrides
) {

    /** 获取重试配置（带按能力覆盖合并） */
    public RetryConfig resolveRetryConfig(LlmCapability capability) {
        RetryConfig base = retry != null ? retry : new RetryConfig(null, null, null, null);
        if (retryOverrides == null) {
            return base;
        }
        RetryConfig override = retryOverrides.get(capability.name().toLowerCase());
        return override != null ? base.mergeWithOverride(override) : base;
    }

    /** 获取熔断器配置（null-safe） */
    public CircuitBreakerProperties resolveCircuitBreaker() {
        return circuitBreaker != null ? circuitBreaker : new CircuitBreakerProperties(null, null, null);
    }

    /** 获取探测配置（null-safe） */
    public ProbeProperties resolveProbe() {
        return probe != null ? probe : new ProbeProperties(null, null);
    }
}
