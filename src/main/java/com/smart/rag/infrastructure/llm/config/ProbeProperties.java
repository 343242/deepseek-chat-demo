package com.smart.rag.infrastructure.llm.config;

/**
 * 探测配置 — 对应 YAML {@code app.llm.resilience.probe}
 */
public record ProbeProperties(
    /** 首包超时时间（毫秒） */
    Long probeTimeoutMs,
    /** 是否启用探测 */
    Boolean enabled
) {
    public ProbeProperties {
        if (probeTimeoutMs == null || probeTimeoutMs <= 0) probeTimeoutMs = 3000L;
        if (enabled == null) enabled = true;
    }

    public long effectiveProbeTimeoutMs() { return probeTimeoutMs; }
    public boolean effectiveEnabled() { return enabled; }
}
