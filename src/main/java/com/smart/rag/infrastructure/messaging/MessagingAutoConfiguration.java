package com.smart.rag.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Messaging bus auto-configuration.
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
                                  @Autowired(required = false) StringRedisTemplate redisTemplate) {
        RocketMQMessageBus bus = new RocketMQMessageBus(properties, codec, provider, meterRegistry);
        if (redisTemplate != null) {
            bus.setRedisTemplate(redisTemplate);
        }
        return bus;
    }

    @Bean
    HealthIndicator messagingHealthIndicator(MessageBusManagement busManagement) {
        return new MessagingHealthIndicator(busManagement);
    }
}
