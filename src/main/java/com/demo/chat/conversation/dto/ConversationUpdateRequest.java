package com.demo.chat.conversation.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新会话请求
 *
 * @param title     新标题（可选，不传则不更新）
 * @param pinned    是否置顶（可选）
 * @param status    状态（可选：ACTIVE / ARCHIVED）
 */
public record ConversationUpdateRequest(
    @Size(max = 200, message = "标题最长 200 字符")
    String title,
    Boolean pinned,
    String status
) {}
