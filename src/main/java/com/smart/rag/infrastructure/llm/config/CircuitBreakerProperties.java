package com.smart.rag.infrastructure.llm.config;

/**
 * 熔断器配置 — 对应 YAML {@code app.llm.resilience.circuit-breaker}
 */
public record CircuitBreakerProperties(
    /** 连续失败次数阈值，达到后熔断器打开 */
    Integer failureThreshold,
    /** 熔断器打开持续时间（毫秒） */
    Long openDurationMs,
    /** 半开状态下允许的最大探测调用数 */
    Integer halfOpenMaxCalls
) {
    public CircuitBreakerProperties {
        if (failureThreshold == null || failureThreshold <= 0) failureThreshold = 5;
        if (openDurationMs == null || openDurationMs <= 0) openDurationMs = 30000L;
        if (halfOpenMaxCalls == null || halfOpenMaxCalls <= 0) halfOpenMaxCalls = 2;
    }

    public int effectiveFailureThreshold() { return failureThreshold; }
    public long effectiveOpenDurationMs() { return openDurationMs; }
    public int effectiveHalfOpenMaxCalls() { return halfOpenMaxCalls; }
}
