package com.smart.rag.usage.dto;

import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * 用量明细 DTO
 */
public record UsageEventDTO(
    String eventId,
    Long userId,
    String scene,
    @Nullable String conversationId,
    String modelId,
    @Nullable Long promptTokens,
    @Nullable Long completionTokens,
    @Nullable Long totalTokens,
    boolean estimated,
    boolean success,
    long durationMs,
    OffsetDateTime createdAt
) {
}
