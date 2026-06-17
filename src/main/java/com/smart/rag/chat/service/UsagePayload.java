package com.smart.rag.chat.service;

import java.util.Objects;

/**
 * 用量记录消息载荷 — {@code chat_usage_record} Topic 的 payload。
 * <p>
 * 由 {@link ChatUsageTracker} 发布、{@link UsageRecordConsumer} 消费。
 * 字段语义对齐 LLM SPI 统一命名：{@code candidateId} 即 registry candidate ID
 * （写入 {@code token_usage.model_id} 列；列名保留兼容历史数据，写入值是 candidate ID 字符串，
 * 见 {@code messaging-bus.md} §7.2）。
 *
 * @param conversationId   会话 ID
 * @param candidateId      候选模型 ID（registry candidate ID）
 * @param promptTokens     输入 token 数，{@code -1} 表示未知
 * @param completionTokens 输出 token 数，{@code -1} 表示未知
 * @param totalTokens      总 token 数，{@code -1} 表示未知
 * @param durationMs       调用耗时（毫秒）
 */
public record UsagePayload(
    String conversationId,
    String candidateId,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    long durationMs
) {
    public UsagePayload {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(candidateId, "candidateId");
    }
}
