package com.demo.deepseekchat.model.dto;

import java.time.LocalDateTime;

/**
 * 对话消息 DTO（用于导出）
 *
 * @param role      角色 (user / assistant / system)
 * @param content   消息内容
 * @param createdAt 创建时间
 */
public record ConversationMessage(
    String role,
    String content,
    LocalDateTime createdAt
) {}
