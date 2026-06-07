package com.smart.rag.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;

/**
 * Messaging bus auto-configuration — active when {@code app.messaging.enabled=true}.
 * <p>
 * Creates {@link ClientServiceProvider}, {@link RocketMQMessageBus} (destroyMethod="shutdown"),
 * and {@link MessagingHealthIndicator}. Business code injects {@link MessageBus} directly.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
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

    /**
     * No-op fallback — active when messaging is disabled or not configured.
     * Ensures {@link MessageBus} is always available for injection.
     */
    @Configuration
    @ConditionalOnProperty(name = "app.messaging.enabled",
        havingValue = "false", matchIfMissing = true)
    static class NoOpMessagingConfiguration {
        @Bean
        MessageBus noOpMessageBus() {
            return new NoOpMessageBus();
        }
    }
}
