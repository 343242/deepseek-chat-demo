package com.smart.rag.chat.service;

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

    private final @Nullable RedissonClient redissonClient;
    private final AtomicLong failureCounter = new AtomicLong();

    public MessageDeadLetterQueue(@Nullable RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
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
}
