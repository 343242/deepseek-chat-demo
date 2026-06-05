package com.smart.rag.infrastructure.messaging;

import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Messaging bus auto-configuration.
 * <p>
 * When {@code app.messaging.enabled=true}: creates {@link ClientServiceProvider},
 * {@link RocketMQMessageBus} (destroyMethod="shutdown"), and {@link MessagingHealthIndicator}.
 * <p>
 * When {@code app.messaging.enabled=false} (default): creates {@link NoOpMessageBus}.
 * Business code injects {@link MessageBus} without null checks.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Configuration
    @ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
    static class EnabledConfiguration {

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
        @ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
        HealthIndicator messagingHealthIndicator(MessageBusManagement busManagement) {
            return new MessagingHealthIndicator(busManagement);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "app.messaging.enabled",
        havingValue = "false", matchIfMissing = true)
    static class DisabledConfiguration {

        @Bean
        MessageBus noOpMessageBus() {
            return new NoOpMessageBus();
        }
    }
}
