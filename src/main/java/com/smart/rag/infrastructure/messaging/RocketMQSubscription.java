package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sealed subscription hierarchy — PushSubscription or SimpleSubscription.
 * <p>
 * Common lifecycle state (topic, group, closed flag) lives here;
 * consumer-type-specific cleanup is in each permitted subclass.
 * close() is idempotent via AtomicBoolean guard.
 */
public abstract sealed class RocketMQSubscription implements Subscription
    permits PushSubscription, SimpleSubscription {

    private static final Logger log = LoggerFactory.getLogger(RocketMQSubscription.class);

    private final String topic;
    private final String group;
    protected final AtomicBoolean closed = new AtomicBoolean(false);

    RocketMQSubscription(String topic, String group) {
        this.topic = topic;
        this.group = group;
    }

    @Override
    public final String topic() { return topic; }

    @Override
    public final String group() { return group; }

    @Override
    public final boolean isActive() { return !closed.get(); }

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

    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }

    public abstract void close(Duration timeout);
}
