package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.MessageConsumeException;
import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.PermanentConsumeException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.ConsumerMode;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.TracePropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Stream 消费循环（design §3）——XREADGROUP → handler → XACK 统一为应用层重试模式
 * （Redis 无 broker 自动重投；PUSH/SIMPLE 仅线程模型区分）。
 * <ul>
 *   <li><b>PUSH</b>：{@code concurrency} 个 poll 线程，消息内联处理（线程忙即天然背压）；</li>
 *   <li><b>SIMPLE</b>：1 个 receive 线程 + processingPool + Semaphore（镜像 SimpleConsumerReceiveLoop，
 *       适合 ETL 长任务）；</li>
 *   <li><b>P1-4</b>：XREADGROUP BLOCK 走独立 {@link RedisStreamConsumerConnections}，不占业务共享连接；</li>
 *   <li><b>Redis 故障韧性</b>：pollLoop try/catch + {@link ReconnectBackoff}（1s→30s ±20% jitter），
 *       成功（含空拉取）即 reset；退避 sleep 只阻塞 poll 线程自身；</li>
 *   <li><b>P0-1</b>：可重试失败 XACK 移出 PEL + 转 ZSET 延迟队列（RetrySweeper 接管），不留 PEL；
 *       永久错误 XACK + DLQ；</li>
 *   <li><b>P1-6</b>：{@link #dispatchToProcessingPool} 供 PelRecoverySweeper 异步派发（不在 sweeper 线程同步 handle）。</li>
 * </ul>
 */
public class RedisStreamConsumerRunner<T> {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumerRunner.class);

    private final String topic;
    private final String group;
    private final String consumerName;
    private final ConsumerConfig config;
    private final Class<T> payloadType;
    private final MessageHandler<T> handler;
    private final MessagePayloadCodec codec;
    private final TracePropagator propagator;
    private final MessagingMetrics metrics;
    private final MessagingProperties properties;
    private final RedisStreamConsumerConnections connections;
    private final RetrySweeper retrySweeper;
    private final RedisStreamDeadLetterWriter deadLetterWriter;
    private final RedisStreamKeys keys;
    private final String streamKey;
    private final int readBatch;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    @org.springframework.lang.Nullable
    private volatile ExecutorService pollExecutor;
    @org.springframework.lang.Nullable
    private volatile ExecutorService processingPool;
    @org.springframework.lang.Nullable
    private volatile Semaphore inflightSemaphore;

    public RedisStreamConsumerRunner(String topic, String group, String consumerName,
                                     ConsumerConfig config, Class<T> payloadType,
                                     MessageHandler<T> handler, MessagePayloadCodec codec,
                                     TracePropagator propagator, MessagingMetrics metrics,
                                     MessagingProperties properties,
                                     RedisStreamConsumerConnections connections,
                                     RetrySweeper retrySweeper,
                                     RedisStreamDeadLetterWriter deadLetterWriter) {
        this.topic = topic;
        this.group = group;
        this.consumerName = consumerName;
        this.config = config;
        this.payloadType = payloadType;
        this.handler = handler;
        this.codec = codec;
        this.propagator = propagator != null ? propagator : TracePropagator.NO_OP;
        this.metrics = metrics;
        this.properties = properties;
        this.connections = connections;
        this.retrySweeper = retrySweeper;
        this.deadLetterWriter = deadLetterWriter;
        this.keys = new RedisStreamKeys(properties);
        this.streamKey = keys.streamKey(topic);
        this.readBatch = Math.min(Math.max(1, config.batchSize()), properties.redis().readBatch());
    }

    public String topic() {
        return topic;
    }

    public String group() {
        return group;
    }

    boolean isRunning() {
        return running.get();
    }

    // ==================== Lifecycle ====================

    /** 幂等启动：ensureGroup + 按 ConsumerMode 起线程。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ensureGroup();
        running.set(true);

        int concurrency = Math.max(1, config.concurrency());
        AtomicInteger threadCounter = new AtomicInteger(0);
        ExecutorService pool = new ThreadPoolExecutor(
            concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, readBatch * 2)),
            r -> {
                Thread t = new Thread(r, "redis-process-" + topic + "-" + threadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.processingPool = pool;

        if (config.consumerMode() == ConsumerMode.PUSH) {
            // PUSH：concurrency 个 poll 线程，消息内联处理（线程忙 = 天然背压）
            ExecutorService pollers = java.util.concurrent.Executors.newFixedThreadPool(concurrency, r -> {
                Thread t = new Thread(r, "redis-poll-" + topic + "-" + threadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            this.pollExecutor = pollers;
            for (int i = 0; i < concurrency; i++) {
                pollers.submit(this::pollLoop);
            }
        } else {
            // SIMPLE：receive 线程 + processingPool + Semaphore（镜像 SimpleConsumerReceiveLoop）
            this.inflightSemaphore = new Semaphore(concurrency);
            ExecutorService receiver = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "redis-receive-" + topic);
                t.setDaemon(true);
                return t;
            });
            this.pollExecutor = receiver;
            receiver.submit(this::pollLoop);
        }
        log.info("Redis Stream consumer started: topic={}, group={}, mode={}, concurrency={}, consumerName={}",
            topic, group, config.consumerMode(), concurrency, consumerName);
    }

    /** 幂等关停：停 poll 线程 → 停 processingPool（awaitTermination 带超时，P2-9）。 */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        shutdownQuietly(pollExecutor);
        shutdownQuietly(processingPool);
        log.info("Redis Stream consumer stopped: topic={}, group={}", topic, group);
    }

    private void shutdownQuietly(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        long timeoutMs = properties.shutdownTimeout().toMillis();
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("Executor did not terminate within {}ms: topic={}, group={}",
                    timeoutMs, topic, group);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Poll loop ====================

    private void pollLoop() {
        ReconnectBackoff backoff = new ReconnectBackoff(properties.redis().reconnectBackoff());
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> messages = connections.streamOps().read(
                    Consumer.from(group, consumerName),
                    StreamReadOptions.empty().count(readBatch)
                        .block(properties.redis().readBlock()),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
                backoff.reset();   // 成功即重置（含空拉取）
                metrics.recordReceiveSuccess(topic, group);
                for (MapRecord<String, Object, Object> msg : messages) {
                    dispatch(msg);
                }            } catch (Exception e) {
                // 连接级失败（Redis 宕机/主从切换/网络分区）→ 指数退避重连；消息不丢（PEL/retry-zset 兜底）
                metrics.recordConsumeConnectionFailure(topic, group);
                if (!running.get()) {
                    break;
                }
                long sleepMs = backoff.nextSleepMs();
                log.warn("XREADGROUP failed, backing off: topic={}, group={}, sleepMs={}",
                    topic, group, sleepMs, e);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Poll loop exited: topic={}, group={}", topic, group);
    }

    /** PUSH：内联；SIMPLE：semaphore 背压 + processingPool。 */
    private void dispatch(MapRecord<String, ?, ?> record) {
        if (config.consumerMode() == ConsumerMode.PUSH) {
            handle(record);
            return;
        }
        Semaphore semaphore = inflightSemaphore;
        ExecutorService pool = processingPool;
        if (semaphore == null || pool == null) {
            handle(record);
            return;
        }
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;   // 关停中：消息留 PEL，PelRecoverySweeper 兜底
        }
        try {
            pool.submit(() -> {
                try {
                    handle(record);
                } finally {
                    semaphore.release();
                }
            });
        } catch (RejectedExecutionException e) {
            semaphore.release();
            // P0-1：满载不留 PEL 卡死——转延迟重试（XACK + ZSET）
            log.warn("Processing pool full, routing to retry: topic={}, msgId={}",
                topic, record.getId().getValue());
            retrySweeper.routeToRetry(this, record, e);
        }
    }

    /**
     * 外部异步派发（PelRecoverySweeper claim 后调用，P1-6）——不在 sweeper 调度线程同步 handle，
     * 避免 ETL 长任务阻塞 sweeper。回灌（RetrySweeper）不经过此路径：新 entry 由 poll loop 经
     * {@code XREADGROUP >} 自然消费。
     */
    public void dispatchToProcessingPool(Runnable task) {
        ExecutorService pool = processingPool;
        if (pool == null || pool.isShutdown() || !running.get()) {
            log.warn("Processing pool unavailable, message stays claimed for next sweep: topic={}, group={}",
                topic, group);
            return;
        }
        try {
            pool.submit(task);
        } catch (RejectedExecutionException e) {
            log.warn("Processing pool full, message stays claimed for next sweep: topic={}, group={}",
                topic, group);
        }
    }

    // ==================== Handle ====================

    /**
     * 消息处理主路径（P0-1 统一 XACK 语义），异常分类复用项目异常体系
     * （{@code infrastructure/exception/}，A/B/C 类 + Messaging 系）：
     * <ul>
     *   <li>{@link PermanentConsumeException}（400003）→ XACK + DLQ（消息本身不可处理）；</li>
     *   <li><b>可重试白名单</b>（业务瞬态）→ XACK + 转 ZSET 延迟队列（RetrySweeper）：
     *       {@link RemoteException}（C 类第三方服务瞬态）、{@link ServiceException}（B 类服务端错误）、
     *       {@link MessageConsumeException}（400002 消费处理失败）、其它 {@link MessagingException}
     *       （传输层）、Spring {@code TransientDataAccessException}（DB 连接/锁等基础设施瞬态）；</li>
     *   <li><b>非重试 → 直接 DLQ</b>：{@link ClientException}（A 类——消息内容触发客户端错误，
     *       重试无意义）、以及<b>未知异常</b>
     *       （白名单之外——NPE/非法状态/非瞬态数据错误等，design R2：避免 bug 被重试循环放大）。</li>
     * </ul>
     */
    void handle(MapRecord<String, ?, ?> record) {
        String msgId = record.getId().getValue();
        Map<?, ?> fields = record.getValue();
        propagator.restore(RedisStreamFields.parseHeaders(RedisStreamFields.str(fields, "headers")));
        long startNanos = metrics.startNanos();
        try {
            MessageEnvelope<T> envelope = decode(msgId, fields);
            handler.onMessage(envelope);
            ack(record.getId());
            metrics.recordConsumeSuccess(topic, group, modeName(), startNanos);
            log.debug("Message consumed and acked: topic={}, group={}, msgId={}", topic, group, msgId);
        } catch (PermanentConsumeException e) {
            metrics.recordConsumeFailure(topic, group, modeName());
            log.error("Permanent consume error, forwarding to DLQ: topic={}, group={}, msgId={}",
                topic, group, msgId, e);
            sendToDlq(fields, msgId, "PERMANENT");
        } catch (Exception e) {
            metrics.recordConsumeFailure(topic, group, modeName());
            if (isRetryable(e)) {
                retrySweeper.routeToRetry(this, record, e);
            } else {
                String reason = dlqReason(e);
                log.error("Non-retryable consume error ({}), forwarding to DLQ: topic={}, group={}, msgId={}",
                    reason, topic, group, msgId, e);
                sendToDlq(fields, msgId, reason);
                if (isUnknown(e)) {
                    metrics.recordUnknownFailure(topic, group);
                }
            }
        } finally {
            propagator.clear();
        }
    }

    /** DLQ 写入成功才 XACK（失败留 PEL，PelRecoverySweeper 兜底，自治不抛业务异常）。 */
    private void sendToDlq(Map<?, ?> fields, String msgId, String reason) {
        if (deadLetterWriter.sendToDeadLetter(keys.dlqKey(topic, group), topic, group,
            RedisStreamFields.toStringMap(fields), reason)) {
            ack(RecordId.of(msgId));
            metrics.recordDeadLetter(topic, group);
        }
    }

    /**
     * 可重试白名单（项目异常体系 + Spring 基础设施瞬态）：
     * RemoteException（C 类第三方瞬态）/ ServiceException（B 类服务端错误）/
     * MessageConsumeException（400002）/ 其它 MessagingException（传输层）/
     * TransientDataAccessException + DataAccessResourceFailureException
     * （DB 连接/锁等基础设施故障——chat-save/usage 落库主故障路径；后者虽归 Spring
     * NonTransient 类，但资源恢复后重试可成功，属可重试瞬态）。
     */
    private static boolean isRetryable(Throwable e) {
        return e instanceof RemoteException
            || e instanceof ServiceException
            || e instanceof MessageConsumeException
            || e instanceof MessagingException
            || e instanceof org.springframework.dao.TransientDataAccessException
            || e instanceof org.springframework.dao.DataAccessResourceFailureException;
    }

    /** DLQ reason：A 类客户端异常归为客户端错误，其余为未知。 */
    private static String dlqReason(Throwable e) {
        if (e instanceof ClientException) {
            return "CLIENT_ERROR";
        }
        return "UNKNOWN";
    }

    private static boolean isUnknown(Throwable e) {
        return !(e instanceof ClientException);
    }

    /** XACK（消费连接池）。失败仅记日志：消息留 PEL，PelRecoverySweeper 兜底。 */
    public void ack(RecordId id) {
        try {
            connections.streamOps().acknowledge(streamKey, group, id);
        } catch (Exception e) {
            log.warn("XACK failed (PelRecoverySweeper backstop): stream={}, group={}, msgId={}",
                streamKey, group, id.getValue(), e);
        }
    }

    private MessageEnvelope<T> decode(String msgId, Map<?, ?> fields) {
        T payload = codec.decode(RedisStreamFields.utf8(RedisStreamFields.str(fields, "payload")), payloadType);
        Map<String, String> headers = RedisStreamFields.parseHeaders(RedisStreamFields.str(fields, "headers"));
        String tag = RedisStreamFields.nullable(RedisStreamFields.str(fields, "tag"));
        String dedupKey = RedisStreamFields.nullable(RedisStreamFields.str(fields, "dedupKey"));
        String hashKey = RedisStreamFields.nullable(RedisStreamFields.str(fields, "hashKey"));
        long bornTs = parseLong(RedisStreamFields.str(fields, "bornTs"), System.currentTimeMillis());
        return new MessageEnvelope<>(msgId, topic, tag, payload, hashKey, dedupKey, headers, bornTs);
    }

    private String modeName() {
        return config.consumerMode() == ConsumerMode.PUSH ? "push" : "simple";
    }

    // ==================== Group ====================

    /** XGROUP CREATE ... MKSTREAM（首次）；BUSYGROUP 忽略；其它失败记 metric（design §9）。 */
    private void ensureGroup() {
        try {
            connections.executeCommand("XGROUP", "CREATE", streamKey, group, "$", "MKSTREAM");
        } catch (Exception e) {
            if (isBusyGroup(e)) {
                log.debug("Consumer group already exists: stream={}, group={}", streamKey, group);
            } else {
                metrics.recordGroupCreateFailed(topic, group);
                log.error("XGROUP CREATE failed: stream={}, group={}", streamKey, group, e);
            }
        }
    }

    private static boolean isBusyGroup(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
