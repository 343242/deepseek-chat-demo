package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.messaging.redis.PelRecoverySweeper;
import com.smart.rag.infrastructure.messaging.redis.RedisStreamConsumerConnections;
import com.smart.rag.infrastructure.messaging.redis.RedisStreamDeadLetterWriter;
import com.smart.rag.infrastructure.messaging.redis.RedisStreamKeys;
import com.smart.rag.infrastructure.messaging.redis.RedisStreamMessageBus;
import com.smart.rag.infrastructure.messaging.redis.RetrySweeper;
import com.smart.rag.infrastructure.messaging.redis.StreamTrimTask;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;

/**
 * Messaging bus auto-configuration — Redis Stream 唯一后端（无 backend 开关、无并存实现）。
 * <p>
 * 装配：{@link RedisStreamMessageBus}（destroyMethod="shutdown"，唯一 MessageBus 实现）、
 * 独立消费连接池（P1-4）、RetrySweeper / PelRecoverySweeper / StreamTrimTask（SmartLifecycle，
 * phase DEFAULT-200 先于 consumer pool 关闭，P2-9）、共享组件（RedisStreamKeys / BackoffSchedule /
 * ZSetDelayQueue，child 2 OutboxRelay 复用）、MessagingHealthIndicator、TracePropagator（D-6）。
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

    /**
     * P1-4（强制）：XREADGROUP BLOCK 独立连接池（share-native-connection=false + pool）。
     * 普通类包装（非 RedisConnectionFactory 子类型），不触发 Boot RedisAutoConfiguration 退避。
     */
    @Bean(destroyMethod = "close")
    RedisStreamConsumerConnections redisStreamConsumerConnections(RedisProperties redisProperties,
                                                                  MessagingProperties properties) {
        return new RedisStreamConsumerConnections(redisProperties, properties);
    }

    /** key 解析集中化（design §1）。 */
    @Bean
    RedisStreamKeys redisStreamKeys(MessagingProperties properties) {
        return new RedisStreamKeys(properties);
    }

    /** 共享退避表（design §4.4）——child 2 OutboxRelay 同 bean 复用。 */
    @Bean
    BackoffSchedule backoffSchedule(MessagingProperties properties) {
        return new BackoffSchedule(properties.backoffMs());
    }

    /** ZSET 延迟队列通用组件（design §4.5，P2-11）——child 2 Outbox 重试复用。 */
    @Bean
    ZSetDelayQueue zSetDelayQueue(StringRedisTemplate redisTemplate) {
        return new ZSetDelayQueue(redisTemplate);
    }

    /** DLQ 写入（P2-8 MAXLEN 统一）。 */
    @Bean
    RedisStreamDeadLetterWriter redisStreamDeadLetterWriter(StringRedisTemplate redisTemplate,
                                                            MessagingProperties properties) {
        return new RedisStreamDeadLetterWriter(redisTemplate, properties);
    }

    @Bean
    RetrySweeper retrySweeper(ZSetDelayQueue zSetDelayQueue, RedisStreamKeys keys,
                              BackoffSchedule backoffSchedule, MessagingProperties properties,
                              @Autowired(required = false) MeterRegistry meterRegistry,
                              RedisStreamDeadLetterWriter deadLetterWriter) {
        return new RetrySweeper(zSetDelayQueue, keys, backoffSchedule, properties,
            new MessagingMetrics(meterRegistry), deadLetterWriter);
    }

    @Bean
    PelRecoverySweeper pelRecoverySweeper(RedisStreamConsumerConnections connections,
                                          MessagingProperties properties,
                                          @Autowired(required = false) MeterRegistry meterRegistry) {
        return new PelRecoverySweeper(connections, properties, new MessagingMetrics(meterRegistry));
    }

    @Bean
    StreamTrimTask streamTrimTask(RedisStreamConsumerConnections connections,
                                  MessagingProperties properties,
                                  @Autowired(required = false) MeterRegistry meterRegistry) {
        return new StreamTrimTask(connections, properties, new MessagingMetrics(meterRegistry));
    }

    /**
     * 唯一 MessageBus 实现（Redis Stream）。bean 类型用具体类：同一实例同时满足
     * {@link MessageBus} 与 {@link MessageBusManagement} 注入（health indicator 需要后者）。
     */
    @Bean(destroyMethod = "shutdown")
    RedisStreamMessageBus messageBus(MessagingProperties properties,
                                     StringRedisTemplate redisTemplate,
                                     MessagePayloadCodec codec,
                                     RedisStreamConsumerConnections connections,
                                     RedisStreamKeys keys,
                                     RedisStreamDeadLetterWriter deadLetterWriter,
                                     RetrySweeper retrySweeper,
                                     PelRecoverySweeper pelRecoverySweeper,
                                     StreamTrimTask streamTrimTask,
                                     @Autowired(required = false) MeterRegistry meterRegistry,
                                     @Autowired(required = false) @Nullable TracePropagator propagator) {
        validateMessagingConfig(properties);
        return new RedisStreamMessageBus(properties, redisTemplate, codec, null,
            propagator, meterRegistry, connections, keys, deadLetterWriter,
            retrySweeper, pelRecoverySweeper, streamTrimTask);
    }

    /**
     * 启动期断言（design §10，fail-fast，违反即启动失败）：
     * <ol>
     *   <li>{@code maxAttempts <= backoff-ms.size()}</li>
     *   <li>{@code pelMinIdleMs > max(各 consumer invisibleDuration) + 5min}
     *       （当前 ETL 30min；默认 40min，margin 10min ✓）</li>
     * </ol>
     * （{@code retry-poll-interval} 与首档退避的关系非 fail-fast：sweep 粒度 5s 下首档 1s
     * 实际生效 ≤5s，可接受精度，见 design §10。）
     */
    static void validateMessagingConfig(MessagingProperties properties) {
        MessagingProperties.RedisStreamConfig redis = properties.redis();
        if (redis.maxAttempts() > properties.backoffMs().length) {
            throw new IllegalStateException(String.format(
                "app.messaging.redis.max-attempts (%d) 超出退避表档位数 backoff-ms.size() (%d)，启动失败",
                redis.maxAttempts(), properties.backoffMs().length));
        }
        long minPelMinIdleMs = MessagingProperties.MAX_EXPECTED_INVISIBLE_DURATION.toMillis()
            + MessagingProperties.PEL_MIN_IDLE_MARGIN.toMillis();
        if (redis.pelMinIdle().toMillis() <= minPelMinIdleMs) {
            throw new IllegalStateException(String.format(
                "app.messaging.redis.pel-min-idle-ms (%d) 必须 > max(invisibleDuration)=%d + %dms margin，启动失败",
                redis.pelMinIdle().toMillis(),
                MessagingProperties.MAX_EXPECTED_INVISIBLE_DURATION.toMillis(),
                MessagingProperties.PEL_MIN_IDLE_MARGIN.toMillis()));
        }
    }

    @Bean
    HealthIndicator messagingHealthIndicator(RedisStreamMessageBus busManagement) {
        return new MessagingHealthIndicator(busManagement);
    }

    /**
     * Phase D D-6 方案 b：基于 {@link OpenTelemetry} 的 {@link TracePropagator}。
     * <p>
     * {@code OpenTelemetry} bean 存在（Spring Boot tracing auto-config 生效，需 micrometer-tracing-bridge-otel
     * + opentelemetry-sdk）→ 真 propagator（W3C traceId 跨消息传播 + MDC 日志串联）；
     * 否则回退 {@link TracePropagator#NO_OP} 并 WARN——显式可见，避免重蹈 Phase 0「静默 NoOp」覆辙。
     */
    @Bean
    TracePropagator tracePropagator(@Autowired(required = false) OpenTelemetry openTelemetry) {
        if (openTelemetry == null) {
            log.warn("OpenTelemetry bean 未装配（tracing auto-config 未生效）— TracePropagator 回退 NO_OP，traceId 不跨消息传播");
            return TracePropagator.NO_OP;
        }
        return new OpenTelemetryTracePropagator(openTelemetry);
    }
}
