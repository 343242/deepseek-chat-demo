package com.smart.rag.usage.dto;

/**
 * 用量分组聚合 DTO — GET /api/usage/stats
 *
 * @param groupKey 聚合键：dim=MODEL 时为 model_id，dim=SCENE 时为场景名，dim=USER 时为 userId 字符串
 */
public record UsageStatsDTO(
    String groupKey,
    long requestCount,
    double successRate,
    long totalPromptTokens,
    long totalCompletionTokens,
    long totalTokens,
    double avgDurationMs
) {
}
