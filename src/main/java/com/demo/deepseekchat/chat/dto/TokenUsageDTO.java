package com.demo.deepseekchat.chat.dto;

import java.time.LocalDateTime;

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
    LocalDateTime createdAt
) {}
