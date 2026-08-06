package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.BackoffSchedule;
import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.ZSetDelayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 退避重试 sweeper（design §4）——消费失败的 P0-1 语义：XACK 移出 PEL + 原子转入 ZSET 延迟队列，
 * 到期由本 sweeper 原子回灌主 stream（poll loop 经 {@code XREADGROUP >} 自然重新消费）。
 * <ul>
 *   <li><b>P0-2</b>：attempt 作为消息字段随 send → 失败 → HSET → 回灌 → handle 流转，
 *       不以 entry ID 为计数 key（回灌生成新 ID，以 ID 为 key 会导致 attempt 永远=1 永不进 DLQ）；</li>
 *   <li><b>P1-3</b>：回灌单 Lua 原子（ZRANGEBYSCORE → ZREM 抢占 → HGET → XADD → HDEL，
 *       经 {@link ZSetDelayQueue#drainToStream}），杜绝 ZREM 成功 XADD 前崩溃丢消息；</li>
 *   <li><b>P2-7</b>：HGET null（孤儿）→ ZREM 清理 + metric，单条异常不中止整批；</li>
 *   <li><b>P2-10</b>：retry key 含 group（经 {@link RedisStreamKeys}），多组不串扰；</li>
 *   <li><b>P2-14</b>：HSET+ZADD 单 Lua + retry hash TTL 防残留。</li>
 * </ul>
 * SmartLifecycle phase {@code DEFAULT-200}：先于 consumer pool（DEFAULT-100）关闭（P2-9）。
 */
public class RetrySweeper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RetrySweeper.class);

    private final ZSetDelayQueue delayQueue;
    private final RedisStreamKeys keys;
    private final BackoffSchedule backoffSchedule;
    private final MessagingProperties properties;
    private final MessagingMetrics metrics;
    private final RedisStreamDeadLetterWriter deadLetterWriter;

    /** topic:group → runner（subscribe 时 register，close 时 unregister）。 */
    private final ConcurrentMap<String, RedisStreamConsumerRunner<?>> runners = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "retry-sweeper");
            t.setDaemon(true);
            return t;
        });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RetrySweeper(ZSetDelayQueue delayQueue, RedisStreamKeys keys,
                        BackoffSchedule backoffSchedule, MessagingProperties properties,
                        MessagingMetrics metrics, RedisStreamDeadLetterWriter deadLetterWriter) {
        this.delayQueue = delayQueue;
        this.keys = keys;
        this.backoffSchedule = backoffSchedule;
        this.properties = properties;
        this.metrics = metrics;
        this.deadLetterWriter = deadLetterWriter;
    }

    // ==================== 注册 ====================

    public void register(RedisStreamConsumerRunner<?> runner) {
        runners.put(runner.topic() + ":" + runner.group(), runner);
        log.info("RetrySweeper registered: topic={}, group={}", runner.topic(), runner.group());
    }

    public void unregister(String topic, String group) {
        runners.remove(topic + ":" + group);
        log.info("RetrySweeper unregistered: topic={}, group={}", topic, group);
    }

    // ==================== 失败分支（P0-1/P0-2） ====================

    /**
     * 可重试失败：XACK 原消息 + HSET/ZADD 单 Lua 入延迟队列（P0-1 不留 PEL）。
     * attempt > maxAttempts → 不回灌，XADD DLQ（P2-8 带 MAXLEN）+ XACK。
     * <p>
     * 任何一步失败（enqueue/DLQ XADD）→ 不 XACK：消息留 PEL，PelRecoverySweeper 兜底（at-least-once）。
     */
    public void routeToRetry(RedisStreamConsumerRunner<?> runner,
                             MapRecord<String, ?, ?> record, Exception cause) {
        String topic = runner.topic();
        String group = runner.group();
        String msgId = record.getId().getValue();
        Map<String, String> fields = RedisStreamFields.toStringMap(record.getValue());

        int attempt = parseAttempt(fields) + 1;
        int maxAttempts = properties.redis().maxAttempts();
        if (attempt > maxAttempts) {
            log.error("Retry exhausted ({} > maxAttempts={}): topic={}, group={}, msgId={}, cause={}",
                attempt, maxAttempts, topic, group, msgId, String.valueOf(cause));
            if (deadLetterWriter.sendToDeadLetter(keys.dlqKey(topic, group), topic, group, fields, "RETRY_EXHAUSTED")) {
                runner.ack(record.getId());
                metrics.recordDeadLetter(topic, group);
            }
            return;
        }

        long backoffMs = backoffSchedule.next(attempt);
        Map<String, String> payload = new HashMap<>(fields);
        payload.put("attempt", String.valueOf(attempt));   // P0-2：attempt 随消息字段流转
        String retryId = UUID.randomUUID().toString();
        try {
            delayQueue.enqueue(keys.retryZsetKey(topic, group), keys.retryHashKey(topic, group),
                retryId, payload,
                System.currentTimeMillis() + backoffMs,
                properties.redis().retryHashTtl());
            runner.ack(record.getId());   // P0-1：XACK 移出 PEL，sweeper 接管
            metrics.recordRetry(topic, group, "redis-stream", String.valueOf(attempt));
            log.warn("Consume failed, retry #{} scheduled in {}ms: topic={}, group={}, msgId={}, cause={}",
                attempt, backoffMs, topic, group, msgId, String.valueOf(cause));
        } catch (Exception e) {
            log.error("Retry enqueue failed, message stays in PEL (PelRecoverySweeper backstop): "
                    + "topic={}, group={}, msgId={}",
                topic, group, msgId, e);
        }
    }

    // ==================== 周期回灌 ====================

    /** 周期回灌（调度器调用；包内可见供测试直调）。 */
    void drain() {
        long nowMs = System.currentTimeMillis();
        for (RedisStreamConsumerRunner<?> runner : runners.values()) {
            String topic = runner.topic();
            String group = runner.group();
            try {
                ZSetDelayQueue.DrainResult result = delayQueue.drainToStream(
                    keys.retryZsetKey(topic, group),
                    keys.retryHashKey(topic, group),
                    keys.streamKey(topic),
                    properties.redis().readBatch(),
                    nowMs);
                if (result.reinjected() > 0) {
                    metrics.recordRetryRedelivered(topic, group, result.reinjected());
                    log.info("Retry redelivered to stream: topic={}, group={}, count={}",
                        topic, group, result.reinjected());
                }
                if (result.orphans() > 0) {
                    metrics.recordRetryOrphan(topic, group, result.orphans());
                }
            } catch (Exception e) {
                // 单 topic 异常不中止整批（P2-7 隔离精神）
                log.error("Retry drain failed (continuing next cycle): topic={}, group={}", topic, group, e);
            }
        }
    }

    // ==================== SmartLifecycle（P2-9：先于 consumer 关闭） ====================

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            long intervalMs = properties.redis().retryPollInterval().toMillis();
            scheduler.scheduleAtFixedRate(this::drain, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            log.info("RetrySweeper started: pollIntervalMs={}", intervalMs);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("RetrySweeper stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 200;
    }

    // ==================== Helpers ====================

    private static int parseAttempt(Map<String, String> fields) {
        String attempt = fields.getOrDefault("attempt", "0");
        try {
            return Math.max(0, Integer.parseInt(attempt));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
