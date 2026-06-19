package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.Reference;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;
import java.util.Map;

/**
 * 策略 execute() 的统一返回类型。
 * ChatServiceImpl 根据 result 完成后续处理：usage 记录、消息保存、DTO 包装。
 */
public record StrategyExecuteResult(
    /** Spring AI 原始响应（含 usage metadata） */
    ChatResponse springAiResponse,
    /** 提取后的文本内容 */
    String content,
    /** Agent 元数据（仅 Agent 模式非 null） */
    @Nullable Map<String, Object> agentMetadata,
    /** 检索引用映射（#n → chunkId/documentId/fileName/page），非 RAG 时为 null */
    @Nullable List<Reference> references
) {
    /** 标准模式工厂 */
    public static StrategyExecuteResult standard(ChatResponse response, String content) {
        return new StrategyExecuteResult(response, content, null, null);
    }

    /** 标准模式工厂（带 references） */
    public static StrategyExecuteResult standard(ChatResponse response, String content,
                                                 @Nullable List<Reference> references) {
        return new StrategyExecuteResult(response, content, null, references);
    }

    /** Agent 模式工厂 */
    public static StrategyExecuteResult agent(ChatResponse response,
                                               String content,
                                               Map<String, Object> agentMetadata) {
        return new StrategyExecuteResult(response, content, agentMetadata, null);
    }

    /** Agent 模式工厂（带 references） */
    public static StrategyExecuteResult agent(ChatResponse response,
                                               String content,
                                               Map<String, Object> agentMetadata,
                                               @Nullable List<Reference> references) {
        return new StrategyExecuteResult(response, content, agentMetadata, references);
    }
}
