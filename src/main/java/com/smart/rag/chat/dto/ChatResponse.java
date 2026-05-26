package com.smart.rag.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 聊天响应 DTO（阻塞式）
 *
 * @param model          实际使用的模型 ID
 * @param content        模型回复内容
 * @param conversationId 对话 ID
 * @param fallback       降级元数据（null 时序列化省略，兼容旧客户端）
 * @param agentMetadata  Agent 模式元数据（intent、token 用量、tool 调用统计等）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
    String model,
    String content,
    String conversationId,
    FallbackMeta fallback,
    Map<String, Object> agentMetadata
) {

    public ChatResponse(String model, String content, String conversationId) {
        this(model, content, conversationId, null, null);
    }

    public ChatResponse(String model, String content, String conversationId, FallbackMeta fallback) {
        this(model, content, conversationId, fallback, null);
    }
}
