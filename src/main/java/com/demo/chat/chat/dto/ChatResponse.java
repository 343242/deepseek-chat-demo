package com.demo.chat.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 聊天响应 DTO（阻塞式）
 *
 * @param model          实际使用的模型 ID
 * @param content        模型回复内容
 * @param conversationId 对话 ID
 * @param fallback       是否由兜底策略降级产生（null 时序列化时省略）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
    String model,
    String content,
    String conversationId,
    Boolean fallback
) {

    /**
     * 兼容旧构造 — 不带 fallback 标记
     */
    public ChatResponse(String model, String content, String conversationId) {
        this(model, content, conversationId, null);
    }
}
