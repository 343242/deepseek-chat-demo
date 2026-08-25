package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;

/**
 * 429 限流异常（design llm-resilience-optimization WS1）
 * <p>
 * 携带服务端 {@code Retry-After} 指示的等待毫秒数（秒数或 HTTP-date 两形态，解析失败为 null）。
 * {@code retryAfterMs <= 60s}：重试时原样等待（不叠 jitter、不受 maxDelayMs 约束）；
 * {@code > 60s}：服务端明示长时间限流，RetryPolicy 判定不可同模型重试、直接跨模型降级。
 */
public class RateLimitedException extends RemoteException {

    /** Retry-After 等待上限：超过即视为服务端明示长时间限流，放弃同模型重试（决策 15） */
    public static final long ABANDON_THRESHOLD_MS = 60_000L;

    private final Long retryAfterMs;

    public RateLimitedException(String detail, Long retryAfterMs) {
        super(RemoteErrorCode.LLM_RATE_LIMITED, detail);
        this.retryAfterMs = retryAfterMs;
    }

    /** 服务端 Retry-After 指示的等待毫秒数；缺失/不可解析时为 null */
    public Long retryAfterMs() {
        return retryAfterMs;
    }

    /** 是否应放弃同模型重试（Retry-After > 60s） */
    public boolean shouldAbandonRetry() {
        return retryAfterMs != null && retryAfterMs > ABANDON_THRESHOLD_MS;
    }
}
