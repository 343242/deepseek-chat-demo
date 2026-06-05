package com.smart.rag.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * No-op implementation — active when {@code app.messaging.enabled=false} (default).
 * Business code injects {@link MessageBus} without null checks; all operations are no-op.
 */
public class NoOpMessageBus implements MessageBus, MessageBusManagement {

    private static final Logger log = LoggerFactory.getLogger(NoOpMessageBus.class);

    @Override
    public String send(Message<?> message) {
        log.debug("Message bus disabled — send() no-op for topic={}", message.topic());
        return "";
    }

    @Override
    public CompletableFuture<String> sendAsync(Message<?> message) {
        log.debug("Message bus disabled — sendAsync() no-op for topic={}", message.topic());
        return CompletableFuture.completedFuture("");
    }

    @Override
    public <T> Subscription subscribe(String topic, String group,
                                      ConsumerConfig config,
                                      Class<T> payloadType,
                                      MessageHandler<T> handler) {
        log.warn("Message bus disabled — subscribe() no-op for topic={}, group={}", topic, group);
        return new NoOpSubscription(topic, group);
    }

    @Override
    public void shutdown() {
        // no-op
    }

    @Override
    public DeadLetterOperations deadLetterOperations() {
        return new NoOpDeadLetterOperations();
    }

    @Override
    public boolean isProducerHealthy() {
        return false;
    }

    @Override
    public int activeSubscriptionCount() {
        return 0;
    }

    @Override
    public String circuitBreakerState() {
        return "DISABLED";
    }

    private static final class NoOpSubscription implements Subscription {
        private final String topic;
        private final String group;

        NoOpSubscription(String topic, String group) {
            this.topic = topic;
            this.group = group;
        }

        @Override
        public String topic() { return topic; }

        @Override
        public String group() { return group; }

        @Override
        public boolean isActive() { return false; }

        @Override
        public void pause() { /* no-op */ }

        @Override
        public void resume() { /* no-op */ }

        @Override
        public void close() { /* no-op */ }
    }

    private static final class NoOpDeadLetterOperations implements DeadLetterOperations {
        @Override
        public List<Message<?>> scanDeadLetters(String topic, int count) {
            return Collections.emptyList();
        }

        @Override
        public void replayDeadLetter(String topic, String messageId) {
            log.warn("Message bus disabled — replayDeadLetter() no-op for topic={}", topic);
        }

        @Override
        public int deadLetterCount(String topic) {
            return 0;
        }
    }
}
