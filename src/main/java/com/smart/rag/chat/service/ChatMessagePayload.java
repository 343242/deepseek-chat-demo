package com.smart.rag.chat.service;

import java.util.Objects;

/**
 * 聊天消息保存消息载荷 — {@code chat_message_save} Topic 的 payload。
 * <p>
 * 由 {@link ChatMessagePublisher} 发布、{@link ChatMessageSaveConsumer} 消费。
 * 字段语义对齐 LLM SPI 统一命名：{@code candidateId} 即 registry candidate ID
 * （写入消息记录的 model 列；列名保留兼容历史数据，写入值是 candidate ID 字符串，
 * 见 {@code messaging-bus.md} §7.1）。
 *
 * @param conversationId   会话 ID
 * @param userMessage      用户消息内容
 * @param assistantContent AI 回复内容
 * @param candidateId      候选模型 ID（registry candidate ID）
 * @param totalTokens      总 token 数，{@code -1} 表示未知（流式路径或 usage 缺失时）
 */
public record ChatMessagePayload(
    String conversationId,
    String userMessage,
    String assistantContent,
    String candidateId,
    long totalTokens
) {
    public ChatMessagePayload {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(assistantContent, "assistantContent");
        Objects.requireNonNull(candidateId, "candidateId");
    }
}
