package com.smart.rag.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized metrics for the messaging module.
 * <p>
 * Null-safe wrapper around {@link MeterRegistry} — all methods are no-ops
 * when no registry is configured.
 */
public class MessagingMetrics {

    private final MeterRegistry registry;

    /** O-03: 每 (topic,group) 最近一次成功 receive 的 epoch ms。receive 抛异常时不更新 → 监控据此检测消费者卡死。 */
    private final Map<String, AtomicLong> lastReceiveSuccess = new ConcurrentHashMap<>();

    public MessagingMetrics(@Nullable MeterRegistry registry) {
        this.registry = registry;
    }

    public long startNanos() { return registry != null ? System.nanoTime() : 0; }

    // ==================== Send ====================

    public void recordSendSuccess(String topic, long startNanos, int payloadSize) {
        if (registry == null) return;
        registry.counter("messaging.send.count", "topic", topic, "result", "success").increment();
        registry.timer("messaging.send.latency", "topic", topic)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        registry.summary("messaging.send.payload.size", "topic", topic).record(payloadSize);
    }

    public void recordSendFailure(String topic) {
        if (registry == null) return;
        registry.counter("messaging.send.count", "topic", topic, "result", "fail").increment();
    }

    public void recordPostCommitFail(String topic) {
        if (registry == null) return;
        registry.counter("messaging.send.post_commit_fail", "topic", topic).increment();
    }

    // ==================== Consume ====================

    public void recordConsumeSuccess(String topic, String group, String mode, long startNanos) {
        if (registry == null) return;
        registry.counter("messaging.consume.count",
            "topic", topic, "group", group, "mode", mode, "result", "success").increment();
        registry.timer("messaging.consume.latency",
            "topic", topic, "group", group, "mode", mode)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    public void recordConsumeFailure(String topic, String group, String mode) {
        if (registry == null) return;
        registry.counter("messaging.consume.count",
            "topic", topic, "group", group, "mode", mode, "result", "fail").increment();
    }

    /**
     * O-03: 记录 SimpleConsumer 最近一次成功 receive 的时间戳（epoch ms）。
     * <p>
     * 即使本次拉取为空也更新——{@code receive()} 无异常返回即证明消费者存活、Broker 可达。
     * {@code receive()} 抛异常（进入退避）时不更新，gauge 值变陈旧，监控用
     * {@code now - last.success > N × invisibleDuration} 检测消费者卡死。
     */
    public void recordReceiveSuccess(String topic, String group) {
        if (registry == null) return;
        AtomicLong ts = lastReceiveSuccess.computeIfAbsent(topic + ":" + group, k -> {
            AtomicLong holder = new AtomicLong(0L);
            registry.gauge("messaging.consumer.receive.last.success",
                Tags.of("topic", topic, "group", group), holder, AtomicLong::doubleValue);
            return holder;
        });
        ts.set(System.currentTimeMillis());
    }

    // ==================== Retry ====================

    public void recordRetry(String topic, String group, String mode, String attempt) {
        if (registry == null) return;
        registry.counter("messaging.retry.count",
            "topic", topic, "group", group, "mode", mode, "attempt", attempt).increment();
    }

    // ==================== Dead Letter ====================

    public void recordDeadLetter(String topic, String group) {
        if (registry == null) return;
        registry.counter("messaging.dead.count", "topic", topic, "group", group).increment();
    }

    // ==================== Redis Stream（design §9） ====================

    /** pollLoop XREADGROUP 连接级失败（Redis 宕机/主从切换/网络分区，退避重连中）。 */
    public void recordConsumeConnectionFailure(String topic, String group) {
        if (registry == null) return;
        registry.counter("messaging.consume.connection.failure",
            "topic", topic, "group", group).increment();
    }

    /** RetrySweeper 回灌成功（ZSET → 主 stream）。 */
    public void recordRetryRedelivered(String topic, String group, int count) {
        if (registry == null) return;
        registry.counter("messaging.retry.redelivered",
            "topic", topic, "group", group).increment(count);
    }

    /** RetrySweeper 孤儿清理（zset/hash 不一致）。 */
    public void recordRetryOrphan(String topic, String group, int count) {
        if (registry == null) return;
        registry.counter("messaging.retry.orphan.count",
            "topic", topic, "group", group).increment(count);
    }

    /** XGROUP CREATE 失败（非 BUSYGROUP）。 */
    public void recordGroupCreateFailed(String topic, String group) {
        if (registry == null) return;
        registry.counter("messaging.stream.group.create.failed",
            "topic", topic, "group", group).increment();
    }

    /** 主 stream lag（XLEN − ΣXPENDING）超 trim-threshold 告警阈值。 */
    public void recordTrimThresholdExceeded(String streamKey) {
        if (registry == null) return;
        registry.counter("messaging.stream.trim.threshold.exceeded", "stream", streamKey).increment();
    }

    // ==================== Idempotent ====================

    public void recordIdempotentDegraded(String topic) {
        if (registry == null) return;
        registry.counter("messaging.idempotent.degraded", "topic", topic).increment();
    }
}
