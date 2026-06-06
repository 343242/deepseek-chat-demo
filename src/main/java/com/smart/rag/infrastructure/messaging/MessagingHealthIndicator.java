package com.smart.rag.infrastructure.messaging;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * Messaging bus health check — monitors producer connectivity, subscription activity,
 * and circuit breaker state via Spring Boot Actuator /health endpoint.
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
                .withDetail("action", "Check RocketMQ Broker/Proxy connectivity");
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
        String circuitBreakerState = busManagement.circuitBreakerState();
        boolean hasOpenBreaker = circuitBreakerState.contains("open")
            || circuitBreakerState.contains("half_open");

        (hasOpenBreaker ? builder.down() : builder.up())
            .withDetail("producer", "healthy")
            .withDetail("activeSubscriptions", activeSubscriptions)
            .withDetail("circuitBreaker", circuitBreakerState);
    }
}
