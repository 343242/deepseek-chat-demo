package com.smart.rag.chat.context;
import com.smart.rag.mode.RequestContext;

/**
 * 请求上下文管理器 — CAG 架构的核心组件
 * <p>
 * 职责：收集并规范化运行时信号，生成统一的 {@link RequestContext}。
 * 约束：只编排和整理上下文，不做业务决策。
 */
public interface RequestContextManager {

    /**
     * 收集并组装当前请求的完整上下文
     *
     * @param userId          当前用户 ID
     * @param conversationId  隔离后的对话 ID（必须传 ConversationIdUtil.buildIsolatedId 的结果）
     * @param ragEnabled      是否启用 RAG
     * @param messageCount    当前会话消息数量（上游传入，避免重复查 ChatMemory）
     * @return 完整的请求上下文
     */
    RequestContext buildContext(Long userId, String conversationId,
                                boolean ragEnabled, int messageCount);
}
