package com.demo.chat.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 聊天响应 DTO（阻塞式）
 *
 * @param model          实际使用的模型 ID
 * @param content        模型回复内容
 * @param conversationId 对话 ID
 * @param fallback       降级元数据（null 时序列化省略，兼容旧客户端）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
    String model,
    String content,
    String conversationId,
    FallbackMeta fallback
) {

    /**
     * 兼容旧构造 — 不带 fallback 元数据
     */
    public ChatResponse(String model, String content, String conversationId) {
        this(model, content, conversationId, null);
    }
}
