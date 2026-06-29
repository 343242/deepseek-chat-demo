package com.smart.rag.infrastructure.fallback;

/**
 * 熔断器配置 — 通用弹性配置 record。
 * <p>
 * LLM（{@code app.llm.resilience.circuit-breaker}）、MCP（{@code mcp.resilience.circuit-breaker}）
 * 等所有远程调用域共用。2026-06-29 从 {@code infrastructure.llm.config} 迁至
 * {@code infrastructure.fallback}（通用弹性包），使非 LLM 域可复用而无需依赖 {@code llm.config} 包。
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
