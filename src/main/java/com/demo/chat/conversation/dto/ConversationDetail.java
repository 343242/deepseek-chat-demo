package com.demo.chat.conversation.dto;

import java.time.LocalDateTime;
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
    LocalDateTime lastMessageAt,
    LocalDateTime createdAt,
    List<MessageVO> messages
) {}
