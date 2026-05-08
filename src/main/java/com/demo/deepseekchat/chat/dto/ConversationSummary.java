package com.demo.deepseekchat.chat.dto;

import java.time.LocalDateTime;

/**
 * 对话摘要 DTO（用于历史列表）
 *
 * @param conversationId 对话 ID
 * @param messageCount   消息数量
 * @param firstMessageAt 第一条消息时间
 * @param lastMessageAt  最后一条消息时间
 */
public record ConversationSummary(
    String conversationId,
    long messageCount,
    LocalDateTime firstMessageAt,
    LocalDateTime lastMessageAt
) {}
