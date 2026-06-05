package com.smart.rag.infrastructure.messaging;

/**
 * Consumer mode — determines whether PushConsumer or SimpleConsumer is used.
 */
public enum ConsumerMode {
    /**
     * PushConsumer — Broker auto-pushes messages. Auto-retry, auto load-balance.
     * Suitable for predictable processing time (chat save, usage recording).
     */
    PUSH,

    /**
     * SimpleConsumer — Manual receive() + explicit ack(). Precise concurrency control.
     * Suitable for unpredictable processing time (RAG indexing with LLM calls).
     */
    SIMPLE
}
