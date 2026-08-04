package com.smart.rag.infrastructure.llm;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Chat 响应
 * <p>
 * 命名为 {@code LlmResponse} 以避免与 Spring AI 的
 * {@code org.springframework.ai.chat.model.ChatResponse} 类型冲突。
 * {@code ChatCapable} 不继承 Spring AI {@code ChatModel}（ISP/LSP 合规），
 * 桥接由 {@link com.smart.rag.infrastructure.llm.adapter.ChatModelAdapter} 独立完成。
 */
public record LlmResponse(
    /** 生成的文本内容 */
    String content,

    /** 是否被截断（达到 maxTokens） */
    boolean truncated,

    /** Token 使用量 */
    TokenUsage tokenUsage,

    /** 工具调用结果（Agent 场景） */
    List<ToolCall> toolCalls,

    /** 供应商原始响应元数据（调试用，不暴露未类型化对象） */
    Map<String, Object> responseMetadata,

    /** 思考过程文本（reasoning_content）；无思考内容时为空串 */
    String reasoningContent
) {
    public LlmResponse {
        content = content != null ? content : "";
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        responseMetadata = responseMetadata != null ? Map.copyOf(responseMetadata) : Map.of();
        reasoningContent = reasoningContent != null ? reasoningContent : "";
    }

    /** 向后兼容：旧 5 参签名（无 reasoningContent） */
    public LlmResponse(String content, boolean truncated, TokenUsage tokenUsage,
                       List<ToolCall> toolCalls, Map<String, Object> responseMetadata) {
        this(content, truncated, tokenUsage, toolCalls, responseMetadata, "");
    }

    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens,
                             @Nullable Integer cacheHitTokens) {
        /** 向后兼容：未提供缓存命中信息时 cacheHitTokens=null */
        public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this(promptTokens, completionTokens, totalTokens, null);
        }
    }
    public record ToolCall(String id, String name, String arguments) {}
}
