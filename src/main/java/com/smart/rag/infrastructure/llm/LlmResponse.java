package com.smart.rag.infrastructure.llm;

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
    Map<String, Object> responseMetadata
) {
    public LlmResponse {
        if (toolCalls == null) toolCalls = List.of();
        if (responseMetadata == null) responseMetadata = Map.of();
    }

    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {}
    public record ToolCall(String id, String name, String arguments) {}
}
