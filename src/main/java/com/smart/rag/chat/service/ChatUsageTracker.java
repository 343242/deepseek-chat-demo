package com.smart.rag.chat.service;

import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Token 用量记录器 — 用量记录的中心化入口。
 * <p>
 * Phase C Step 1 后不再直接落库，而是将用量记录发布到消息总线（{@link #TOPIC}），
 * 由 {@link UsageRecordConsumer} 异步消费并调 {@link UsageService#recordUsage} 落库。
 * 发送端与 DB 写入端经 {@link MessageBus} SPI 解耦——本类只持有 {@link MessageBus}，
 * 不再直接引用 {@link UsageService}。
 *
 * <p><b>非关键路径</b>：发布失败仅记日志，不影响主对话流程（与改造前的 try/catch 吞咽语义一致）。
 *
 * <p><b>非关键路径（见 messaging-bus.md §7.2、§9 Phase C）</b>：Phase 0 后消息总线 always-on，
 * {@code send} 失败仅记日志、不影响主对话流程（与改造前 try/catch 吞咽语义一致）。usage 不做同步降级
 * fallback——降级是 chat save 的职责（见 §7.1）；usage 偶发丢失可接受。
 */
@Component
public class ChatUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(ChatUsageTracker.class);

    /** 用量记录 Topic 裸名（物理名由 {@code topicPrefix} 拼接为 {@code SMART_RAG_chat_usage_record}，见 §5.12）。 */
    static final String TOPIC = "chat_usage_record";

    private final MessageBus messageBus;

    public ChatUsageTracker(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    /**
     * 从 AI 响应中提取并发布 Token 用量到消息总线。
     * <p>
     * {@code aiResponse} / {@code metadata} / {@code usage} 任一为空时，token 三字段填 {@code -1}，
     * 仍发布记录（至少保留 duration 与 candidateId）。相比改造前"usage 为空则跳过"，此为防御性增强。
     *
     * @param conversationId 会话 ID
     * @param candidateId    候选模型 ID（registry candidate ID）
     * @param aiResponse     AI 响应（含 usage 元数据），可为 {@code null}
     * @param durationMs     调用耗时（毫秒）
     */
    public void recordUsage(String conversationId, String candidateId,
                            org.springframework.ai.chat.model.ChatResponse aiResponse, long durationMs) {
        try {
            Usage usage = (aiResponse != null && aiResponse.getMetadata() != null)
                    ? aiResponse.getMetadata().getUsage() : null;
            long promptTokens = extractOrNeg(usage, Usage::getPromptTokens);
            long completionTokens = extractOrNeg(usage, Usage::getCompletionTokens);
            long totalTokens = extractOrNeg(usage, Usage::getTotalTokens);
            publish(conversationId, candidateId,
                    promptTokens, completionTokens, totalTokens, durationMs);
        } catch (Exception e) {
            log.error("Failed to publish usage: conversationId={}, candidate={}",
                    ConversationIdUtil.mask(conversationId), candidateId, e);
        }
    }

    /**
     * 发布用量记录（无 AI 响应时的降级版本，token 三字段填 {@code -1}）。
     *
     * @param conversationId 会话 ID
     * @param candidateId    候选模型 ID（registry candidate ID）
     * @param durationMs     调用耗时（毫秒）
     */
    public void recordUsage(String conversationId, String candidateId, long durationMs) {
        try {
            publish(conversationId, candidateId, -1, -1, -1, durationMs);
        } catch (Exception e) {
            log.error("Failed to publish usage (no-response): conversationId={}, candidate={}",
                    ConversationIdUtil.mask(conversationId), candidateId, e);
        }
    }

    private void publish(String conversationId, String candidateId,
                         long promptTokens, long completionTokens, long totalTokens,
                         long durationMs) {
        UsagePayload payload = new UsagePayload(
                conversationId, candidateId,
                promptTokens, completionTokens, totalTokens, durationMs);
        // deduplicationKey = conversationId + ":" + candidateId + ":" + 毫秒时间桶。
        // 同会话同模型的多次调用不被去重（每次都是独立 usage 记录），
        // 仅对 broker 重试 / consumer ACK 失败导致的同一记录重复投递去重。
        String deduplicationKey = conversationId + ":" + candidateId + ":" + System.currentTimeMillis();
        messageBus.send(MessageEnvelope.deduplicated(TOPIC, payload, deduplicationKey));
        log.debug("Usage published: candidate={}, prompt={}, completion={}, total={}, duration={}ms",
                candidateId, promptTokens, completionTokens, totalTokens, durationMs);
    }

    /** 从 {@link Usage} 安全提取 token 数；{@code usage} 为空或取值为 {@code null} 时返回 {@code -1}。 */
    private static long extractOrNeg(Usage usage, Function<Usage, Integer> extractor) {
        if (usage == null) {
            return -1;
        }
        Integer value = extractor.apply(usage);
        return value != null ? value.intValue() : -1;
    }
}
