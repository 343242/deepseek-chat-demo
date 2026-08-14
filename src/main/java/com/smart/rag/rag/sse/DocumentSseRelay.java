package com.smart.rag.rag.sse;

import com.smart.rag.rag.event.DocumentStatusChangedEvent;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 文档状态变更的 Redis Pub/Sub 桥接。
 * <p>
 * 把进程内 Spring {@link DocumentStatusChangedEvent} 广播到 Redis Topic，使多实例部署下
 * 任意实例触发的状态变更都能被持有 SSE 连接的实例收到并转发。
 *
 * <h3>数据流</h3>
 * <pre>
 * EtlStatusManager 发 DocumentStatusChangedEvent (Spring 进程内事件)
 *   → @EventListener 收到 → RTopic.publish (Redis Pub/Sub 扇出)
 *     → 各实例 RTopic listener → DocumentSseRegistry.send (查本地连接转发)
 * </pre>
 *
 * <h3>为何不用 Redis Stream</h3>
 * Stream consumer group 是 competing-consumer（同 group 内消息只被一台消费），无法扇出。
 * SSE 推送需要"每实例都收到"（用户连接可能在任意一台），Pub/Sub 天然适合。
 *
 * <h3>序列化</h3>
 * 用专用 {@link DocumentStatusCodec}（不开 default typing 的纯净 Jackson），而非全局
 * {@code JsonJacksonCodec}。后者开启 {@code DefaultTyping.NON_FINAL}（{@code @class} 属性），
 * 而 {@link DocumentStatusChangedEvent} 是 record（隐式 final）—— NON_FINAL 跳过 final、
 * 序列化不写 {@code @class}；RTopic 的 Pub/Sub decode 按 {@code Object} 解码、强依赖 {@code @class}，
 * 往返必失败（{@code InvalidTypeIdException}）。详见 {@link DocumentStatusCodec}。
 *
 * @see DocumentSseRegistry
 * @see DocumentStatusCodec
 */
@Component
public class DocumentSseRelay {

    private static final Logger log = LoggerFactory.getLogger(DocumentSseRelay.class);

    /** Redis Pub/Sub channel：文档状态变更广播 */
    static final String TOPIC = "smart-rag:doc-status";

    private final RedissonClient redisson;
    private final DocumentSseRegistry registry;
    /** 专用 codec：record 经 RTopic 往返必备，绕开全局 codec 的 default typing（见类注释） */
    private final Codec codec = new DocumentStatusCodec();

    public DocumentSseRelay(RedissonClient redisson, DocumentSseRegistry registry) {
        this.redisson = redisson;
        this.registry = registry;
    }

    /**
     * 启动时订阅 Redis Topic：收到广播 → 转发给本地 SSE 连接（无连接则静默跳过）。
     */
    @PostConstruct
    void subscribe() {
        RTopic topic = redisson.getTopic(TOPIC, codec);
        topic.addListener(DocumentStatusChangedEvent.class, (channel, event) -> registry.send(event));
        log.info("Subscribed to Redis topic '{}' for document status SSE broadcast", TOPIC);
    }

    /**
     * 监听进程内 Spring 事件 → 广播到 Redis Topic。
     * <p>
     * {@code @Async} 避免阻塞 ETL 回调线程（Redis PUBLISH 通常亚毫秒，但网络抖动时隔离更安全）。
     * 若无任何实例订阅（subscribers < 1），仅 debug 日志——该事件可能发生在所有实例重启窗口期。
     */
    @EventListener
    @Async
    public void onStatusChanged(DocumentStatusChangedEvent event) {
        long subscribers = redisson.getTopic(TOPIC, codec).publish(event);
        if (subscribers < 1) {
            log.debug("No instance subscribed to '{}' (doc {} status {} event dropped)",
                    TOPIC, event.documentId(), event.status());
        }
    }
}
