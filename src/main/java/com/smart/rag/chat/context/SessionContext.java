package com.smart.rag.chat.context;

/**
 * 会话上下文 — 当前请求的会话状态
 *
 * @param conversationId 隔离后的对话 ID
 * @param messageCount   当前会话消息数量
 * @param stage          对话阶段推断（首次对话 / 对话初期 / 深入交流 / 长对话）
 */
public record SessionContext(
        String conversationId,
        int messageCount,
        String stage
) {
}
