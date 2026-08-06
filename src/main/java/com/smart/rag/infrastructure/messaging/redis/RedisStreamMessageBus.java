package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.exception.MessagePublishException;
import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.BackoffSchedule;
import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.DeadLetterOperations;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageBusManagement;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessageValidator;
import com.smart.rag.infrastructure.messaging.MessagingCircuitBreakerState;
import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.SendCircuitBreaker;
import com.smart.rag.infrastructure.messaging.Subscription;
import com.smart.rag.infrastructure.messaging.TracePropagator;
import com.smart.rag.infrastructure.messaging.ZSetDelayQueue;
import com.smart.rag.infrastructure.messaging.idempotent.IdempotentHandler;
import com.smart.rag.infrastructure.messaging.outbox.SharedCircuitBreakerGate;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Redis Stream 消息总线（design §2/§7）——Redis 8 Stream（XADD/XREADGROUP/XACK/XAUTOCLAIM）
 * 实现 {@link MessageBus} SPI（唯一实现，无灰度开关、无两实现并存）。
 * <p>
 * <ul>
 *   <li><b>R1 send</b>：XADD 主 stream（无 MAXLEN——物理裁剪由 StreamTrimTask MINID，P1-5）；
 *       返回 entry ID 作为传输级 ID；header（含 traceparent）随 XADD 字段写入；attempt=0 随消息携带（P0-2）；
 *       per-topic SendCircuitBreaker 保留。</li>
 *   <li><b>R2 subscribe</b>：XREADGROUP 消费循环（PUSH/SIMPLE 线程模型区分），consumer 名
 *       {@code app:{instanceId}}；pollLoop 连接级失败指数退避重连（±20% jitter）。</li>
 *   <li><b>P1-4</b>：XREADGROUP BLOCK 走独立 {@link RedisStreamConsumerConnections}（share-native-connection=false
 *       + pool），不占业务共享 Redis 连接。</li>
 *   <li><b>R3-R5</b>：RetrySweeper（ZSET 延迟队列 + 单 Lua 原子回灌）/ PelRecoverySweeper（XAUTOCLAIM
 *       异步派发）/ DLQ（独立 stream + MAXLEN，首次真正实现 DeadLetterOperations）。</li>
 *   <li><b>R6 FIFO</b>：bus 不分区——同 topic 消息投给 group 内任一 consumer；per-documentId 串行由
 *       EtlDispatchServiceImpl 的 RLock 保证（hashKey 仅作为字段写入供业务层参考）。</li>
 * </ul>
 * <b>traceparent 冻结点</b>（child 2 OutboxRelay 依赖）：send() 对 {@code traceparent} header 采
 * "已存在不覆盖"策略——relay 重建 envelope 时 headers 含 publisher 存储的 traceparent，不覆盖。
 */
