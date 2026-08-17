package com.smart.rag.usage.dto;

import java.time.OffsetDateTime;

/**
 * 用量时间桶 DTO — GET /api/usage/timeline（generate_series 补零桶，供图表直连）
 */
public record UsageTimelinePointDTO(
    OffsetDateTime bucket,
    long requestCount,
    long totalPromptTokens,
    long totalCompletionTokens,
    long totalTokens
) {
}
