package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;

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
                throw new ClientException(MessagingErrorCode.INVALID_CONFIG, "并发数必须大于等于1");
            }
            if (concurrency > 256) {
                throw new ClientException(MessagingErrorCode.INVALID_CONFIG, "并发数不能超过256");
            }
            if (batchSize < 1) {
                throw new ClientException(MessagingErrorCode.INVALID_CONFIG, "批量大小必须大于等于1");
            }
            if (batchSize > 256) {
                throw new ClientException(MessagingErrorCode.INVALID_CONFIG, "批量大小不能超过256");
            }
            if (invisibleDuration != null && invisibleDuration.compareTo(Duration.ofSeconds(20)) < 0) {
                throw new ClientException(MessagingErrorCode.INVALID_CONFIG, "不可见时间不能小于20秒");
            }
            if (invisibleDuration != null && invisibleDuration.compareTo(Duration.ofHours(2)) > 0) {
                throw new ClientException(MessagingErrorCode.INVALID_CONFIG, "不可见时间不能超过2小时");
            }
            if (retryPolicy != null && retryPolicy.maxRetries() > 100) {
                throw new ClientException(MessagingErrorCode.INVALID_CONFIG, "最大重试次数不能超过100");
            }
            if (tagExpression == null || tagExpression.isBlank()) {
                tagExpression = "*";
            }
            if (retryPolicy == null) {
                retryPolicy = RetryPolicy.DEFAULT;
            }
            if (consumeTimeout == null) {
                consumeTimeout = Duration.ofMinutes(15);
            }
            return new ConsumerConfig(consumerMode, concurrency, batchSize,
                consumeTimeout, invisibleDuration, tagExpression, retryPolicy);
        }
    }
}
