package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 用量记录消费者 — 从消息总线拉取 {@code chat_usage_record} 并异步落库。
 * <p>
 * PushConsumer 模式（{@link ConsumerConfig#DEFAULT}），消费组 {@link #GROUP}
 * （运维侧 {@code maxDeliveryAttempts=16}）。handler 内部调
 * {@link UsageService#recordUsage} 落库。
 * <p>
 * <b>幂等</b>：publisher 端 {@link ChatUsageTracker} 已为每条消息设置稳定的
 * {@code deduplicationKey}（见 messaging-bus.md §7.2）；订阅时由
 * {@link com.smart.rag.infrastructure.messaging.redis.RedisStreamMessageBus} 自动用
 * {@link com.smart.rag.infrastructure.messaging.idempotent.IdempotentHandler}
 * （Redis SETNX，默认开启、TTL 900s 覆盖 16 次重试窗口）包装 handler，故同一记录的
 * broker 重投递会被去重。这是 at-least-once 语义下的尽力去重：当 Redis 在重投递窗口内
 * 不可用时，{@code IdempotentHandler} 降级为透传，可能产生重复行（见其类注释的权衡声明）
 * ——usage 为非关键路径，此权衡可接受。
 * <p>
 * Phase 0 后消息总线 always-on，本 consumer 始终激活（无条件 {@code @Component}），
 * 与 publisher 端 {@link ChatUsageTracker} 一致。
 */
@Component
public class UsageRecordConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordConsumer.class);

    /** 消费组（运维侧创建，{@code maxDeliveryAttempts=16}，见 §5.12）。 */
    static final String GROUP = "usage-group";

    private final MessageBus messageBus;
    private final UsageService usageService;

    private volatile Subscription subscription;
    private volatile boolean running;

    public UsageRecordConsumer(MessageBus messageBus, UsageService usageService) {
        this.messageBus = messageBus;
        this.usageService = usageService;
    }

    @Override
    public void start() {
        if (running) return;

        MessageHandler<UsagePayload> handler = msg -> {
            UsagePayload p = msg.payload();
            try {
                usageService.recordUsage(
                        p.conversationId(), p.candidateId(),
                        p.promptTokens(), p.completionTokens(),
                        p.totalTokens(), p.durationMs());
            } catch (RuntimeException e) {
                // 落库失败：记录可观测日志后重抛，触发 broker 重试（非关键路径，不静默吞咽）。
                log.warn("Usage record persistence failed (will retry): topic={}, dedupKey={}",
                        ChatUsageTracker.TOPIC, msg.deduplicationKey(), e);
                throw e;
            }
        };

        subscription = messageBus.subscribe(ChatUsageTracker.TOPIC, GROUP,
                ConsumerConfig.DEFAULT, UsagePayload.class, handler);
        running = true;
        log.info("Usage record consumer started: topic={}, group={}", ChatUsageTracker.TOPIC, GROUP);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;

        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        log.info("Usage record consumer stopped");
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
