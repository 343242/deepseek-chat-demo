package com.smart.rag.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
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
 * and {@link MessagingHealthIndicator}. Business code injects {@link MessageBus} directly.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

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
    HealthIndicator messagingHealthIndicator(MessageBusManagement busManagement) {
        return new MessagingHealthIndicator(busManagement);
    }
}
