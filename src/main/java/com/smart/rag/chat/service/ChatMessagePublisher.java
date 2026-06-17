package com.smart.rag.chat.service;

import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.exception.MessagingException;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
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

    public ChatMessagePublisher(MessageBus messageBus, ChatConversationHelper conversationHelper) {
        this.messageBus = messageBus;
        this.conversationHelper = conversationHelper;
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
            // Bus 失败同步降级：直接走原 saveMessagesAndNotify，
            // 保留事务、双消息写入、onNewMessages 全部语义。
            log.warn("Message bus unavailable, falling back to synchronous save: conversationId={}",
                    ConversationIdUtil.mask(conversationId), e);
            conversationHelper.saveMessagesAndNotify(
                    conversationId, userMessage, assistantContent,
                    candidateId, totalTokens, elapsedMs);
        }
    }
}
