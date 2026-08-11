package com.smart.rag.chat.dto;

import java.time.OffsetDateTime;

/**
 * 单次 Token 用量 DTO
 */
public record TokenUsageDTO(
    String conversationId,
    String modelId,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    long durationMs,
    OffsetDateTime createdAt
) {}
