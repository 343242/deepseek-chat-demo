package com.smart.rag.mode;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 策略执行上下文 -- execute / executeStream 的入参。
 */
public record StrategyExecutionContext(
    ChatClient chatClient,
    /** 候选模型 ID（对应 YAML candidate.id） */
    String candidateId,
    ChatRequest request,
    /** 隔离后的 ID -- 内部使用（消息保存、usage 记录） */
    String conversationId,
    /** 原始 ID -- 返回给客户端的 DTO */
    String rawConversationId,
    Long userId,
    @Nullable RequestContext cagContext,
    /** 创建时间戳，供 elapsed() 计算 */
    long startTimeMs
) {
    public long elapsed() {
        return System.currentTimeMillis() - startTimeMs;
    }
}
