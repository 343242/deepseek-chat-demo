package com.smart.rag.conversation.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建会话请求
 *
 * @param title      会话标题（可选，不传则自动从首条消息生成）
 * @param modelId    模型 ID（可选）
 */
public record ConversationCreateRequest(
    @Size(max = 200, message = "标题最长 200 字符")
    String title,

    @Size(max = 100, message = "模型 ID 最长 100 字符")
    @Pattern(regexp = "^[a-zA-Z0-9._/-]*$", message = "模型 ID 格式不合法")
    String modelId
) {}
