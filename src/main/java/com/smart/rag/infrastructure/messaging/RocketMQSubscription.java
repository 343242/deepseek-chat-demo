package com.smart.rag.infrastructure.messaging;

import jakarta.annotation.Nullable;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RocketMQ subscription lifecycle — manages a single PushConsumer or SimpleConsumer.
 * <p>
 * close() is idempotent: AtomicBoolean guard ensures cleanup runs exactly once.
 * close(Duration) propagates timeout to receive executor termination and consumer close.
 */
public class RocketMQSubscription implements Subscription {

    private static final Logger log = LoggerFactory.getLogger(RocketMQSubscription.class);

    private final String topic;
    private final String group;
    @Nullable private final PushConsumer pushConsumer;
    @Nullable private final SimpleConsumer simpleConsumer;
    @Nullable private final ExecutorService receiveExecutor;
    @Nullable private final SimpleConsumerReceiveLoop<?> receiveLoop;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    @Nullable private volatile AtomicBoolean runningFlag;
    @Nullable private AtomicLong closeTimeoutMsHolder;

    RocketMQSubscription(String topic, String group,
                         @Nullable PushConsumer pushConsumer,
                         @Nullable SimpleConsumer simpleConsumer,
                         @Nullable ExecutorService receiveExecutor,
                         @Nullable SimpleConsumerReceiveLoop<?> receiveLoop) {
        this.topic = topic;
        this.group = group;
        this.pushConsumer = pushConsumer;
        this.simpleConsumer = simpleConsumer;
        this.receiveExecutor = receiveExecutor;
        this.receiveLoop = receiveLoop;
    }

    /** Backward-compatible constructor for PushConsumer (no loop). */
    RocketMQSubscription(String topic, String group,
                         @Nullable PushConsumer pushConsumer,
                         @Nullable SimpleConsumer simpleConsumer,
                         @Nullable ExecutorService receiveExecutor) {
        this(topic, group, pushConsumer, simpleConsumer, receiveExecutor, null);
    }

    void setRunningFlag(AtomicBoolean flag) {
        this.runningFlag = flag;
    }

    void setCloseTimeoutMsHolder(AtomicLong holder) {
        this.closeTimeoutMsHolder = holder;
    }

    @Override
    public String topic() { return topic; }

    @Override
    public String group() { return group; }

    @Override
    public boolean isActive() { return !closed.get(); }

    @Override
    public void pause() {
        log.warn("pause() not supported by RocketMQ 5.x — topic={}, group={}", topic, group);
    }

    @Override
    public void resume() {
        log.warn("resume() not supported by RocketMQ 5.x — topic={}, group={}", topic, group);
    }

    public void close(Duration timeout) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (closeTimeoutMsHolder != null) {
            closeTimeoutMsHolder.set(timeout.toMillis());
        }
        if (runningFlag != null) {
            runningFlag.set(false);
        }
        if (receiveLoop != null) {
            receiveLoop.shutdownProcessingPool();
        }
        if (receiveExecutor != null) {
            receiveExecutor.shutdown();
            try {
                if (!receiveExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Receive executor did not terminate within {}ms: topic={}",
                        timeout.toMillis(), topic);
                    receiveExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                receiveExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (simpleConsumer != null) {
            try {
                simpleConsumer.close();
            } catch (Exception e) {
                log.warn("Error closing simpleConsumer: topic={}, group={}", topic, group, e);
            }
        }
        if (pushConsumer != null) {
            try {
                pushConsumer.close();
            } catch (Exception e) {
                log.warn("Error closing pushConsumer: topic={}, group={}", topic, group, e);
            }
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }
}
