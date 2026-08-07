package com.smart.rag.infrastructure.messaging.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.BackoffSchedule;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OutboxRelay（design §3）——定时扫描 outbox {@code pending} 行，经 delegate bus 投递，
 * 成功删除，失败递增 {@code attempts} + 退避 {@code next_retry_at}，超 {@code maxAttempts} 标记
 * {@code dead}。
 * <p>
 * <b>事务拆分</b>：claim 短事务（{@code FOR UPDATE SKIP LOCKED}，持锁 ~1ms）→ <b>事务外</b>
 * send（MQ 网络 IO，可能卡数秒，不放事务内）→ 批量 DELETE 短事务。claiming 状态防同实例重复
 * claim；崩溃的 claiming 行由 claimPending 查询超时回收（阈值绑定配置）。
 * <p>
 * <b>熔断门控冻结 attempts（评审 P1-7）</b>：{@code gate.isOpen(topic)} 为真时<b>不 send、不递增
 * attempts</b>，只顺延 {@code next_retry_at}（{@code gateDeferInterval}）——连续 MQ 故障期间积压
 * 行 attempts 冻结、永不转 dead。{@code dead} 只属于反复真实投递失败的毒消息。
 * <p>
 * <b>drain 异常隔离（评审 P0-3）</b>：{@code scheduleAtFixedRate} 任务抛未捕获异常会永久抑制
 * 后续执行——{@code tryDrainIfLeader()} 整层 try/catch，一次 DB 抖动不能杀死 relay。
 * <p>
 * <b>drain-until-empty（评审"性能"P1）</b>：单次 poll 内循环 claim 直到清空（上限
 * {@code maxBatchesPerPoll} 防饿死），峰值恢复速率从 6.4 msg/s 提升到 ~64 msg/s。
 * <p>
 * <b>at-least-once 语义</b>：send 成功 + DELETE 失败/崩溃 → 行残留 → 下轮重复投递；即时投递与
 * relay 并发 → 双投递。{@code FOR UPDATE SKIP LOCKED} 只防并发 claim 同一行，<b>不消除</b>重复
 * 投递——由消费端 {@code IdempotentHandler}（SETNX）+ DB 唯一约束兜底（文档化权衡，非 bug）。
 * <p>
 * <b>envelope 重建</b>：{@code payload_type} 驱动 {@code Class.forName} + {@code codec.decode}
 * 反序列化（P1-4）；恢复 {@code tag}/{@code hashKey}/{@code headers}（P1-8）；traceparent 用
 * 存储值，<b>不重新 inject</b>（design §5——delegate send 对已存在 traceparent 不覆盖）。
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    /** pending gauge 覆盖的三个 publisher topic（裸名；与 chat/rag 服务常量保持一致）。 */
    static final String[] KNOWN_TOPICS = {
        "chat_message_save", "chat_usage_record", "rag_index_document"
    };

    private final OutboxMapper mapper;
    private final TransactionTemplate tx;
    private final MessageBus delegate;
    private final MessagePayloadCodec codec;
    private final SharedCircuitBreakerGate cbGate;
    private final BackoffSchedule backoff;
    private final MessagingProperties.OutboxConfig config;
    private final OutboxMetrics metrics;
    private final RedissonLeadership leadership;
    private final @Nullable MeterRegistry meterRegistry;

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay-drain");
            t.setDaemon(true);
            return t;
        });

    private volatile boolean running;

    public OutboxRelay(OutboxMapper mapper,
                       TransactionTemplate tx,
                       MessageBus delegate,
                       MessagePayloadCodec codec,
                       SharedCircuitBreakerGate cbGate,
                       BackoffSchedule backoff,
                       MessagingProperties properties,
                       RedissonLeadership leadership,
                       @Nullable OutboxMetrics metrics,
                       @Nullable MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.tx = tx;
        this.delegate = delegate;
        this.codec = codec;
        this.cbGate = cbGate;
        this.backoff = backoff;
        this.config = properties.outbox();
        this.metrics = metrics != null ? metrics : new OutboxMetrics(meterRegistry);
        this.leadership = leadership;
        this.meterRegistry = meterRegistry;
    }

    // ==================== SmartLifecycle ====================

    /** 在 destroyMethod 之前 stop（消费侧 shutdown 之前释放 leader）。 */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 50;
    }

    @Override
    public void start() {
        running = true;
        leadership.start();
        registerGauges();
        scheduler.scheduleAtFixedRate(this::tryDrainIfLeader,
            config.pollInterval().toMillis(), config.pollInterval().toMillis(),
            TimeUnit.MILLISECONDS);
        log.info("Outbox relay started: pollInterval={}, batchSize={}, maxBatchesPerPoll={}",
            config.pollInterval(), config.batchSize(), config.maxBatchesPerPoll());
    }

    @Override
    public void stop() {
        running = false;
        leadership.stop();
        scheduler.shutdown();
        try {
            // drain 可能正在 delegate.send() 网络阻塞；shutdown（非 shutdownNow）会等它完成，带超时
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Outbox relay scheduler did not finish within 5s");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 包级可见：测试断言 leader 状态（leader_active gauge 直接读 leadership）。 */
    boolean isLeader() {
        return leadership.isLeader();
    }

    // ==================== 调度入口 ====================

    /**
     * P0-3：整层 try/catch——scheduleAtFixedRate 任务抛未捕获异常 → 后续执行被永久抑制。
     * 一次 DB 抖动不能杀死 relay 直到进程重启：吞掉、记日志、下轮继续。
     */
    void tryDrainIfLeader() {
        try {
            if (!leadership.isLeader()) {
                return;
            }
            drainUntilEmpty();
        } catch (Throwable e) {
            log.warn("Outbox drain failed, will retry next poll", e);
        }
    }

    /** drain-until-empty（P1）：单 poll 内循环 claim 直到清空，上限 maxBatchesPerPoll 防饿死。 */
    void drainUntilEmpty() {
        for (int batchNo = 0; batchNo < config.maxBatchesPerPoll(); batchNo++) {
            List<OutboxEntry> rows = claimBatch();
            if (rows.isEmpty()) {
                break;   // 本轮清空
            }
            processBatch(rows);
        }
    }

    // ==================== claim / process ====================

    /**
     * Step A：claim（短事务，持锁 ~1ms）——SELECT FOR UPDATE SKIP LOCKED + 同事务标记 claiming
     * （刷新 updated_at，供超时回收）。
     */
    List<OutboxEntry> claimBatch() {
        return tx.execute(status -> {
            Instant now = Instant.now();
            List<OutboxEntry> rows = mapper.claimPending(
                config.batchSize(), now, config.claimingTimeoutSeconds());
            if (!rows.isEmpty()) {
                mapper.markClaiming(rows.stream().map(OutboxEntry::getId).toList(), now);
            }
            return rows;
        });
    }

    /**
     * Step B：事务外 send（MQ 网络 IO）→ Step C：批量 DELETE / gate-defer 短事务。
     */
    void processBatch(List<OutboxEntry> rows) {
        List<Long> deliveredIds = new ArrayList<>(rows.size());
        List<Long> deferIds = new ArrayList<>();
        for (OutboxEntry row : rows) {
            // ★ P1-7：gate OPEN → 不 send、不递增 attempts，仅顺延 next_retry_at（Step C defer 分支）
            if (cbGate.isOpen(row.getTopic())) {
                deferIds.add(row.getId());
                continue;
            }
            try {
                delegate.send(rebuildEnvelope(row));
                deliveredIds.add(row.getId());
                metrics.relayDelivered(row.getTopic());
            } catch (Throwable e) {
                metrics.relayFailed(row.getTopic());
                bumpOrMarkDead(row, e);
            }
        }
        tx.executeWithoutResult(status -> {
            if (!deliveredIds.isEmpty()) {
                mapper.deleteByIds(deliveredIds);
            }
            if (!deferIds.isEmpty()) {
                mapper.deferForRetry(deferIds, Instant.now().plus(config.gateDeferInterval()));
            }
        });
    }

    /** 仅真实投递失败调用：递增 attempts；耗尽 → dead（真正的毒消息）。 */
    private void bumpOrMarkDead(OutboxEntry row, Throwable e) {
        int nextAttempt = row.getAttempts() + 1;
        String reason = e.getMessage() != null
            ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500))
            : e.getClass().getSimpleName();
        if (nextAttempt >= config.maxAttempts()) {
            mapper.markDead(row.getId(), reason);
            metrics.relayDead(row.getTopic());
            log.error("Outbox delivery dead (attempts exhausted): outboxId={}, topic={}, reason={}",
                row.getId(), row.getTopic(), reason);
        } else {
            mapper.bumpAttempts(row.getId(), nextAttempt,
                Instant.now().plusMillis(backoff.next(nextAttempt)));
        }
    }

    /**
     * 运行时类加载。{@code Class.forName} 返回 {@code Class<?>}，Java 类型擦除使泛型参数
     * 无法静态验证——此处是唯一且不可避免的 unchecked 桥（payload_type 为运行时字符串）。
     */
    @SuppressWarnings("unchecked")
    private static <T> Class<T> loadPayloadClass(String name) throws ClassNotFoundException {
        return (Class<T>) Class.forName(name);
    }

    /**
     * envelope 重建（design §4/§5）：payload_type 驱动反序列化；恢复 tag/hashKey/headers；
     * traceparent 用存储值不重新 inject（delegate send 对已存在 traceparent 不覆盖）。
     */
    MessageEnvelope<?> rebuildEnvelope(OutboxEntry row) {
        Class<Object> payloadType;
        try {
            payloadType = loadPayloadClass(row.getPayloadType());
        } catch (ClassNotFoundException e) {
            // payload 类不在 classpath = 部署/env 错误；按普通投递失败路径有界重试 → dead
            throw new MessagingException(
                MessagingErrorCode.CONSUME_FAILED,
                "Outbox payload type not found: " + row.getPayloadType(), e);
        }
        Object payload = codec.decode(row.getPayload().getBytes(StandardCharsets.UTF_8), payloadType);
        return new MessageEnvelope<>(null, row.getTopic(), row.getTag(), payload,
            row.getHashKey(), row.getDedupKey(), parseHeaders(row.getHeaders()),
            row.getCreatedAt().toEpochMilli());
    }

    static Map<String, String> parseHeaders(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, STRING_MAP_TYPE);
        } catch (Exception e) {
            throw new MessagingException(
                MessagingErrorCode.CONSUME_FAILED,
                "Failed to parse outbox headers: " + json, e);
        }
    }

    // ==================== 指标 ====================

    /** R4 gauges：leader_active / pending（按 topic）/ oldest_age_seconds。 */
    private void registerGauges() {
        if (meterRegistry == null) {
            return;
        }
        Gauge.builder("messaging.outbox.leader_active",
                () -> leadership.isLeader() ? 1 : 0)
            .register(meterRegistry);
        Gauge.builder("messaging.outbox.oldest_age_seconds",
                () -> {
                    Instant oldest = mapper.selectOldestCreatedAt();
                    return oldest == null ? 0.0
                        : Duration.between(oldest, Instant.now()).toSeconds();
                })
            .register(meterRegistry);
        for (String topic : KNOWN_TOPICS) {
            Gauge.builder("messaging.outbox.pending",
                    () -> pendingCount(topic))
                .tag("topic", topic)
                .register(meterRegistry);
        }
    }

    private long pendingCount(String topic) {
        try {
            return mapper.selectPendingCountByTopic().stream()
                .filter(c -> topic.equals(c.topic()))
                .mapToLong(OutboxMapper.TopicCount::count)
                .findFirst().orElse(0L);
        } catch (Exception e) {
            // gauge 采样失败不炸 relay：返回 0（scrape 侧可观察到缺失/抖动）
            log.debug("pending gauge query failed: topic={}, err={}", topic, e.getMessage());
            return 0;
        }
    }
}
