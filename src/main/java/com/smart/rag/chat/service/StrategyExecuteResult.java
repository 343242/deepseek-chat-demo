package com.smart.rag.chat.service;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatResponse;

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
    @Nullable Map<String, Object> agentMetadata
) {
    /** 标准模式工厂 */
    public static StrategyExecuteResult standard(ChatResponse response, String content) {
        return new StrategyExecuteResult(response, content, null);
    }

    /** Agent 模式工厂 */
    public static StrategyExecuteResult agent(ChatResponse response,
                                               String content,
                                               Map<String, Object> agentMetadata) {
        return new StrategyExecuteResult(response, content, agentMetadata);
    }
}
