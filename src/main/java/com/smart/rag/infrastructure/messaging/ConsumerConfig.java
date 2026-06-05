package com.smart.rag.infrastructure.messaging;

import java.time.Duration;

/**
 * Consumer configuration — per-subscription settings.
 */
public record ConsumerConfig(
    ConsumerMode consumerMode,
    int concurrency,
    int batchSize,
    Duration consumeTimeout,
    Duration invisibleDuration,
    String tagExpression,
    RetryPolicy retryPolicy
) {
    public static final ConsumerConfig DEFAULT = new ConsumerConfig(
        ConsumerMode.PUSH, 20, 32,
        Duration.ofMinutes(15), Duration.ofMinutes(10),
        "*", RetryPolicy.DEFAULT);

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ConsumerMode consumerMode = ConsumerMode.PUSH;
        private int concurrency = 20;
        private int batchSize = 32;
        private Duration consumeTimeout = Duration.ofMinutes(15);
        private Duration invisibleDuration = Duration.ofMinutes(10);
        private String tagExpression = "*";
        private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;

        public Builder consumerMode(ConsumerMode consumerMode) {
            this.consumerMode = consumerMode;
            return this;
        }

        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder consumeTimeout(Duration consumeTimeout) {
            this.consumeTimeout = consumeTimeout;
            return this;
        }

        public Builder invisibleDuration(Duration invisibleDuration) {
            this.invisibleDuration = invisibleDuration;
            return this;
        }

        public Builder tagExpression(String tagExpression) {
            this.tagExpression = tagExpression;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public ConsumerConfig build() {
            if (concurrency < 1) {
                throw new IllegalArgumentException("concurrency must be >= 1");
            }
            if (concurrency > 256) {
                throw new IllegalArgumentException("concurrency must be <= 256");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be >= 1");
            }
            if (batchSize > 256) {
                throw new IllegalArgumentException("batchSize must be <= 256");
            }
            if (invisibleDuration != null && invisibleDuration.compareTo(Duration.ofSeconds(20)) < 0) {
                throw new IllegalArgumentException("invisibleDuration must be >= 20s (RocketMQ minimum)");
            }
            if (invisibleDuration != null && invisibleDuration.compareTo(Duration.ofHours(2)) > 0) {
                throw new IllegalArgumentException("invisibleDuration must be <= 2h");
            }
            if (retryPolicy != null && retryPolicy.maxRetries() > 100) {
                throw new IllegalArgumentException("maxRetries must be <= 100");
            }
            if (tagExpression == null || tagExpression.isBlank()) {
                tagExpression = "*";
            }
            return new ConsumerConfig(consumerMode, concurrency, batchSize,
                consumeTimeout, invisibleDuration, tagExpression, retryPolicy);
        }
    }
}
