package com.demo.deepseekchat.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 聊天请求 DTO
 *
 * @param model         模型 ID（如 deepseek-chat, deepseek-reasoner）
 * @param message       用户消息内容
 * @param conversationId 对话 ID（可选，用于多轮对话，默认 "default"）
 */
public record ChatRequest(
    @NotBlank(message = "模型不能为空")
    @Size(max = 100, message = "模型名称过长")
    String model,

    @NotBlank(message = "消息不能为空")
    @Size(max = 10000, message = "消息内容过长，最多 10000 字符")
    String message,

    @Size(max = 100, message = "对话 ID 过长")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "对话 ID 仅允许字母、数字、下划线和连字符")
    String conversationId
) {
    public String conversationId() {
        return conversationId != null && !conversationId.isBlank() ? conversationId : "default";
    }
}
