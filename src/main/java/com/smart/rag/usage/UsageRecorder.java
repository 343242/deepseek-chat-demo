package com.smart.rag.usage;

import com.smart.rag.infrastructure.llm.usage.UsageEventSink;
import com.smart.rag.infrastructure.llm.usage.UsageSample;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 用量事件发布器 — 基础设施层 {@link UsageEventSink} 端口的实现。
 * <p>
 * 把采集装饰器的 {@link UsageSample} 组装为 {@link UsageEventPayload} 发布到消息总线
 * （{@code OutboxMessageBus} → Redis Stream），由 {@code UsageEventConsumer} 异步落库。
 * <p>
 * <b>非关键路径</b>：本类承担装饰器契约的"绝不抛出"——发布失败仅记日志并计
 * {@code usage.publish_failed} 告警计数，不影响模型调用主链路。幂等键为每事件
 * 新生成的 {@code eventId}（UUID），替代旧 {@code conversationId:candidateId:毫秒桶}
 * 键（该键在 Agent 同毫秒多轮时会碰撞误去重）。
 */
@Component
public class UsageRecorder implements UsageEventSink {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    /** 用量事件 Topic 裸名（物理名由 topicPrefix 拼接为 SMART_RAG_usage_event_record）。 */
    static final String TOPIC = "usage_event_record";

    private final MessageBus messageBus;
    private final @Nullable MeterRegistry meterRegistry;

    public UsageRecorder(MessageBus messageBus,
                         @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
        this.messageBus = messageBus;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void accept(UsageSample sample) {
        try {
            String eventId = UUID.randomUUID().toString();
            UsageEventPayload payload = new UsageEventPayload(
                eventId,
                sample.context().userId(),
                sample.context().scene().name(),
                sample.context().conversationId(),
                sample.context().candidateId(),
                sample.promptTokens(),
                sample.completionTokens(),
                sample.promptTokens() != null && sample.completionTokens() != null
                    ? sample.promptTokens() + sample.completionTokens()
                    : null,
                sample.estimated(),
                sample.success(),
                sample.durationMs());
            messageBus.send(MessageEnvelope.deduplicated(TOPIC, payload, eventId));
        } catch (Exception e) {
            log.error("Failed to publish usage event: scene={}, candidate={}, userId={}",
                sample.context().scene(), sample.context().candidateId(), sample.context().userId(), e);
            if (meterRegistry != null) {
                meterRegistry.counter("usage.publish_failed").increment();
            }
        }
    }
}
