package com.smart.rag.infrastructure.messaging;

/**
 * Message handler — business code implements this to process messages.
 * <p>
 * Named MessageHandler (not MessageListener) to avoid clash with
 * {@code org.apache.rocketmq.client.apis.consumer.MessageListener}.
 * <p>
 * Error propagation: thrown exception = consume failure. Must be idempotent (at-least-once delivery).
 */
@FunctionalInterface
public interface MessageHandler<T> {
    void onMessage(Message<T> message);
}
