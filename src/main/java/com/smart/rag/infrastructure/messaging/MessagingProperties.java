package com.smart.rag.infrastructure.messaging;

import jakarta.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;
import java.util.Collections;

/**
 * Messaging bus configuration properties.
 * <p>
 * Uses record + compact constructor for defaults, consistent with project convention
 * (see ChatFallbackProperties).
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
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
        if (orderedTopics == null) {
            orderedTopics = Collections.emptySet();
        }
        if (idempotent == null) {
            idempotent = new IdempotentConfig(true, 90000);
        }
        if (circuitBreaker == null) {
            circuitBreaker = new CircuitBreakerConfig(5, 30000);
        }
        if (rocketmq == null) {
            rocketmq = new RocketMQConfig(null, null,
                null, 0, 0, null, null, null);
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
        @Nullable Boolean enableSsl,
        @Nullable String accessKey,
        @Nullable String secretKey
    ) {
        public static final String DEFAULT_PRODUCER_GROUP = "smart-rag-producer";

        public RocketMQConfig {
            if (producerGroup == null || producerGroup.isEmpty()) {
                producerGroup = DEFAULT_PRODUCER_GROUP;
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

        @Override
        public String toString() {
            return "RocketMQConfig["
                + "endpoints=" + endpoints
                + ", producerGroup=" + producerGroup
                + ", requestTimeout=" + requestTimeout
                + ", maxDeliveryAttempts=" + maxDeliveryAttempts
                + ", maxMessageSize=" + maxMessageSize
                + ", enableSsl=" + enableSsl
                + ", accessKey=" + (accessKey != null ? "***" : "null")
                + ", secretKey=" + (secretKey != null ? "***" : "null")
                + ']';
        }
    }
}
