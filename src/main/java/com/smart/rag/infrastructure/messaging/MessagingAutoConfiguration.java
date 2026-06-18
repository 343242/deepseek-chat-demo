package com.smart.rag.infrastructure.messaging;

import com.smart.rag.chat.service.MessageDeadLetterQueue;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;

/**
 * Messaging bus auto-configuration — Phase 0 (2026-06) 起无条件装配（always-on）。
 * <p>
 * 原设计的 {@code app.messaging.enabled} 开关与 {@code NoOpMessageBus} 已移除：开关默认缺失时
 * 经 {@code matchIfMissing=true} 落到 NoOp，导致 broker 已就绪仍静默丢消息。现 {@link RocketMQMessageBus}
 * 始终装配，运行期 broker 不可达由 publisher 端 {@code MessagingException} 降级 + 熔断兜底。
 * <p>
 * Creates {@link ClientServiceProvider}, {@link RocketMQMessageBus} (destroyMethod="shutdown"),
 * {@link MessagingHealthIndicator}, and {@link TracePropagator} (D-6). Business code injects
 * {@link MessageBus} directly.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

    @Bean
    ClientServiceProvider rocketmqClientServiceProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean(destroyMethod = "shutdown")
    MessageBus rocketMQMessageBus(MessagingProperties properties,
                                  MessagePayloadCodec codec,
                                  ClientServiceProvider provider,
                                  @Autowired(required = false) MeterRegistry meterRegistry,
                                  @Autowired(required = false) @Nullable TracePropagator propagator,
                                  @Autowired(required = false) StringRedisTemplate redisTemplate) {
        return new RocketMQMessageBus(properties, codec, provider, meterRegistry, propagator, redisTemplate);
    }

    @Bean
    HealthIndicator messagingHealthIndicator(MessageBusManagement busManagement,
                                             MessageDeadLetterQueue deadLetterQueue) {
        return new MessagingHealthIndicator(busManagement, deadLetterQueue);
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
