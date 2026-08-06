package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.MessagingException;
import jakarta.annotation.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Message bus SPI — unified entry point for all messaging operations.
 * <p>
 * Implementation: RedisStreamMessageBus（Redis 8 Stream 消息总线）。
 * All thrown exceptions must be {@link MessagingException} subclasses.
 */
public interface MessageBus {

    /** Synchronous send, returns transport-level message ID */
    String send(MessageEnvelope<?> messageEnvelope);

    /** Asynchronous send, returns transport-level message ID */
    CompletableFuture<String> sendAsync(MessageEnvelope<?> messageEnvelope);

    /**
     * Subscribe to a topic with a consumer group.
     *
     * @param topic       target Topic
     * @param group       consumer group name
     * @param config      consumer configuration (mode, concurrency, etc.)
     * @param payloadType payload type for deserialization (needed due to type erasure)
     * @param handler     message handler
     * @return subscription handle for lifecycle management
     */
    <T> Subscription subscribe(String topic, String group,
                               ConsumerConfig config,
                               Class<T> payloadType,
                               MessageHandler<T> handler);

    /** Shutdown: stop all consumers, release connections */
    void shutdown();

    /**
     * Send message after current Spring transaction commits.
     * Non-transactional contexts fall back to immediate send.
     */
    default void sendAfterCommit(MessageEnvelope<?> messageEnvelope) {
        send(messageEnvelope);
    }

    /** Dead letter operations (optional, for ops tooling) */
    default @Nullable DeadLetterOperations deadLetterOperations() {
        return null;
    }
}
