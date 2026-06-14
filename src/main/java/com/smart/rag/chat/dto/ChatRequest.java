package com.smart.rag.chat.dto;

import com.smart.rag.chat.mode.ChatMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 聊天请求 DTO
 *
 * @param model          模型候选 ID（registry candidate ID，如 deepseek-v4-flash）。
 *                      不接受 provider/model 复合格式（如 deepseek/deepseek-v4-flash）。
 * @param message        用户消息内容
 * @param conversationId 对话 ID（可选，不传则后端自动生成 UUIDv7）
 * @param ragEnabled     是否启用 RAG 检索增强（可选，默认 false）
 * @param mode           对话模式（可选，默认 SIMPLE）。
 *                       SIMPLE: 单轮直接调用，无上下文记忆
 *                       MULTI_TURN: 多轮对话，自动维护会话记忆
 *                       AGENT: Agent 模式，意图识别驱动动态 Tool 子集
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

    @Pattern(regexp = "^(SIMPLE|MULTI_TURN|AGENT)$", message = "对话模式仅支持 SIMPLE、MULTI_TURN 或 AGENT")
    String mode,

    Boolean enableThinking,

    /** 团队 ID（可选，启用 RAG 时检索团队知识库） */
    Long teamId
) {
    /**
     * 获取 conversationId，不传时返回 null（由 ChatServiceImpl 自动生成 UUIDv7）
     */
    public String conversationId() {
        return (conversationId != null && !conversationId.isBlank()) ? conversationId : null;
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

    /**
     * 创建模型替换副本（用于兜底降级时切换模型）
     *
     * @param newModel 新的模型 ID
     * @return 除 model 外其余字段不变的 ChatRequest
     */
    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, message, conversationId, ragEnabled, mode, enableThinking, teamId);
    }
}
