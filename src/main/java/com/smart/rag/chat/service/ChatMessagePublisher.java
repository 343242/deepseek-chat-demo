package com.smart.rag.chat.service;

import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.exception.MessagingException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;

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
 * <p><b>关键路径（chat 落库）</b>：与 {@link ChatUsageTracker}（非关键路径）不同，{@code send} 抛
 * {@link MessagingException} 时同步降级调 {@link ChatConversationHelper#saveMessagesAndNotify}，
 * 保留事务、双消息写入、{@code onNewMessages} 全语义，行为与 Phase C 前完全一致。
 *
 * <p><b>幂等</b>：deduplicationKey = {@code conversationId + ":" + md5Hex(userMessage)}。
 * 保证同一会话不同消息不互斥；bus send 网络超时（Broker 实际已入队但客户端抛 TimeoutException）
 * 触发同步降级写入后，consumer 后续拉到同消息时由 §5.10 总线级 Redis SETNX 拦截，
 * 业务层 DB 唯一约束 {@code (conversation_id, message_index)} 兜底。
 * 边角风险（同会话 15min 内连发相同 userMessage）由 DB 唯一约束兜底，PRD P1-D6 已声明接受。
 */
@Component
public class ChatMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatMessagePublisher.class);

    /** 聊天消息保存 Topic 裸名（物理名由 {@code topicPrefix} 拼接为 {@code SMART_RAG_chat_message_save}，见 §5.12）。 */
    public static final String TOPIC = "chat_message_save";

    private final MessageBus messageBus;
    private final ChatConversationHelper conversationHelper;
    private final @Nullable MeterRegistry meterRegistry;
    private final long[] backoffMs;

    public ChatMessagePublisher(MessageBus messageBus, ChatConversationHelper conversationHelper,
                                @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
        this(messageBus, conversationHelper, meterRegistry, DEFAULT_BACKOFF_MS);
    }

    /** 包级构造器：测试注入退避数组（生产用 {@link #DEFAULT_BACKOFF_MS}）。 */
    ChatMessagePublisher(MessageBus messageBus, ChatConversationHelper conversationHelper,
                         @Nullable MeterRegistry meterRegistry, long[] backoffMs) {
        this.messageBus = messageBus;
        this.conversationHelper = conversationHelper;
        this.meterRegistry = meterRegistry;
        this.backoffMs = backoffMs.clone();  // 防御性拷贝：避免外部 mutate 污染退避
    }

    /**
     * 发布消息保存事件，失败时降级为同步写入。
     * <p>
     * 不使用 Transactional Outbox——同 JVM 内异步解耦，{@code send} 失败时回退到同步路径即可。
     * 两接入点当前都不在事务上下文，故直接用 {@link MessageBus#send}（见 DC-01）。
     *
     * @param conversationId   会话 ID
     * @param userMessage      用户消息内容
     * @param assistantContent AI 回复内容
     * @param candidateId      候选模型 ID（registry candidate ID）
     * @param aiResponse       AI 响应（含 usage 元数据，可为 {@code null}；仅用于提取 totalTokens）
     * @param elapsedMs        调用耗时（毫秒）
     */
    public void publishMessageSave(String conversationId, String userMessage, String assistantContent,
                                   String candidateId, ChatResponse aiResponse, long elapsedMs) {
        int totalTokens = ChatConversationHelper.extractTotalTokens(aiResponse);
        ChatMessagePayload payload = new ChatMessagePayload(
                conversationId, userMessage, assistantContent, candidateId, totalTokens);
        // deduplicationKey = conversationId + ":" + md5Hex(userMessage)。
        String deduplicationKey = conversationId + ":" + DigestUtils.md5Hex(userMessage);
        MessageEnvelope<ChatMessagePayload> message =
                MessageEnvelope.deduplicated(TOPIC, payload, deduplicationKey);

        try {
            messageBus.send(message);
            log.debug("Chat message save published: conversationId={}, candidate={}, totalTokens={}",
                    ConversationIdUtil.mask(conversationId), candidateId, totalTokens);
        } catch (MessagingException e) {
            // Bus 失败同步降级：走有限重试的 saveMessagesAndNotify（Phase D D-4）。
            // 保留事务、双消息写入、onNewMessages 全部语义；DB 瞬时故障由有限重试覆盖，
            // 重试耗尽或硬故障 → 记 ERROR + chat.save.fallback_failed 告警计数（不再写 legacy Redis DLQ）。
            log.warn("Message bus unavailable, falling back to synchronous save: conversationId={}",
                    ConversationIdUtil.mask(conversationId), e);
            saveWithBoundedRetry(conversationId, userMessage, assistantContent,
                    candidateId, totalTokens, elapsedMs);
        }
    }

    /** 同步降级路径默认指数退避（ms）：覆盖 DB 瞬时故障（连接抖动/死锁）。测试可经包级构造器注入。 */
    static final long[] DEFAULT_BACKOFF_MS = {200, 1000, 3000};

    /**
     * 同步降级路径有限重试：仅对瞬时 DB 异常（{@link DataAccessException} /
     * {@link TransactionSystemException}）重试，覆盖现实主流的 DB 失败模式。
     * 硬故障重试耗尽或非瞬时异常 → {@link #reportFallbackFailure}（记 ERROR + 告警计数）。
     * <p>
     * 降级路径罕见（仅 bus 不可达时触发），阻塞可接受；不保留 legacy Redis DLQ（会卡死 Phase D soak 门控）。
     */
    private void saveWithBoundedRetry(String conversationId, String userMessage, String assistantContent,
                                      String candidateId, int totalTokens, long elapsedMs) {
        for (int attempt = 0; ; attempt++) {
            try {
                conversationHelper.saveMessagesAndNotify(
                        conversationId, userMessage, assistantContent, candidateId, totalTokens, elapsedMs);
                return;  // 落库成功
            } catch (DataAccessException | TransactionSystemException e) {
                if (attempt >= backoffMs.length - 1) {
                    reportFallbackFailure(conversationId, e);  // 重试耗尽 → DB 硬故障
                    return;
                }
                log.warn("Fallback save transient DB failure, retry in {}ms (attempt {}/{}): conversationId={}",
                        backoffMs[attempt + 1], attempt + 1, backoffMs.length,
                        ConversationIdUtil.mask(conversationId));
                if (!sleepNoThrow(backoffMs[attempt + 1])) {
                    return;  // 线程被中断，放弃
                }
            } catch (RuntimeException e) {
                // 非瞬时 DB 异常（逻辑错误等）：不重试，直接告警
                reportFallbackFailure(conversationId, e);
                return;
            }
        }
    }

    /**
     * 重试耗尽 / 不可重试：记 ERROR（脱敏）+ 自增 {@code chat.save.fallback_failed} 告警计数。
     * 数据已 SSE 投递给用户，丢的是会话历史持久化（用户可重发）。
     */
    private void reportFallbackFailure(String conversationId, RuntimeException e) {
        log.error("Fallback save failed (bus + DB both unavailable): conversationId={}",
                ConversationIdUtil.mask(conversationId), e);
        if (meterRegistry != null) {
            meterRegistry.counter("chat.save.fallback_failed", "result", "exhausted").increment();
        }
    }

    /** {@link Thread#sleep} 封装：被中断时恢复中断标志并返回 false（调用方放弃重试）。 */
    private static boolean sleepNoThrow(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
