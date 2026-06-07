package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.MessagingErrorCode;
import com.smart.rag.infrastructure.exception.ServiceException;
import jakarta.annotation.Nullable;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
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
    @Nullable private final SimpleConsumerContext simpleCtx;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Bundles SimpleConsumer-specific lifecycle state. */
    record SimpleConsumerContext(
        org.apache.rocketmq.client.apis.consumer.SimpleConsumer consumer,
        ExecutorService receiveExecutor,
        SimpleConsumerReceiveLoop<?> receiveLoop,
        AtomicBoolean runningFlag,
        AtomicLong closeTimeoutMsHolder
    ) {}

    RocketMQSubscription(String topic, String group,
                         @Nullable PushConsumer pushConsumer,
                         @Nullable SimpleConsumerContext simpleCtx) {
        this.topic = topic;
        this.group = group;
        this.pushConsumer = pushConsumer;
        this.simpleCtx = simpleCtx;
    }

    @Override
    public String topic() { return topic; }

    @Override
    public String group() { return group; }

    @Override
    public boolean isActive() { return !closed.get(); }

    @Override
    public void pause() {
        throw new ServiceException(MessagingErrorCode.UNSUPPORTED_OPERATION,
            "当前消息订阅不支持暂停操作");
    }

    @Override
    public void resume() {
        throw new ServiceException(MessagingErrorCode.UNSUPPORTED_OPERATION,
            "当前消息订阅不支持恢复操作");
    }

    public void close(Duration timeout) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (simpleCtx != null) {
            simpleCtx.closeTimeoutMsHolder().set(timeout.toMillis());
            simpleCtx.runningFlag().set(false);
            simpleCtx.receiveLoop().shutdownProcessingPool();
            ExecutorService executor = simpleCtx.receiveExecutor();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Receive executor did not terminate within {}ms: topic={}",
                        timeout.toMillis(), topic);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            try {
                simpleCtx.consumer().close();
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
