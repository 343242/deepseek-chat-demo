package com.smart.rag.infrastructure.messaging.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.DeadLetterOperations;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.Subscription;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbox 装饰器（design §2）——把三个 publisher 的投递可靠性从"内存层尽力而为"升级为
 * "DB 事务级最终一致"（R1）。{@code MessageBus} bean 由本类（{@code @Primary}）装饰 delegate
 * {@code RedisStreamMessageBus}，publisher 业务逻辑零改动。
 * <p>
 * <b>语义契约</b>：
 * <ul>
 *   <li>{@code send()}：INSERT outbox 行（~1ms）→ 查共享熔断门控 → 有界 executor 上异步即时投递
 *       （{@code immediateRetryCount} 次重试，间隔 {@code immediateRetryIntervalMs}）；成功 DELETE 行，
 *       失败/拒绝 → 行留 relay（天然不丢）。<b>返回前不阻塞请求线程做 MQ IO</b>（R2）。</li>
 *   <li>{@code sendAsync()}：同样经 outbox 托管（design §6.1——直接委托会静默绕过持久化）。
 *       future 表示即时投递 best-effort 结果：成功 complete(delegateId)；即时重试耗尽
 *       completeExceptionally——但行留 relay 补投，消息不丢。</li>
 *   <li>{@code subscribe()}/{@code shutdown()}/{@code deadLetterOperations()}：直接委托 delegate。</li>
 * </ul>
 * <b>有界 executor 拒绝语义（R2）</b>：拒绝 = 行不投递、继续留 PG，relay 回收，不丢消息。
 * {@code @PreDestroy} 关闭时超时丢弃队列任务同样安全（行留 PG）。
 * <p>
 * <b>sendAfterCommit 语义</b>：默认实现即经 {@code send()} 走 outbox。调用方<b>不应在持有业务
 * 事务时调用</b>——outbox INSERT 会加入调用方事务（同连接）；本场景 publisher 侧无业务写，
 * INSERT 是独立短事务。若未来需要事务性发布，应确保 INSERT 不与长事务共用连接。
 */
