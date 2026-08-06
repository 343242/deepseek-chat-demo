package com.smart.rag.infrastructure.messaging;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.util.Map;

/**
 * Messaging bus health check — monitors producer connectivity, subscription activity,
 * and circuit breaker state via Spring Boot Actuator /health endpoint.
 * <p>
 * DOWN = producer unreachable OR any send circuit breaker open/half-open.
 * (Phase D D-2/D-3: 移除了 legacy Redis DLQ depth detail —— 随 {@code MessageDeadLetterQueue} 一并退役。)
 */
public class MessagingHealthIndicator extends AbstractHealthIndicator {

    private final MessageBusManagement busManagement;

    public MessagingHealthIndicator(MessageBusManagement busManagement) {
        this.busManagement = busManagement;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        // 1. Producer connectivity
        boolean producerHealthy = busManagement.isProducerHealthy();
        if (!producerHealthy) {
            builder.down()
                .withDetail("producer", "unreachable")
                .withDetail("action", "Check Redis connectivity / messaging bus");
            return;
        }

        // 2. Active subscriptions
        int activeSubscriptions = busManagement.activeSubscriptionCount();
        if (activeSubscriptions == 0) {
            builder.up()
                .withDetail("producer", "healthy")
                .withDetail("subscriptions", "none")
                .withDetail("warning", "No active subscriptions registered");
            return;
        }

        // 3. Circuit breaker state
        Map<String, String> circuitBreakerState = busManagement.circuitBreakerState();
        boolean hasOpenBreaker = !circuitBreakerState.isEmpty() && circuitBreakerState.values().stream()
                .anyMatch(s -> "open".equals(s) || "half_open".equals(s));

        (hasOpenBreaker ? builder.down() : builder.up())
            .withDetail("producer", "healthy")
            .withDetail("activeSubscriptions", activeSubscriptions)
            .withDetail("circuitBreaker", circuitBreakerState);
    }
}
