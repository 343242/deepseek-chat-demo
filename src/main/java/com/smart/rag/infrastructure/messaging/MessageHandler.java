package com.smart.rag.infrastructure.messaging;

/**
 * Message handler — business code implements this to process messages.
 * <p>
 * Error propagation: thrown exception = consume failure. Must be idempotent (at-least-once delivery).
 */
@FunctionalInterface
public interface MessageHandler<T> {
    void onMessage(MessageEnvelope<T> messageEnvelope);
}
