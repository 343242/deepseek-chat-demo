package com.smart.rag.chat.service;

import com.smart.rag.common.util.ChecksumUtils;
import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.exception.MessagingException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 聊天消息保存发布者 — 把同步路径与流式路径的落库请求都路由到消息总线
 * （{@code chat_message_save} Topic），由 {@link ChatMessageSaveConsumer} 异步消费并调
 * {@link ChatConversationHelper#saveMessagesAndNotify} 落库。
 * <p>
 * 两接入点（见 {@code messaging-bus.md} §7.1）：
 * <ul>
 *   <li>同步路径：{@code ChatServiceImpl.processResult()}</li>
 *   <li>流式路径：{@code MultiTurnModeStrategy.executeStream()} 的 {@code Flux.doFinally}</li>
 * </ul>
 *
 * <p><b>关键路径（chat 落库）</b>：投递经 {@code OutboxMessageBus}（{@code @Primary} 装饰器，
 * child 2）——INSERT outbox 行（DB 事务级持久化）后异步即时投递，失败行留 relay 退避补投，
 * <b>不再有同步降级路径</b>（{@code saveWithBoundedRetry} 已随 outbox 删除，R2）。
 * {@code send()} 仅在 outbox INSERT 失败（DB 硬故障）时抛 {@link MessagingException}——catch
 * 仅防御此路径，记 {@code chat.save.publish_failed} 告警计数。
 *
 * <p><b>幂等</b>：deduplicationKey = {@code conversationId + ":" + sha256Hex(userMessage)}。
 * 保证同一会话不同消息不互斥；bus send 网络超时（Broker 实际已入队但客户端抛 TimeoutException）
 * 触发重复投递后，consumer 后续拉到同消息时由 §5.10 总线级 Redis SETNX 拦截，
 * 业务层 DB 唯一约束 {@code (conversation_id, message_index)} 兜底。
 * 边角风险（同会话 15min 内连发相同 userMessage）由 DB 唯一约束兜底，PRD P1-D6 已声明接受。
 */
@Component
public class ChatMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatMessagePublisher.class);

    /** 聊天消息保存 Topic 裸名（物理名由 {@code topicPrefix} 拼接为 {@code SMART_RAG_chat_message_save}，见 §5.12）。 */
    public static final String TOPIC = "chat_message_save";

    private final MessageBus messageBus;
    private final @Nullable MeterRegistry meterRegistry;

    public ChatMessagePublisher(MessageBus messageBus,
                                @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
        this.messageBus = messageBus;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 发布消息保存事件。
     * <p>
     * 投递可靠性由 outbox 保证（child 2）：{@code send()} 返回前仅完成 outbox INSERT（~1ms），
     * 即时投递与 relay 回收均在独立线程/定时任务上异步进行——<b>不阻塞请求线程</b>。
     *
     * @param conversationId   会话 ID
     * @param userMessage      用户消息内容
     * @param assistantContent AI 回复内容
     * @param candidateId      候选模型 ID（registry candidate ID）
     * @param totalTokens      总 token 数，{@code null} 表示未知（厂商未返回 usage；每轮对话显示只落真实值，不估算）
     * @param durationMs       调用耗时（毫秒）
     */
    public void publishMessageSave(String conversationId, String userMessage, String assistantContent,
                                   String candidateId, @Nullable Integer totalTokens, long durationMs) {
        ChatMessagePayload payload = new ChatMessagePayload(
                conversationId, userMessage, assistantContent, candidateId,
                totalTokens != null ? totalTokens.longValue() : null, durationMs);
        // deduplicationKey = conversationId + ":" + sha256Hex(userMessage)。
        String deduplicationKey = conversationId + ":" + ChecksumUtils.sha256Hex(userMessage);
        MessageEnvelope<ChatMessagePayload> message =
                MessageEnvelope.deduplicated(TOPIC, payload, deduplicationKey);

        try {
            messageBus.send(message);
            log.debug("Chat message save published: conversationId={}, candidate={}, totalTokens={}, durationMs={}",
                    ConversationIdUtil.mask(conversationId), candidateId, totalTokens, durationMs);
        } catch (MessagingException e) {
            // 仅防御 outbox INSERT 失败（DB 硬故障）：行没进去就没有 relay 兜底。
            // 数据已 SSE 投递给用户，丢的是会话历史持久化（用户可重发）——记 ERROR + 告警计数。
            log.error("Outbox persist failed for chat save (message lost): conversationId={}",
                    ConversationIdUtil.mask(conversationId), e);
            if (meterRegistry != null) {
                meterRegistry.counter("chat.save.publish_failed").increment();
            }
        }
    }
}
