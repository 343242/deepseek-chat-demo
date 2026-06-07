package com.smart.rag.infrastructure.messaging;

import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Push-mode subscription — manages a single PushConsumer lifecycle.
 */
final class PushSubscription extends RocketMQSubscription {

    private static final Logger log = LoggerFactory.getLogger(PushSubscription.class);

    private final PushConsumer pushConsumer;

    PushSubscription(String topic, String group, PushConsumer pushConsumer) {
        super(topic, group);
        this.pushConsumer = pushConsumer;
    }

    @Override
    public void close(Duration timeout) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            pushConsumer.close();
        } catch (Exception e) {
            log.warn("Error closing pushConsumer: topic={}, group={}", topic(), group(), e);
        }
    }
}
