package com.smart.rag.usage;

import org.jspecify.annotations.Nullable;

/**
 * 用量事件消息载荷 — {@code UsageRecorder} → MessageBus(topic {@code usage_event_record}) → {@code UsageEventConsumer}。
 * <p>
 * token 为 {@code null} 表未知（取消 -1 哨兵：NULL 不污染 SUM）；estimated=true 时 token 为字符估算值。
 *
 * @param eventId          事件唯一 ID（UUID，兼作消息幂等键与 DB 唯一约束）
 * @param userId           发起用户 ID
 * @param scene            调用场景（{@link com.smart.rag.infrastructure.llm.usage.UsageScene} 名）
 * @param conversationId   会话 ID，可为 {@code null}
 * @param candidateId      候选模型 ID（registry candidate ID）
 * @param promptTokens     输入 token 数，{@code null} 表未知
 * @param completionTokens 输出 token 数，{@code null} 表未知
 * @param estimated        token 是否为估算值
 * @param success          调用是否成功
 * @param durationMs       模型调用耗时（毫秒）
 */
public record UsageEventPayload(
    String eventId,
    Long userId,
    String scene,
    @Nullable String conversationId,
    String candidateId,
    @Nullable Long promptTokens,
    @Nullable Long completionTokens,
    @Nullable Long totalTokens,
    boolean estimated,
    boolean success,
    long durationMs
) {
}
