package com.smart.rag.chat.service;

import com.smart.rag.common.util.ConversationIdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 死信队列定时重试 — 每分钟扫描并重试失败的消息持久化
 */
@Component
public class DeadLetterRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRetryScheduler.class);
    private static final int BATCH_SIZE = 50;

    private final MessageDeadLetterQueue deadLetterQueue;
    private final ChatConversationHelper conversationHelper;

    public DeadLetterRetryScheduler(MessageDeadLetterQueue deadLetterQueue,
                                     ChatConversationHelper conversationHelper) {
        this.deadLetterQueue = deadLetterQueue;
        this.conversationHelper = conversationHelper;
    }

    @Scheduled(fixedDelay = 60_000)
    public void retryFailedMessages() {
        List<DeadLetterEntry> entries = deadLetterQueue.drain(BATCH_SIZE);
        if (entries.isEmpty()) return;

        log.info("Retrying {} dead-letter entries", entries.size());

        for (DeadLetterEntry entry : entries) {
            try {
                conversationHelper.saveMessagesAndNotify(
                        entry.conversationId(), entry.userContent(), entry.assistantContent(),
                        entry.modelId(), null, entry.durationMs());
                log.info("Dead-letter retry succeeded: conversationId={}, attempts={}",
                        ConversationIdUtil.mask(entry.conversationId()), entry.retryCount() + 1);
            } catch (Exception e) {
                entry.incrementRetry();
                if (entry.isRetryable()) {
                    deadLetterQueue.enqueue(entry);
                    log.warn("Dead-letter retry failed (attempt {}): conversationId={}",
                            entry.retryCount(), ConversationIdUtil.mask(entry.conversationId()), e);
                } else {
                    log.error("Dead-letter exhausted after {} retries, discarding: conversationId={}",
                            entry.retryCount(), ConversationIdUtil.mask(entry.conversationId()), e);
                }
            }
        }

        long failures = deadLetterQueue.getAndResetFailureCount();
        if (failures > 0) {
            log.info("Message persistence failures since last check: {}", failures);
        }
    }
}
