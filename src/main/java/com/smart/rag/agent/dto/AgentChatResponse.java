package com.smart.rag.agent.dto;

import java.util.Map;

/**
 * Agent 模式响应 DTO
 *
 * @param content        回答内容
 * @param conversationId 对话 ID
 * @param model          使用的模型
 * @param intent         意图分类结果
 * @param traceId        追踪 ID
 * @param toolCalls      Tool 调用次数
 * @param totalTokens    总 token 消耗
 * @param durationMs     总耗时（ms）
 * @param metadata       额外元数据（AgentTrace、引用列表等）
 */
public record AgentChatResponse(
    String content,
    String conversationId,
    String model,
    String intent,
    String traceId,
    int toolCalls,
    int totalTokens,
    long durationMs,
    Map<String, Object> metadata
) {}
