package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import org.springframework.lang.Nullable;

/**
 * 重试配置 — 对应 YAML {@code app.llm.resilience.retry}
 * <p>
 * 所有 LLM 操作共用同一套重试参数。按能力类型可选覆盖。
 * <p>
 * <b>字段 nullability</b>：所有字段均 nullable。compact constructor 仅做范围校验
 * （非 null 字段必须为正值），<b>不</b>替换 null 为默认值。默认值在 {@link #effectiveMaxAttempts()}
 * 等方法被调用时按需返回，以支持 {@link #mergeWithOverride(RetryConfig)} 正确判断
 * "用户未设置"与"用户设置为默认值"两种语义。
 * <p>
 * 不需要独立的 @ConfigurationProperties 注解——作为 ResilienceConfig 的嵌套属性，
 * 由 {@link ResilienceConfig} 统一绑定。
 */
public record RetryConfig(
    /** 最大重试次数（含首次调用）；null 表示未配置，使用 {@link #effectiveMaxAttempts()} 默认值 3 */
    @Nullable Integer maxAttempts,
    /** 退避基础延迟（毫秒）；null 表示未配置，默认 500ms */
    @Nullable Long baseDelayMs,
    /** 退避最大延迟（毫秒）；null 表示未配置，默认 5000ms */
    @Nullable Long maxDelayMs,
    /** 退避乘数；null 表示未配置，默认 2.0 */
    @Nullable Double multiplier
) {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BASE_DELAY_MS = 500L;
    private static final long DEFAULT_MAX_DELAY_MS = 5000L;
    private static final double DEFAULT_MULTIPLIER = 2.0;

    public RetryConfig {
        if (maxAttempts != null && maxAttempts <= 0) {
            throw new ClientException(ClientErrorCode.VALIDATION_ERROR,
                "maxAttempts 必须为正值，实际为 " + maxAttempts);
        }
        if (baseDelayMs != null && baseDelayMs <= 0) {
            throw new ClientException(ClientErrorCode.VALIDATION_ERROR,
                "baseDelayMs 必须为正值，实际为 " + baseDelayMs);
        }
        if (maxDelayMs != null && maxDelayMs <= 0) {
            throw new ClientException(ClientErrorCode.VALIDATION_ERROR,
                "maxDelayMs 必须为正值，实际为 " + maxDelayMs);
        }
        if (multiplier != null && multiplier <= 0) {
            throw new ClientException(ClientErrorCode.VALIDATION_ERROR,
                "multiplier 必须为正值，实际为 " + multiplier);
        }
    }

    /** 返回实际生效的最大重试次数（null 时使用默认值 3） */
    public int effectiveMaxAttempts() { return maxAttempts != null ? maxAttempts : DEFAULT_MAX_ATTEMPTS; }

    /** 返回实际生效的退避基础延迟（null 时使用默认值 500ms） */
    public long effectiveBaseDelayMs() { return baseDelayMs != null ? baseDelayMs : DEFAULT_BASE_DELAY_MS; }

    /** 返回实际生效的退避最大延迟（null 时使用默认值 5000ms） */
    public long effectiveMaxDelayMs() { return maxDelayMs != null ? maxDelayMs : DEFAULT_MAX_DELAY_MS; }

    /** 返回实际生效的退避乘数（null 时使用默认值 2.0） */
    public double effectiveMultiplier() { return multiplier != null ? multiplier : DEFAULT_MULTIPLIER; }

    /**
     * 将 override 中的非 null 字段合并到本配置，生成新实例。
     * <p>
     * 用于按能力覆盖重试参数（如 embedding 调用使用更长超时）。compact constructor 不替换 null，
     * 因此 override 中未显式设置的字段（仍为 null）会正确回落到 base 配置的对应值。
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
