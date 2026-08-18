package com.smart.rag.infrastructure.llm.config;

/**
 * 探测配置 — 对应 YAML {@code app.llm.resilience.probe}
 * <p>
 * 首包探测恒启用（无开关）；本配置仅承载超时参数。
 */
public record ProbeProperties(
    /** 首包超时时间（毫秒） */
    Long probeTimeoutMs
) {
    public ProbeProperties {
        if (probeTimeoutMs == null || probeTimeoutMs <= 0) probeTimeoutMs = 3000L;
    }

    public long effectiveProbeTimeoutMs() { return probeTimeoutMs; }
}