public class OutboxMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(OutboxMessageBus.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private final MessageBus delegate;
    private final OutboxMapper outboxMapper;
    private final MessagePayloadCodec codec;
    private final SharedCircuitBreakerGate cbGate;
    private final MessagingProperties.OutboxConfig config;
    private final OutboxMetrics metrics;
    private final ThreadPoolExecutor retryExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public OutboxMessageBus(MessageBus delegate,
                            OutboxMapper outboxMapper,
                            MessagePayloadCodec codec,
                            SharedCircuitBreakerGate cbGate,
                            MessagingProperties properties,
                            @Nullable OutboxMetrics metrics) {
        this.delegate = delegate;
        this.outboxMapper = outboxMapper;
        this.codec = codec;
        this.cbGate = cbGate;
        this.config = properties.outbox();
        this.metrics = metrics != null ? metrics : new OutboxMetrics(null);
        this.retryExecutor = new ThreadPoolExecutor(
            config.immediateExecutorCore(),
            config.immediateExecutorMax(),
            60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(config.immediateExecutorQueue()),
            r -> {
                Thread t = new Thread(r, "outbox-immediate-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }

    private static final AtomicInteger counter = new AtomicInteger();

    // ==================== Send（托管） ====================

    @Override
    public String send(MessageEnvelope<?> message) {
        OutboxEntry entry = insert(message);
        tryImmediate(entry.getId(), message);
        return String.valueOf(entry.getId());
    }

    @Override
    public CompletableFuture<String> sendAsync(MessageEnvelope<?> message) {
        OutboxEntry entry = insert(message);
        if (cbGate.isOpen(message.topic())) {
            // OPEN：行留 relay，future 立即完成（设计 §6.1）
            return CompletableFuture.completedFuture(String.valueOf(entry.getId()));
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            retryExecutor.execute(() -> {
                try {
                    String delegateId = sendWithRetry(entry.getId(), message);
                    deleteOutbox(entry.getId());
                    future.complete(delegateId);
                } catch (Exception e) {
                    // 即时投递未成功：行留 relay（不丢），future 诚实反馈
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满：行留 relay，future 如实拒绝
            log.debug("sendAsync rejected by bounded executor, row stays for relay: outboxId={}", entry.getId());
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 即时投递（send() 内部：spawn 前二次检查 gate——OPEN 时连任务都不提交，省队列位）。
     */
    private void tryImmediate(Long outboxId, MessageEnvelope<?> message) {
        if (cbGate.isOpen(message.topic())) {
            log.debug("Circuit gate OPEN, skipping immediate delivery, row stays for relay: outboxId={}", outboxId);
            return;
        }
        try {
            retryExecutor.execute(() -> {
                try {
                    sendWithRetry(outboxId, message);
                    deleteOutbox(outboxId);
                } catch (Exception e) {
                    log.debug("Immediate delivery failed, row stays for relay: outboxId={}, err={}",
                        outboxId, e.getMessage());
                    metrics.immediateFailed(message.topic());
                }
            });
        } catch (RejectedExecutionException e) {
            // 拒绝 = 行留 relay（不丢）——有界 executor 的背压语义（design §2）
            log.debug("Immediate executor rejected, row stays for relay: outboxId={}", outboxId);
        }
    }

    /**
     * 即时投递有限重试：最多 {@code immediateRetryCount + 1} 次尝试，间隔
     * {@code immediateRetryIntervalMs}。仅覆盖 MQ 瞬时抖动（网络毛刺/主从切换）；
     * 硬故障由 relay 退避重试兜底（R3）。
     *
     * @return delegate 传输 ID（成功）
     * @throws MessagingException 重试耗尽
     */
    private String sendWithRetry(Long outboxId, MessageEnvelope<?> message) {
        for (int attempt = 0; ; attempt++) {
            try {
                String id = delegate.send(message);
                metrics.immediateDelivered(message.topic());
                return id;
            } catch (Exception e) {
                if (attempt >= config.immediateRetryCount()) {
                    throw new MessagingException(MessagingErrorCode.PUBLISH_FAILED,
                        "Immediate delivery exhausted: outboxId=" + outboxId + ", topic=" + message.topic(), e);
                }
                if (!sleepNoThrow(config.immediateRetryIntervalMs())) {
                    throw new MessagingException(MessagingErrorCode.PUBLISH_FAILED,
                        "Immediate delivery interrupted: outboxId=" + outboxId, e);
                }
            }
        }
    }

    private void deleteOutbox(Long outboxId) {
        try {
            outboxMapper.deleteByIds(List.of(outboxId));
        } catch (Exception e) {
            // DELETE 失败 → 行残留 → relay 重复投递 → 消费端 SETNX + DB 唯一约束兜底（at-least-once，文档化）
            log.warn("Outbox delete failed after delivery (duplicate delivery possible, consumer idempotency covers): "
                + "outboxId={}, err={}", outboxId, e.getMessage());
        }
    }

    /** INSERT outbox 行（含 payload_type/tag/hash_key 列，P1-4/P1-8）。INSERT 失败抛 400013（DB 硬故障）。 */
    private OutboxEntry insert(MessageEnvelope<?> message) {
        OutboxEntry entry = new OutboxEntry();
        entry.setTopic(message.topic());
        entry.setPayload(new String(codec.encode(message.payload()), StandardCharsets.UTF_8));
        entry.setPayloadType(message.payload().getClass().getName());
        entry.setTag(message.tag());
        entry.setDedupKey(message.deduplicationKey());
        entry.setHashKey(message.hashKey());
        entry.setHeaders(headersJson(message.headers()));
        entry.setStatus("pending");
        entry.setAttempts(0);
        // createdAt/updatedAt/nextRetryAt 同源一个时刻：避免多次 now() 漂移，并满足两列的 NOT NULL。
        // XML INSERT 逐列显式枚举（JSONB CAST 惯例），显式 NULL 不会触发 DDL 的 DEFAULT now()，
        // 故必须在此赋值——回归见 OutboxMessageBusInsertTest（真 PG，锁死此契约）。
        Instant now = Instant.now();
        entry.setNextRetryAt(now);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        try {
            outboxMapper.insert(entry);
        } catch (Exception e) {
            throw new MessagingException(MessagingErrorCode.OUTBOX_INSERT_FAILED,
                "Failed to persist message to outbox: topic=" + message.topic(), e);
        }
        return entry;
    }

    // ==================== 委托 delegate ====================

    @Override
    public <T> Subscription subscribe(String topic, String group,
                                      com.smart.rag.infrastructure.messaging.ConsumerConfig config,
                                      Class<T> payloadType,
                                      com.smart.rag.infrastructure.messaging.MessageHandler<T> handler) {
        return delegate.subscribe(topic, group, config, payloadType, handler);
    }

    /** 委托 delegate；本装饰器自己的即时投递 executor 由 {@code @PreDestroy} 关闭（行留 PG，relay 兜底）。 */
    @Override
    public void shutdown() {
        delegate.shutdown();
        close();
    }

    @Override
    public void sendAfterCommit(MessageEnvelope<?> messageEnvelope) {
        // 显式注释（design §6.2）：默认实现即经 send() 走 outbox。调用方不应在持有业务事务时调用——
        // outbox INSERT 会加入调用方事务（同连接）。本场景 publisher 侧无业务写，INSERT 是独立短事务。
        send(messageEnvelope);
    }

    @Override
    public @Nullable DeadLetterOperations deadLetterOperations() {
        return delegate.deadLetterOperations();
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭即时投递 executor：{@code shutdown() + awaitTermination(5s)}；超时未完成的任务丢弃是
     * 安全的（行留 PG，relay 兜底）——文档化此点而非留给实现臆断。
     */
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        retryExecutor.shutdown();
        try {
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Immediate delivery executor did not finish within 5s; queued tasks dropped "
                    + "(safe: rows stay in outbox, relay drains them)");
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            retryExecutor.shutdownNow();
        }
    }

    // ==================== Helpers ====================

    static String headersJson(Map<String, String> headers) {
        try {
            return JSON.writeValueAsString(headers);
        } catch (Exception e) {
            throw new MessagingException(MessagingErrorCode.PUBLISH_FAILED, "Failed to serialize headers", e);
        }
    }

    static Map<String, String> parseHeaders(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, STRING_MAP_TYPE);
        } catch (Exception e) {
            throw new MessagingException(MessagingErrorCode.CONSUME_FAILED, "Failed to parse headers: " + json, e);
        }
    }

    /** {@link Thread#sleep} 封装：被中断时恢复中断标志并返回 false（调用方放弃重试）。 */
    private static boolean sleepNoThrow(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
