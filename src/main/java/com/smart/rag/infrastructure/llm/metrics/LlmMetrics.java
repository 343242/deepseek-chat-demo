package com.smart.rag.infrastructure.llm.metrics;

import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.llm.LlmCapability;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Centralized metrics for the LLM module.
 * <p>
 * Null-safe wrapper around {@link MeterRegistry} — all methods are no-ops
 * when no registry is configured (e.g., when Actuator is not on the classpath).
 * <p>
 * Follows the same pattern as {@code MessagingMetrics}.
 */
public class LlmMetrics {

    private final MeterRegistry registry;
    private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();

    public LlmMetrics(@Nullable MeterRegistry registry) {
        this.registry = registry;
    }

    public long startNanos() {
        return registry != null ? System.nanoTime() : 0;
    }

    // ==================== Chat ====================

    public void recordChatLatency(String candidateId, long startNanos, String result) {
        if (registry == null) return;
        registry.timer("llm.chat.latency", "candidateId", candidateId, "result", result)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    public void recordTokens(String candidateId, String operation, int count) {
        if (registry == null) return;
        registry.counter("llm.chat.tokens", "candidateId", candidateId, "operation", operation)
            .increment(count);
    }

    /**
     * 流式生成取消计数（design chat-stream-cancel.md §6.3）。
     * 标签 {@code reason} 区分 USER_ABORT / NAVIGATE_AWAY / SESSION_SWITCH。
     */
    public void recordStreamCancelled(String reason) {
        if (registry == null) return;
        registry.counter("chat.stream.cancelled", "reason", reason).increment();
    }

    // ==================== Embedding ====================

    public void recordEmbedLatency(String candidateId, long startNanos, String result) {
        if (registry == null) return;
        registry.timer("llm.embed.latency", "candidateId", candidateId, "result", result)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    // ==================== Rerank ====================

    public void recordRerankLatency(String candidateId, long startNanos, String result) {
        if (registry == null) return;
        registry.timer("llm.rerank.latency", "candidateId", candidateId, "result", result)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    // ==================== Circuit Breaker ====================

    /**
     * Register a gauge tracking circuit breaker state for a candidate.
     * Call once per candidate at creation time.
     * <p>
     * Value: 0=CLOSED, 1=HALF_OPEN, 2=OPEN
     * <p>
     * <b>幂等保护</b>：通过 {@link #registeredGauges} 集合去重，
     * 对同一 {@code candidateId} 的重复注册是 no-op（{@link Set#add} 返回 false 时跳过）。
     * 这避免了 {@code LlmClientRegistry.refresh()} 在重建 snapshot 时重复注册
     * gauge 导致 Micrometer {@link MeterRegistry} 内存增长（每个 Gauge 引用持有的 supplier）。
     * {@code CircuitBreaker} 实例在 refresh 时由 {@code LlmClientRegistry} 复用（不重建），
     * 但即使重建也不会重复注册。
     */
    public void registerCircuitBreakerGauge(String candidateId, Supplier<CircuitBreakerState> stateSupplier) {
        if (registry == null) return;
        if (!registeredGauges.add(candidateId)) return; // idempotent: already registered → no-op
        registry.gauge("llm.circuit.state",
            io.micrometer.core.instrument.Tags.of("candidateId", candidateId),
            stateSupplier, s -> switch (s.get()) {
                case CLOSED -> 0;
                case HALF_OPEN -> 1;
                case OPEN -> 2;
            });
    }

    // ==================== Fallback ====================

    public void recordFallback(LlmCapability capability, String from, String to) {
        if (registry == null) return;
        registry.counter("llm.fallback.invocations",
                "capability", capability.name(), "from", from, "to", to)
            .increment();
    }

    // ==================== BYOK ====================

    /**
     * BYOK fallback 触发（design §5.4 / R1 / AC29）：
     * 用户有 BYOK 配置但全部 status=0（disabled）→ fallback yml 时计数，
     * 区别于"从未配置"（无行 → 无 counter，正常 fallback）。
     */
    public void recordByokFallback(String reason) {
        if (registry == null) return;
        registry.counter("llm.byok.fallback", "reason", reason).increment();
    }

    /**
     * BYOK 异步 close 失败计数（design §5.3 / R3 / AC31）：
     * invalidate/淘汰时异步 close 旧 client 异常 → counter + WARN（不抛，不影响 invalidateUser 调用方）。
     */
    public void recordByokCloseError() {
        if (registry == null) return;
        registry.counter("llm.byok.close.errors").increment();
    }

    // ==================== Retry ====================

    public void recordRetryAttempt(String candidateId, String result) {
        if (registry == null) return;
        registry.counter("llm.retry.attempts", "candidateId", candidateId, "result", result)
            .increment();
    }

    // ==================== Client Initialization ====================

    /**
     * Record a client initialization failure. Allows monitoring/alerting when
     * {@code LlmClientFactory.createRawClient} silently skips a misconfigured candidate
     * (returns null instead of failing fast).
     */
    public void recordClientInitFailure(String candidateId) {
        if (registry == null) return;
        registry.counter("llm.client.init.failures", "candidateId", candidateId)
            .increment();
    }
}
