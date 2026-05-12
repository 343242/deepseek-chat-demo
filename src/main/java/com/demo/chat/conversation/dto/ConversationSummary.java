package com.demo.chat.conversation.dto;

import java.time.LocalDateTime;

/**
 * 会话摘要 DTO（用于列表展示）
 */
public record ConversationSummary(
    Long id,
    String conversationId,
    String title,
    String titleSource,
    String modelId,
    boolean pinned,
    String status,
    int messageCount,
    LocalDateTime lastMessageAt,
    LocalDateTime createdAt
) {}
