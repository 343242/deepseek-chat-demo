package com.smart.rag.infrastructure.messaging;

import jakarta.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * Messaging bus configuration properties.
 * <p>
 * Uses record + compact constructor for defaults, consistent with project convention
 * (see ChatFallbackProperties).
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
    boolean enabled,
    String topicPrefix,
    Duration shutdownTimeout,
    Set<String> orderedTopics,
    IdempotentConfig idempotent,
    CircuitBreakerConfig circuitBreaker,
    RocketMQConfig rocketmq
) {
    public MessagingProperties {
        if (topicPrefix == null || topicPrefix.isEmpty()) {
            topicPrefix = "SMART_RAG_";
        }
        if (shutdownTimeout == null) {
            shutdownTimeout = Duration.ofSeconds(30);
        }
        if (idempotent == null) {
            idempotent = new IdempotentConfig(true, 90000);
        }
        if (circuitBreaker == null) {
            circuitBreaker = new CircuitBreakerConfig(5, 30000);
        }
        if (rocketmq == null) {
            rocketmq = new RocketMQConfig(null, "smart-rag-producer",
                Duration.ofSeconds(3), 16, 4194304, null, null);
        }
    }

    /** Idempotent check configuration */
    public record IdempotentConfig(
        boolean enabled,
        long ttlSeconds
    ) {
        public IdempotentConfig {
            if (ttlSeconds <= 0) {
                ttlSeconds = 90000;
            }
        }
    }

    /** Circuit breaker configuration */
    public record CircuitBreakerConfig(
        int failureThreshold,
        long cooldownMillis
    ) {
        public CircuitBreakerConfig {
            if (failureThreshold <= 0) {
                failureThreshold = 5;
            }
            if (cooldownMillis <= 0) {
                cooldownMillis = 30000;
            }
        }
    }

    /** RocketMQ 5.x client configuration */
    public record RocketMQConfig(
        String endpoints,
        String producerGroup,
        Duration requestTimeout,
        int maxDeliveryAttempts,
        int maxMessageSize,
        @Nullable String accessKey,
        @Nullable String secretKey
    ) {
        public RocketMQConfig {
            if (producerGroup == null || producerGroup.isEmpty()) {
                producerGroup = "smart-rag-producer";
            }
            if (requestTimeout == null) {
                requestTimeout = Duration.ofSeconds(3);
            }
            if (maxDeliveryAttempts <= 0) {
                maxDeliveryAttempts = 16;
            }
            if (maxMessageSize <= 0) {
                maxMessageSize = 4194304;
            }
        }
    }
}
