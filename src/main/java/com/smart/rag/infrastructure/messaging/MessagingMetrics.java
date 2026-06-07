package com.smart.rag.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Centralized metrics for the messaging module.
 * <p>
 * Null-safe wrapper around {@link MeterRegistry} — all methods are no-ops
 * when no registry is configured.
 */
public class MessagingMetrics {

    private final MeterRegistry registry;

    MessagingMetrics(@Nullable MeterRegistry registry) {
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

    // ==================== Idempotent ====================

    public void recordIdempotentDegraded(String topic) {
        if (registry == null) return;
        registry.counter("messaging.idempotent.degraded", "topic", topic).increment();
    }
}
