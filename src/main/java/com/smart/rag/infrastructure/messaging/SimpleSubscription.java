package com.smart.rag.infrastructure.messaging;

import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple-mode subscription — manages SimpleConsumer + receive executor lifecycle.
 * <p>
 * Bundles SimpleConsumer-specific state into {@link SimpleConsumerContext}.
 */
final class SimpleSubscription extends RocketMQSubscription {

    private static final Logger log = LoggerFactory.getLogger(SimpleSubscription.class);

    /** Bundles SimpleConsumer-specific lifecycle state. */
    record SimpleConsumerContext(
        SimpleConsumer consumer,
        ExecutorService receiveExecutor,
        SimpleConsumerReceiveLoop<?> receiveLoop,
        AtomicBoolean runningFlag,
        AtomicLong closeTimeoutMsHolder
    ) {}

    private final SimpleConsumerContext simpleCtx;

    SimpleSubscription(String topic, String group, SimpleConsumerContext simpleCtx) {
        super(topic, group);
        this.simpleCtx = simpleCtx;
    }

    @Override
    public void close(Duration timeout) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        simpleCtx.closeTimeoutMsHolder().set(timeout.toMillis());
        simpleCtx.runningFlag().set(false);
        simpleCtx.receiveLoop().shutdownProcessingPool();
        ExecutorService executor = simpleCtx.receiveExecutor();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Receive executor did not terminate within {}ms: topic={}",
                    timeout.toMillis(), topic());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            simpleCtx.consumer().close();
        } catch (Exception e) {
            log.warn("Error closing simpleConsumer: topic={}, group={}", topic(), group(), e);
        }
    }
}