public class RedisStreamMessageBus implements MessageBus, MessageBusManagement {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamMessageBus.class);

    private static final Pattern GROUP_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");

    private final MessagingProperties properties;
    private final StringRedisTemplate businessTemplate;
    private final MessagePayloadCodec codec;
    private final MessageValidator validator;
    private final TracePropagator propagator;
    private final MessagingMetrics metrics;

    private final RedisStreamConsumerConnections connections;
    private final RedisStreamKeys keys;
    private final RedisStreamDeadLetterWriter deadLetterWriter;
    private final RetrySweeper retrySweeper;
    private final PelRecoverySweeper pelRecoverySweeper;
    private final StreamTrimTask streamTrimTask;
    private final RedisStreamDeadLetterOperations deadLetterOps;

    private final String consumerName;

    private final ConcurrentMap<String, SendCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RedisStreamSubscription> subscriptionRegistry = new ConcurrentHashMap<>();
    /** topic → group（当前 1:1 拓扑，DLQ SPI 的 group 解析用；多组扩展点见 RedisStreamDeadLetterOperations）。 */
    private final ConcurrentMap<String, String> topicToGroup = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RedisStreamSubscription> activeSubscriptions = new CopyOnWriteArrayList<>();
    /** P1-6.2（child 2 跨 child 契约）：广播钩子——tripOpen/CLOSED 经 SharedCircuitBreakerGate 同步其它实例。 */
    private final @Nullable SharedCircuitBreakerGate cbGate;

    private static final ExecutorService ASYNC_SEND_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "redis-send-async");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean shutdown;

    public RedisStreamMessageBus(MessagingProperties properties,
                                 StringRedisTemplate businessTemplate,
                                 MessagePayloadCodec codec,
                                 @Nullable MessageValidator validator,
                                 @Nullable TracePropagator propagator,
                                 @Nullable MeterRegistry meterRegistry,
                                 RedisStreamConsumerConnections connections,
                                 RedisStreamKeys keys,
                                 RedisStreamDeadLetterWriter deadLetterWriter,
                                 RetrySweeper retrySweeper,
                                 PelRecoverySweeper pelRecoverySweeper,
                                 StreamTrimTask streamTrimTask) {
        this(properties, businessTemplate, codec, validator, propagator, meterRegistry, connections,
            keys, deadLetterWriter, retrySweeper, pelRecoverySweeper, streamTrimTask, null);
    }

    /** 广播钩子装配点（P1-6.2）：{@code cbGate} 为 null 时 SendCircuitBreaker 无广播（既有语义不变）。 */
    public RedisStreamMessageBus(MessagingProperties properties,
                                 StringRedisTemplate businessTemplate,
                                 MessagePayloadCodec codec,
                                 @Nullable MessageValidator validator,
                                 @Nullable TracePropagator propagator,
                                 @Nullable MeterRegistry meterRegistry,
                                 RedisStreamConsumerConnections connections,
                                 RedisStreamKeys keys,
                                 RedisStreamDeadLetterWriter deadLetterWriter,
                                 RetrySweeper retrySweeper,
                                 PelRecoverySweeper pelRecoverySweeper,
                                 StreamTrimTask streamTrimTask,
                                 @Nullable SharedCircuitBreakerGate cbGate) {
        this.properties = properties;
        this.businessTemplate = businessTemplate;
        this.codec = codec;
        this.validator = validator != null ? validator : new MessageValidator(properties, codec);
        this.propagator = propagator != null ? propagator : TracePropagator.NO_OP;
        this.metrics = new MessagingMetrics(meterRegistry);
        this.connections = connections;
        this.keys = keys;
        this.deadLetterWriter = deadLetterWriter;
        this.retrySweeper = retrySweeper;
        this.pelRecoverySweeper = pelRecoverySweeper;
        this.streamTrimTask = streamTrimTask;
        this.cbGate = cbGate;
        this.consumerName = RedisStreamInstance.consumerName(properties);
        this.deadLetterOps = new RedisStreamDeadLetterOperations(
            deadLetterWriter, businessTemplate, codec, keys, properties, topicToGroup::get);

        MessageValidator.validateTopicPrefix(properties.topicPrefix());
    }

    // ==================== Send ====================

    @Override
    public String send(MessageEnvelope<?> message) {
        SendCircuitBreaker cb = circuitBreakerFor(message.topic());
        if (!cb.isCallAllowed()) {
            throw new MessagePublishException("Circuit breaker OPEN for topic: " + message.topic());
        }
        try {
            byte[] encoded = validator.validateAndEncode(message);
            // traceparent 冻结点：headers 已含（relay 投递的存储 traceparent）则不覆盖，仅缺失时注入
            Map<String, String> headers = new HashMap<>(message.headers());
            if (!headers.containsKey("traceparent")) {
                headers.putAll(propagator.inject());
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("topic", message.topic());
            fields.put("tag", nvl(message.tag()));
            fields.put("dedupKey", nvl(message.deduplicationKey()));
            fields.put("hashKey", nvl(message.hashKey()));
            fields.put("headers", RedisStreamFields.headersJson(headers));
            fields.put("payload", new String(encoded, StandardCharsets.UTF_8));
            fields.put("bornTs", String.valueOf(message.timestamp()));
            fields.put("attempt", "0");   // P0-2：attempt 随消息字段流转基线
            fields.put("contentType", "application/json");

            long startNanos = metrics.startNanos();
            // P1-5：XADD 不带 MAXLEN——主 stream 物理裁剪由 StreamTrimTask 按 XINFO MINID 负责
            RecordId recordId = businessTemplate.opsForStream()
                .add(MapRecord.create(keys.streamKey(message.topic()), fields));
            metrics.recordSendSuccess(message.topic(), startNanos, encoded.length);
            cb.recordSuccess();
            log.debug("Message sent: topic={}, streamEntryId={}", message.topic(), recordId.getValue());
            return recordId.getValue();
        } catch (com.smart.rag.infrastructure.exception.ClientException e) {
            throw e;
        } catch (Exception e) {
            cb.recordFailure();
            metrics.recordSendFailure(message.topic());
            throw new MessagePublishException("Failed to send message to topic: " + message.topic(), e);
        }
    }

    @Override
    public CompletableFuture<String> sendAsync(MessageEnvelope<?> message) {
        return CompletableFuture.supplyAsync(() -> send(message), ASYNC_SEND_EXECUTOR);
    }

    // ==================== Subscribe ====================

    @Override
    public <T> Subscription subscribe(String topic, String group,
                                      ConsumerConfig config,
                                      Class<T> payloadType,
                                      MessageHandler<T> handler) {
        if (group == null || !GROUP_PATTERN.matcher(group).matches()) {
            throw new com.smart.rag.infrastructure.exception.ClientException(MessagingErrorCode.INVALID_GROUP,
                "非法消费者组名称: '" + group + "'，仅允许字母/数字/下划线/连字符，长度1-128");
        }
        String subscriptionKey = topic + ":" + group;
        RedisStreamSubscription existing = subscriptionRegistry.get(subscriptionKey);
        if (existing != null && existing.isActive()) {
            return existing;
        }
        synchronized (this) {
            if (shutdown) {
                throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                    "MessageBus is shutting down, cannot create new subscriptions");
            }
        }

        MessageHandler<T> wrappedHandler = properties.idempotent().enabled()
            ? IdempotentHandler.wrap(handler, topic, businessTemplate,
                properties.idempotent().ttlSeconds(), metrics)
            : handler;

        RedisStreamConsumerRunner<T> runner = new RedisStreamConsumerRunner<>(
            topic, group, consumerName, config, payloadType, wrappedHandler,
            codec, propagator, metrics, properties, connections, retrySweeper, deadLetterWriter);
        try {
            runner.start();
        } catch (Exception e) {
            throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                "Failed to start subscription: " + topic, e);
        }

        retrySweeper.register(runner);
        pelRecoverySweeper.register(runner);
        streamTrimTask.register(topic, group);
        topicToGroup.put(topic, group);

        RedisStreamSubscription subscription = new RedisStreamSubscription(topic, group, runner, () -> {
            retrySweeper.unregister(topic, group);
            pelRecoverySweeper.unregister(topic, group);
            streamTrimTask.unregister(topic, group);
            subscriptionRegistry.remove(subscriptionKey);
            activeSubscriptions.removeIf(s -> s.topic().equals(topic) && s.group().equals(group));
        });

        synchronized (this) {
            if (shutdown) {
                try {
                    subscription.close();
                } catch (Exception e) {
                    log.warn("Failed to close subscription during shutdown race", e);
                }
                throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                    "MessageBus is shutting down, cannot create new subscriptions");
            }
            activeSubscriptions.add(subscription);
            subscriptionRegistry.put(subscriptionKey, subscription);
        }
        return subscription;
    }

    // ==================== Shutdown ====================

    @Override
    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        for (RedisStreamSubscription sub : activeSubscriptions) {
            try {
                sub.close();
            } catch (Exception e) {
                log.warn("Error closing subscription: topic={}, group={}", sub.topic(), sub.group(), e);
            }
        }
        activeSubscriptions.clear();
        subscriptionRegistry.clear();
        connections.close();
        log.info("RedisStreamMessageBus shutdown complete");
    }

    // ==================== Transaction Integration ====================

    @Override
    public void sendAfterCommit(MessageEnvelope<?> message) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            send(message);
                        } catch (Exception e) {
                            log.error("Post-commit send failed: topic={}, dedupKey={}",
                                message.topic(), message.deduplicationKey(), e);
                            metrics.recordPostCommitFail(message.topic());
                        }
                    }
                });
        } else {
            send(message);
        }
    }

    // ==================== Dead Letter Operations ====================

    @Override
    public DeadLetterOperations deadLetterOperations() {
        return deadLetterOps;
    }

    // ==================== Management ====================

    /** Redis PING（design §8：health = Redis 可达 + 活跃订阅）。 */
    @Override
    public boolean isProducerHealthy() {
        if (shutdown) {
            return false;
        }
        try {
            String pong = businessTemplate.execute((RedisCallback<String>) conn -> conn.ping());
            return pong != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int activeSubscriptionCount() {
        return (int) activeSubscriptions.stream().filter(RedisStreamSubscription::isActive).count();
    }

    @Override
    public Map<String, String> circuitBreakerState() {
        if (circuitBreakers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        circuitBreakers.forEach((topic, cb) ->
            result.put(topic, cb.state().name().toLowerCase()));
        return result;
    }

    /** child 2 SharedCircuitBreakerGate 读（R7 冻结点）：per-topic 熔断是否 OPEN。 */
    @Override
    public boolean isCircuitBreakerOpen(String topic) {
        SendCircuitBreaker cb = circuitBreakers.get(topic);
        return cb != null && cb.state() == MessagingCircuitBreakerState.OPEN;
    }

    // ==================== Private Helpers ====================

    /** per-topic 熔断实例化点（P1-6.2：注入 SharedCircuitBreakerGate 广播钩子）。 */
    private SendCircuitBreaker circuitBreakerFor(String topic) {
        return circuitBreakers.computeIfAbsent(topic,
            k -> new SendCircuitBreaker(properties.circuitBreaker(), Clock.systemUTC(), cbGate, topic));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
