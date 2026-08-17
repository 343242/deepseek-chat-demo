package com.smart.rag.usage;

import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.Subscription;
import com.smart.rag.usage.service.UsageEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 用量事件消费者 — 从消息总线拉取 {@code usage_event_record} 并异步落库。
 * <p>
 * PushConsumer 模式（{@link ConsumerConfig#DEFAULT}）。落库失败重抛触发 broker 重试
 * （非关键路径，不静默吞咽）。
 * <p>
 * <b>幂等</b>：双层兜底——订阅端 {@code IdempotentHandler}（Redis SETNX，TTL 900s）按
 * {@code eventId} 去重 broker 重投递；Redis 不可用窗口内的重复投递由 DB
 * {@code usage_event.event_id} 唯一约束兜底（重复行以 DuplicateKey 静默跳过）。
 */
@Component
public class UsageEventConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UsageEventConsumer.class);

    /** 消费组（沿用旧 usage-group；新 topic 下消费位点从零开始） */
    static final String GROUP = "usage-group";

    private final MessageBus messageBus;
    private final UsageEventService usageEventService;

    private volatile Subscription subscription;
    private volatile boolean running;

    public UsageEventConsumer(MessageBus messageBus, UsageEventService usageEventService) {
        this.messageBus = messageBus;
        this.usageEventService = usageEventService;
    }

    @Override
    public void start() {
        if (running) return;

        subscription = messageBus.subscribe(UsageRecorder.TOPIC, GROUP,
                ConsumerConfig.DEFAULT, UsageEventPayload.class,
                msg -> {
                    try {
                        usageEventService.record(msg.payload());
                    } catch (RuntimeException e) {
                        log.warn("Usage event persistence failed (will retry): dedupKey={}",
                                msg.deduplicationKey(), e);
                        throw e;
                    }
                });
        running = true;
        log.info("Usage event consumer started: topic={}, group={}", UsageRecorder.TOPIC, GROUP);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;

        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        log.info("Usage event consumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE - 100;
    }
}
