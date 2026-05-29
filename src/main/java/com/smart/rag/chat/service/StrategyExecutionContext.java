package com.smart.rag.chat.service;

import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.provider.ModelRouter;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 策略执行上下文 -- execute / executeStream 的入参。
 */
public record StrategyExecutionContext(
    ChatClient chatClient,
    ModelRouter.Route route,
    ChatRequest request,
    /** 隔离后的 ID -- 内部使用（消息保存、usage 记录） */
    String conversationId,
    /** 原始 ID -- 返回给客户端的 DTO */
    String rawConversationId,
    Long userId,
    RequestContext cagContext,
    /** 创建时间戳，供 elapsed() 计算 */
    long startTimeMs
) {
    public long elapsed() {
        return System.currentTimeMillis() - startTimeMs;
    }
}
