package com.smart.rag.infrastructure.fallback.cache;

/**
 * 模型健康缓存条目
 *
 * @param modelId    模型 compositeId
 * @param status     健康状态
 * @param timestamp  探测时间戳（epoch millis）
 * @param latencyMs  探测延迟毫秒数，未知时为 -1
 */
public record HealthEntry(
        String modelId,
        HealthStatus status,
        long timestamp,
        long latencyMs
) {
    public boolean isHealthy() {
        return status == HealthStatus.HEALTHY;
    }
}
