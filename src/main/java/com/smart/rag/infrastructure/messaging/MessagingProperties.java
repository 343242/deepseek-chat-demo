package com.smart.rag.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;
import java.util.Collections;

/**
 * Messaging bus configuration properties.
 * <p>
 * Uses record + compact constructor for defaults, consistent with project convention
 * (see ChatFallbackProperties).
 * <p>
 * Redis Stream is the only backend — no backend switch.
 * Fail-fast startup assertions live in {@link RedisStreamConfig} (design §10):
 * {@code maxAttempts <= backoff-ms.size()}, {@code pelMinIdleMs > max(invisibleDuration) + 5min},
 * {@code dlq-trim-threshold > 0}, {@code read-batch >= 1}.
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
    String topicPrefix,
    Duration shutdownTimeout,
    Set<String> orderedTopics,
    IdempotentConfig idempotent,
    CircuitBreakerConfig circuitBreaker,
    long[] backoffMs,
    RedisStreamConfig redis
) {
    /** 最大预期业务处理时长（ETL invisibleDuration 30min，见 EtlDocumentConsumer）——pel-min-idle 断言基准。 */
    static final Duration MAX_EXPECTED_INVISIBLE_DURATION = Duration.ofMinutes(30);
    /** pel-min-idle 必须高于最大处理时长的余量（design §10 启动期断言）。 */
    static final Duration PEL_MIN_IDLE_MARGIN = Duration.ofMinutes(5);

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
            idempotent = new IdempotentConfig(true, 900);
        }
        if (circuitBreaker == null) {
            circuitBreaker = new CircuitBreakerConfig(5, 30000);
        }
        if (backoffMs == null || backoffMs.length == 0) {
            backoffMs = BackoffSchedule.DEFAULT_BACKOFF_MS;
        }
        if (redis == null) {
            redis = new RedisStreamConfig();
        }
    }

    /** Idempotent check configuration */
    public record IdempotentConfig(
        boolean enabled,
        /** Redis key TTL in seconds — covers broker retry window (default 15 min) */
        long ttlSeconds
    ) {
        public IdempotentConfig {
            if (ttlSeconds <= 0) {
                ttlSeconds = 900;
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

    /**
     * Redis Stream bus configuration (design §10) — unique MQ backend config.
     * <p>
     * Startup assertions (fail-fast):
     * <ol>
     *   <li>{@code maxAttempts <= backoff-ms.size()} — attempt 档位必须落在退避表内。</li>
     *   <li>{@code pelMinIdleMs > max(各 consumer invisibleDuration) + 5min} —
     *       当前 ETL invisibleDuration = 30min，默认 pel-min-idle = 40min，margin 10min ✓。</li>
     *   <li>{@code dlq-trim-threshold > 0}、{@code read-batch >= 1}。</li>
     * </ol>
     * ({@code retry-poll-interval} 与首档退避的关系非 fail-fast：sweep 粒度 5s 下首档 1s
     * 实际生效 ≤5s，可接受精度，见 design §10。)
     */
    public record RedisStreamConfig(
        String streamPrefix,
        String dlqPrefix,
        String retryPrefix,
        String retryZsetPrefix,
        String consumerNamePrefix,
        long trimThreshold,
        long dlqTrimThreshold,
        Duration readBlock,
        int readBatch,
        Duration pelMinIdle,
        Duration retryPollInterval,
        Duration trimPollInterval,
        int maxAttempts,
        Duration retryHashTtl,
        ReconnectBackoffConfig reconnectBackoff,
        ConsumerConnectionConfig consumer
    ) {
        /** 全默认构造（compact 构造器补齐默认值）。 */
        public RedisStreamConfig() {
            this(null, null, null, null, null, 0, 0, null, 0, null, null, null, 0, null, null, null);
        }

        public RedisStreamConfig {
            if (streamPrefix == null || streamPrefix.isEmpty()) {
                streamPrefix = "stream:";
            }
            if (dlqPrefix == null || dlqPrefix.isEmpty()) {
                dlqPrefix = "dlq:";
            }
            if (retryPrefix == null || retryPrefix.isEmpty()) {
                retryPrefix = "retry:";
            }
            if (retryZsetPrefix == null || retryZsetPrefix.isEmpty()) {
                retryZsetPrefix = "retry-zset:";
            }
            if (consumerNamePrefix == null || consumerNamePrefix.isEmpty()) {
                consumerNamePrefix = "app:";
            }
            if (trimThreshold <= 0) {
                trimThreshold = 100_000;
            }
            if (dlqTrimThreshold <= 0) {
                dlqTrimThreshold = 50_000;
            }
            if (readBlock == null || readBlock.isZero() || readBlock.isNegative()) {
                readBlock = Duration.ofMillis(2000);
            }
            if (readBatch <= 0) {
                readBatch = 32;
            }
            if (pelMinIdle == null || pelMinIdle.isZero() || pelMinIdle.isNegative()) {
                pelMinIdle = Duration.ofMinutes(40);
            }
            if (retryPollInterval == null || retryPollInterval.isZero() || retryPollInterval.isNegative()) {
                retryPollInterval = Duration.ofSeconds(5);
            }
            if (trimPollInterval == null || trimPollInterval.isZero() || trimPollInterval.isNegative()) {
                trimPollInterval = Duration.ofSeconds(60);
            }
            if (maxAttempts <= 0) {
                maxAttempts = 16;
            }
            if (retryHashTtl == null || retryHashTtl.isZero() || retryHashTtl.isNegative()) {
                retryHashTtl = Duration.ofHours(2);
            }
            if (reconnectBackoff == null) {
                reconnectBackoff = new ReconnectBackoffConfig();
            }
            if (consumer == null) {
                consumer = new ConsumerConnectionConfig();
            }
        }
    }

    /** pollLoop 连接级失败退避重连（design §3 Redis 故障韧性）。 */
    public record ReconnectBackoffConfig(
        long initialMs,
        double multiplier,
        long maxMs,
        double jitterFactor
    ) {
        /** 全默认构造（compact 构造器补齐默认值）。 */
        public ReconnectBackoffConfig() {
            this(0, 0, 0, -1);
        }

        public ReconnectBackoffConfig {
            if (initialMs <= 0) {
                initialMs = 1000;
            }
            if (multiplier <= 1.0) {
                multiplier = 2.0;
            }
            if (maxMs <= 0 || maxMs < initialMs) {
                maxMs = 30_000;
            }
            if (jitterFactor < 0 || jitterFactor > 1) {
                jitterFactor = 0.2;
            }
        }
    }

    /**
     * 消费连接隔离（P1-4，强制）：XREADGROUP BLOCK 在独立 {@code LettuceConnectionFactory}
     * （share-native-connection=false + pool），不得占用业务共享 Redis 连接。
     */
    public record ConsumerConnectionConfig(
        boolean shareNativeConnection,
        int poolMaxActive,
        int poolMaxIdle
    ) {
        /** 全默认构造（compact 构造器补齐默认值）；shareNativeConnection 必须 false（P1-4 强制）。 */
        public ConsumerConnectionConfig() {
            this(false, 0, 0);
        }

        public ConsumerConnectionConfig {
            if (poolMaxActive <= 0) {
                poolMaxActive = 32;
            }
            if (poolMaxIdle <= 0) {
                poolMaxIdle = 32;
            }
        }
    }
}
