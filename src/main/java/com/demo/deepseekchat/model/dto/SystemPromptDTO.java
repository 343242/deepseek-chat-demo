package com.demo.deepseekchat.model.dto;

import java.time.LocalDateTime;

/**
 * System Prompt 配置 DTO
 */
public record SystemPromptDTO(
    String modelId,
    String promptText,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
