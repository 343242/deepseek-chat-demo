package com.demo.deepseekchat.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 聊天请求 DTO
 *
 * @param model         模型 ID（如 deepseek-chat, deepseek-reasoner）
 * @param message       用户消息内容
 * @param conversationId 对话 ID（可选，用于多轮对话）
 */
public record ChatRequest(
    String model,
    String message,
    @JsonProperty(defaultValue = "default") String conversationId
) {
    public String conversationId() {
        return conversationId != null && !conversationId.isBlank() ? conversationId : "default";
    }
}
