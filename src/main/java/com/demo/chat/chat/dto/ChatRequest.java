package com.demo.chat.chat.dto;

import com.demo.chat.chat.mode.ChatMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 聊天请求 DTO
 *
 * @param model          模型 ID（如 deepseek-chat, deepseek/deepseek-chat）
 * @param message        用户消息内容
 * @param conversationId 对话 ID（可选，用于多轮对话，默认 "default"）
 * @param ragEnabled     是否启用 RAG 检索增强（可选，默认 false）
 * @param mode           对话模式（可选，默认 SIMPLE）。
 *                       SIMPLE: 单轮直接调用，无上下文记忆
 *                       MULTI_TURN: 多轮对话，自动维护会话记忆
 * @param enableThinking 是否启用思考过程输出（可选，默认 false，仅 MULTI_TURN 模式生效）
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
    String conversationId,

    Boolean ragEnabled,

    @Pattern(regexp = "^(SIMPLE|MULTI_TURN)?$", message = "对话模式仅支持 SIMPLE 或 MULTI_TURN")
    String mode,

    Boolean enableThinking
) {
    public String conversationId() {
        return conversationId != null && !conversationId.isBlank() ? conversationId : "default";
    }

    /** 是否启用 RAG，默认 false */
    public boolean isRagEnabled() {
        return ragEnabled != null && ragEnabled;
    }

    /** 解析对话模式，默认 SIMPLE */
    public ChatMode resolveMode() {
        return ChatMode.fromString(mode);
    }

    /** 是否启用思考过程，仅 MULTI_TURN 模式下生效 */
    public boolean isThinkingEnabled() {
        return resolveMode() == ChatMode.MULTI_TURN && enableThinking != null && enableThinking;
    }
}
