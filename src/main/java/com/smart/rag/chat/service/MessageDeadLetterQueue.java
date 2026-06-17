package com.smart.rag.chat.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 死信队列 — 消息持久化失败后的补偿存储
 */
@Component
public class MessageDeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(MessageDeadLetterQueue.class);

    /**
     * Micrometer gauge name for the legacy Redis DLQ depth.
     * <p>
     * Used to verify the Phase D precondition: legacy DLQ 7-day rolling
     * window with 0 new entries. Exposed at {@code /metrics/legacy.dlq.size}.
     */
    static final String SIZE_GAUGE_NAME = "legacy.dlq.size";

    private final @Nullable RedissonClient redissonClient;
    private final AtomicLong failureCounter = new AtomicLong();

    public MessageDeadLetterQueue(@Nullable RedissonClient redissonClient,
                                  @Nullable MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        if (meterRegistry != null) {
            // 单例队列无标签维度；supplier 绑定 this::size，每次抓取实时查 Redisson
            meterRegistry.gauge(SIZE_GAUGE_NAME, this, MessageDeadLetterQueue::size);
        }
    }

    public void enqueue(DeadLetterEntry entry) {
        failureCounter.incrementAndGet();
        if (redissonClient != null) {
            try {
                RQueue<DeadLetterEntry> queue = redissonClient.getQueue(DeadLetterEntry.QUEUE_KEY);
                queue.offer(entry);
                log.info("Enqueued dead-letter entry: conversationId={}, retryCount={}",
                        entry.conversationId(), entry.retryCount());
            } catch (Exception e) {
                log.error("Failed to enqueue dead-letter entry: conversationId={}",
                        entry.conversationId(), e);
            }
        } else {
            log.warn("RedissonClient unavailable, dead-letter entry lost: conversationId={}",
                    entry.conversationId());
        }
    }

    public List<DeadLetterEntry> drain(int maxItems) {
        if (redissonClient == null) return Collections.emptyList();

        try {
            RQueue<DeadLetterEntry> queue = redissonClient.getQueue(DeadLetterEntry.QUEUE_KEY);
            List<DeadLetterEntry> drained = new ArrayList<>();
            for (int i = 0; i < maxItems; i++) {
                DeadLetterEntry entry = queue.poll();
                if (entry == null) break;
                drained.add(entry);
            }
            return drained;
        } catch (Exception e) {
            log.error("Failed to drain dead-letter queue", e);
            return Collections.emptyList();
        }
    }

    public long getAndResetFailureCount() {
        return failureCounter.getAndSet(0);
    }

    /**
     * 非破坏性返回队列当前长度。
     * <p>
     * 用于 Phase D 前置观测：确认 legacy DLQ 在 7 天滚动窗口内 0 新条目。
     * 通过 {@link #SIZE_GAUGE_NAME} gauge 暴露到 Actuator，并作为
     * {@code MessagingHealthIndicator} 的 detail 展示。
     *
     * @return 当前队列长度；{@code RedissonClient} 为 null（test profile / 无 Redis）时返回 0
     */
    public long size() {
        if (redissonClient == null) return 0L;
        try {
            RQueue<DeadLetterEntry> queue = redissonClient.getQueue(DeadLetterEntry.QUEUE_KEY);
            return queue.size();
        } catch (Exception e) {
            log.warn("Failed to read dead-letter queue size", e);
            return 0L;
        }
    }
}
