package com.smart.rag.infrastructure.messaging;

import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                                  ClientServiceProvider provider) {
        return new RocketMQMessageBus(properties, codec, provider);
    }

    @Bean
    HealthIndicator messagingHealthIndicator(MessageBusManagement busManagement) {
        return new MessagingHealthIndicator(busManagement);
    }
}
