package com.smart.rag.chat.context;
import com.smart.rag.mode.SessionContext;

/**
 * 会话上下文解析策略
 * <p>
 * 基于上游传入的消息数量，推断对话阶段并构建会话上下文。
 * 不自行查 ChatMemory，避免与 ConversationContextAdvisor 重复查询。
 */
public interface SessionContextResolver {

    /**
     * 解析会话上下文
     *
     * @param conversationId 隔离后的对话 ID
     * @param messageCount   当前会话消息数量（由上游传入）
     * @return 会话上下文
     */
    SessionContext resolve(String conversationId, int messageCount);
}
