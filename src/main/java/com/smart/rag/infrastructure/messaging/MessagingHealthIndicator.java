package com.smart.rag.infrastructure.messaging;

import com.smart.rag.chat.service.MessageDeadLetterQueue;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.util.Map;

/**
 * Messaging bus health check — monitors producer connectivity, subscription activity,
 * and circuit breaker state via Spring Boot Actuator /health endpoint.
 * <p>
 * Also surfaces the legacy Redis {@link MessageDeadLetterQueue} depth as a detail
 * ({@code legacyDlqSize}) to support the Phase D precondition verification
 * (legacy DLQ 0 new entries over a 7-day window). This is <b>detail-only</b> —
 * a sustained non-zero size is NOT treated as unhealthy because
 * {@code DeadLetterRetryScheduler} continuously drains the queue; DOWN remains
 * producer-unreachable (and circuit-breaker-open) only.
 */
public class MessagingHealthIndicator extends AbstractHealthIndicator {

    private final MessageBusManagement busManagement;
    private final MessageDeadLetterQueue deadLetterQueue;

    public MessagingHealthIndicator(MessageBusManagement busManagement,
                                    MessageDeadLetterQueue deadLetterQueue) {
        this.busManagement = busManagement;
        this.deadLetterQueue = deadLetterQueue;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        // 1. Producer connectivity
        boolean producerHealthy = busManagement.isProducerHealthy();
        if (!producerHealthy) {
            builder.down()
                .withDetail("producer", "unreachable")
                .withDetail("action", "Check RocketMQ Broker/Proxy connectivity")
                .withDetail("legacyDlqSize", deadLetterQueue.size());
            return;
        }

        // 2. Active subscriptions
        int activeSubscriptions = busManagement.activeSubscriptionCount();
        if (activeSubscriptions == 0) {
            builder.up()
                .withDetail("producer", "healthy")
                .withDetail("subscriptions", "none")
                .withDetail("warning", "No active subscriptions registered")
                .withDetail("legacyDlqSize", deadLetterQueue.size());
            return;
        }

        // 3. Circuit breaker state
        Map<String, String> circuitBreakerState = busManagement.circuitBreakerState();
        boolean hasOpenBreaker = !circuitBreakerState.isEmpty() && circuitBreakerState.values().stream()
                .anyMatch(s -> "open".equals(s) || "half_open".equals(s));

        (hasOpenBreaker ? builder.down() : builder.up())
            .withDetail("producer", "healthy")
            .withDetail("activeSubscriptions", activeSubscriptions)
            .withDetail("circuitBreaker", circuitBreakerState)
            .withDetail("legacyDlqSize", deadLetterQueue.size());
    }
}
