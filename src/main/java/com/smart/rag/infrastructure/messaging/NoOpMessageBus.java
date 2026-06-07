package com.smart.rag.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * No-op {@link MessageBus} — active when messaging is disabled.
 * <p>
 * Business code injects {@link MessageBus} without null-checks;
 * all operations are safe no-ops.
 */
public class NoOpMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(NoOpMessageBus.class);

    @Override
    public String send(MessageEnvelope<?> messageEnvelope) {
        log.debug("Messaging disabled, send ignored: topic={}", messageEnvelope.topic());
        return "no-op";
    }

    @Override
    public CompletableFuture<String> sendAsync(MessageEnvelope<?> messageEnvelope) {
        log.debug("Messaging disabled, sendAsync ignored: topic={}", messageEnvelope.topic());
        return CompletableFuture.completedFuture("no-op");
    }

    @Override
    public <T> Subscription subscribe(String topic, String group,
                                      ConsumerConfig config,
                                      Class<T> payloadType,
                                      MessageHandler<T> handler) {
        log.info("Messaging disabled, subscribe ignored: topic={}, group={}", topic, group);
        return new NoOpSubscription(topic, group);
    }

    @Override
    public void shutdown() {
        // no-op
    }

    @Override
    public void sendAfterCommit(MessageEnvelope<?> messageEnvelope) {
        log.debug("Messaging disabled, sendAfterCommit ignored: topic={}", messageEnvelope.topic());
    }

    private record NoOpSubscription(String topic, String group) implements Subscription {

        @Override
        public boolean isActive() { return false; }

        @Override
        public void pause() { /* no-op */ }

        @Override
        public void resume() { /* no-op */ }

        @Override
        public void close() { /* no-op, idempotent */ }
    }
}
