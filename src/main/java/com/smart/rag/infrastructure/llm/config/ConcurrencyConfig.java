package com.smart.rag.infrastructure.llm.config;

import org.springframework.lang.Nullable;

/**
 * 并发准入闸门配置（design llm-resilience-optimization WS4）— 对应 YAML
 * {@code app.llm.resilience.concurrency} / {@code concurrency-overrides}。
 * <p>
 * 三级解析（优先级：candidate params.max-concurrent > capability override > global），
 * 解析模式镜像 {@link RetryConfig}。
 * <p>
 * 代码默认 {@code maxConcurrent=0}（禁用，回滚开关——yml 删 concurrency 块即禁用）、
 * {@code acquireTimeoutMs=1000}。<b>初值未经压测标定，上线必须走灰度流程</b>
 * （设计文档 §3.4.8：压测定值 → 单候选灰度 → 盯 llm.busy.rejected → 全量）。
 */
public record ConcurrencyConfig(
    /** 每 candidate 最大并发；0 或 null = 禁用闸门 */
    @Nullable Integer maxConcurrent,

    /** acquire 等待预算（毫秒）；超时抛 LLM_BUSY */
    @Nullable Long acquireTimeoutMs
) {

    private static final int DEFAULT_MAX_CONCURRENT = 0;
    private static final long DEFAULT_ACQUIRE_TIMEOUT_MS = 1000;

    public int effectiveMaxConcurrent() {
        return maxConcurrent != null ? maxConcurrent : DEFAULT_MAX_CONCURRENT;
    }

    public long effectiveAcquireTimeoutMs() {
        return acquireTimeoutMs != null ? acquireTimeoutMs : DEFAULT_ACQUIRE_TIMEOUT_MS;
    }

    /** capability override 合并：override 非空字段覆盖 base（镜像 RetryConfig.mergeWithOverride） */
    public ConcurrencyConfig mergeWithOverride(@Nullable ConcurrencyConfig override) {
        if (override == null) return this;
        return new ConcurrencyConfig(
            override.maxConcurrent != null ? override.maxConcurrent : maxConcurrent,
            override.acquireTimeoutMs != null ? override.acquireTimeoutMs : acquireTimeoutMs);
    }
}
