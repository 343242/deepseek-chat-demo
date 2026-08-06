package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 订阅句柄（design §3）——持 {@link RedisStreamConsumerRunner}，{@link #close()} 幂等
 * 关停消费线程池（AtomicBoolean 防重入，P2-9），并回调注销 sweeper/trim 注册。
 */
public class RedisStreamSubscription implements Subscription {

    private final String topic;
    private final String group;
    private final RedisStreamConsumerRunner<?> runner;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RedisStreamSubscription(String topic, String group,
                            RedisStreamConsumerRunner<?> runner, Runnable onClose) {
        this.topic = topic;
        this.group = group;
        this.runner = runner;
        this.onClose = onClose;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public boolean isActive() {
        return !closed.get();
    }

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
        if (closed.compareAndSet(false, true)) {
            runner.stop();
            onClose.run();
        }
    }
}
