package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
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
    @Nullable Map<String, RetryConfig> retryOverrides,

    /** 并发准入闸门全局默认（WS4；maxConcurrent=0 禁用） */
    @Nullable ConcurrencyConfig concurrency,

    /** 按能力覆盖并发闸门参数（key 为能力名小写，镜像 retryOverrides） */
    @Nullable Map<String, ConcurrencyConfig> concurrencyOverrides
) {

    /** Cached default instances — avoids creating new objects on every resolve call */
    private static final RetryConfig DEFAULT_RETRY = new RetryConfig(null, null, null, null);
    private static final CircuitBreakerProperties DEFAULT_CB = new CircuitBreakerProperties(null, null, null);
    private static final ProbeProperties DEFAULT_PROBE = new ProbeProperties(null);
    private static final ConcurrencyConfig DEFAULT_CONCURRENCY = new ConcurrencyConfig(null, null);

    /** 获取重试配置（带按能力覆盖合并） */
    public RetryConfig resolveRetryConfig(LlmCapability capability) {
        RetryConfig base = retry != null ? retry : DEFAULT_RETRY;
        if (retryOverrides == null) {
            return base;
        }
        RetryConfig override = retryOverrides.get(capability.name().toLowerCase());
        return override != null ? base.mergeWithOverride(override) : base;
    }

    /** 获取并发闸门配置（带按能力覆盖合并，WS4；仅作用于系统候选——决策 14） */
    public ConcurrencyConfig resolveConcurrencyConfig(LlmCapability capability) {
        ConcurrencyConfig base = concurrency != null ? concurrency : DEFAULT_CONCURRENCY;
        if (concurrencyOverrides == null) {
            return base;
        }
        ConcurrencyConfig override = concurrencyOverrides.get(capability.name().toLowerCase());
        return override != null ? base.mergeWithOverride(override) : base;
    }

    /** 获取熔断器配置（null-safe） */
    public CircuitBreakerProperties resolveCircuitBreaker() {
        return circuitBreaker != null ? circuitBreaker : DEFAULT_CB;
    }

    /** 获取探测配置（null-safe） */
    public ProbeProperties resolveProbe() {
        return probe != null ? probe : DEFAULT_PROBE;
    }
}
