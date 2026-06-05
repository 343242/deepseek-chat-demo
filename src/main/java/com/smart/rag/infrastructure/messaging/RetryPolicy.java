package com.smart.rag.infrastructure.messaging;

/**
 * Retry policy.
 * <p>
 * PushConsumer: retries managed by Broker-side state machine. maxRetries maps to
 * consumer group's maxDeliveryAttempts (Broker-side config).
 * <p>
 * SimpleConsumer: no auto-retry. Failed messages reappear after invisibleDuration.
 * Application-layer Caffeine counter tracks retry count; exceeded → ack + DLQ.
 */
public record RetryPolicy(
    int maxRetries
) {
    /** PushConsumer default: 16 delivery attempts */
    public static final RetryPolicy DEFAULT = new RetryPolicy(16);

    /** SimpleConsumer default: 5 retries */
    public static final RetryPolicy SIMPLE_DEFAULT = new RetryPolicy(5);

    /** No retry */
    public static final RetryPolicy NO_RETRY = new RetryPolicy(0);
}
