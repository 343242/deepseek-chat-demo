package com.smart.rag.infrastructure.messaging;

/**
 * Circuit breaker state — CLOSED / OPEN / HALF_OPEN.
 */
public enum MessagingCircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
