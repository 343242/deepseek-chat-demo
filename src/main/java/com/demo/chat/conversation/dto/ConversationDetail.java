package com.demo.chat.conversation.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话详情（含消息列表）
 */
public record ConversationDetail(
    Long id,
    String conversationId,
    String title,
    String titleSource,
    String modelId,
    boolean pinned,
    String status,
    int messageCount,
    OffsetDateTime lastMessageAt,
    OffsetDateTime createdAt,
    List<MessageVO> messages
) {}
