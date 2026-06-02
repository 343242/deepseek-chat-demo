package com.smart.rag.infrastructure.fallback.probe;

/**
 * 探测结果
 *
 * @param success    是否成功（首包到达）
 * @param modelId    探测的模型 ID
 * @param latencyMs  首包延迟毫秒数，失败时为 -1
 */
public record ProbeResult(
        boolean success,
        String modelId,
        long latencyMs
) {
    public static ProbeResult success(String modelId, long latencyMs) {
        return new ProbeResult(true, modelId, latencyMs);
    }

    public static ProbeResult failure(String modelId) {
        return new ProbeResult(false, modelId, -1);
    }
}
