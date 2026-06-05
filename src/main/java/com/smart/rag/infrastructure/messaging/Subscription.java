package com.smart.rag.infrastructure.messaging;

/**
 * Subscription handle — manages lifecycle of a single consumer group.
 * close() must be idempotent (second call is a no-op).
 */
public interface Subscription extends AutoCloseable {
    String topic();

    String group();

    boolean isActive();

    void pause();

    void resume();

    @Override
    void close();
}
