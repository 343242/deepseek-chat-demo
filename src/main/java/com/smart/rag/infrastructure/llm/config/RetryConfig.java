package com.smart.rag.infrastructure.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.Nullable;

/**
 * 重试配置 — 对应 YAML {@code app.llm.resilience.retry}
 * <p>
 * 所有 LLM 操作共用同一套重试参数。按能力类型可选覆盖。
 * 字段 nullable 以支持 mergeWithOverride 合并语义。
 */
@ConfigurationProperties(prefix = "app.llm.resilience.retry")
public record RetryConfig(
    /** 最大重试次数（含首次调用） */
    @Nullable Integer maxAttempts,
    /** 退避基础延迟（毫秒） */
    @Nullable Long baseDelayMs,
    /** 退避最大延迟（毫秒） */
    @Nullable Long maxDelayMs,
    /** 退避乘数 */
    @Nullable Double multiplier
) {

    public RetryConfig {
        if (maxAttempts == null || maxAttempts <= 0) maxAttempts = 3;
        if (baseDelayMs == null || baseDelayMs <= 0) baseDelayMs = 500L;
        if (maxDelayMs == null || maxDelayMs <= 0) maxDelayMs = 5000L;
        if (multiplier == null || multiplier <= 0) multiplier = 2.0;
    }

    public int effectiveMaxAttempts() { return maxAttempts; }
    public long effectiveBaseDelayMs() { return baseDelayMs; }
    public long effectiveMaxDelayMs() { return maxDelayMs; }
    public double effectiveMultiplier() { return multiplier; }

    /**
     * 将 override 中的非 null 字段合并到本配置，生成新实例。
     * <p>
     * 用于按能力覆盖重试参数（如 embedding 调用使用更长超时）。
     */
    public RetryConfig mergeWithOverride(RetryConfig override) {
        return new RetryConfig(
            override.maxAttempts != null ? override.maxAttempts : this.maxAttempts,
            override.baseDelayMs != null ? override.baseDelayMs : this.baseDelayMs,
            override.maxDelayMs != null ? override.maxDelayMs : this.maxDelayMs,
            override.multiplier != null ? override.multiplier : this.multiplier
        );
    }
}
