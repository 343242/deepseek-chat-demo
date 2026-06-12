package com.smart.rag.infrastructure.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 重试配置 — 对应 YAML {@code app.llm.resilience.retry}
 * <p>
 * 所有 LLM 操作共用同一套重试参数。按能力类型可选覆盖（Phase 3）。
 */
@ConfigurationProperties(prefix = "app.llm.resilience.retry")
public record RetryConfig(
    /** 最大重试次数（含首次调用） */
    Integer maxAttempts,
    /** 退避基础延迟（毫秒） */
    Long baseDelayMs,
    /** 退避最大延迟（毫秒） */
    Long maxDelayMs,
    /** 退避乘数 */
    Double multiplier
) {

    public RetryConfig {
        if (maxAttempts == null || maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (baseDelayMs == null || baseDelayMs <= 0) {
            baseDelayMs = 500L;
        }
        if (maxDelayMs == null || maxDelayMs <= 0) {
            maxDelayMs = 5000L;
        }
        if (multiplier == null || multiplier <= 0) {
            multiplier = 2.0;
        }
    }

    public int effectiveMaxAttempts() { return maxAttempts; }
    public long effectiveBaseDelayMs() { return baseDelayMs; }
    public long effectiveMaxDelayMs() { return maxDelayMs; }
    public double effectiveMultiplier() { return multiplier; }
}
