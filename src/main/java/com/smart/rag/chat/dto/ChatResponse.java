package com.smart.rag.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smart.rag.mode.Reference;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 聊天响应 DTO（阻塞式）
 *
 * @param model          实际使用的模型 ID
 * @param content        模型回复内容
 * @param conversationId 对话 ID
 * @param fallback       降级元数据（null 时序列化省略，兼容旧客户端）
 * @param agentMetadata  Agent 模式元数据（intent、token 用量、tool 调用统计等）
 * @param references     检索引用映射（#n → chunkId/documentId/fileName/page），非 RAG 时为 null（序列化省略）
 * @param tokenUsage     本次回复总 token 数，null 表示未知（厂商未返回 usage；只给真实值不估算）
 * @param durationMs     本次回复耗时（毫秒）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
    String model,
    String content,
    String conversationId,
    FallbackMeta fallback,
    Map<String, Object> agentMetadata,
    @Nullable List<Reference> references,
    @Nullable Integer tokenUsage,
    @Nullable Long durationMs
) {

    public ChatResponse(String model, String content, String conversationId) {
        this(model, content, conversationId, null, null, null, null, null);
    }

    public ChatResponse(String model, String content, String conversationId, FallbackMeta fallback) {
        this(model, content, conversationId, fallback, null, null, null, null);
    }

    public ChatResponse(String model, String content, String conversationId,
                        FallbackMeta fallback, Map<String, Object> agentMetadata) {
        this(model, content, conversationId, fallback, agentMetadata, null, null, null);
    }
}
