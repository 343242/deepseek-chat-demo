package com.demo.chat.conversation.dto;

import java.time.OffsetDateTime;

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
    OffsetDateTime lastMessageAt,
    OffsetDateTime createdAt
) {}
