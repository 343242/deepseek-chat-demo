package com.smart.rag.usage.dto;

/**
 * 用量总计 DTO — GET /api/usage/summary
 */
public record UsageSummaryDTO(
    long requestCount,
    long successCount,
    double successRate,
    long totalPromptTokens,
    long totalCompletionTokens,
    long totalTokens,
    double avgDurationMs,
    long maxDurationMs
) {
}
