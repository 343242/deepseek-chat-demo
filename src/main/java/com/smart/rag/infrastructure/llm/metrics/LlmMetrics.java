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
     */
    public void registerCircuitBreakerGauge(String candidateId, Supplier<CircuitBreakerState> stateSupplier) {
        if (registry == null) return;
        if (!registeredGauges.add(candidateId)) return; // already registered
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

    // ==================== Retry ====================

    public void recordRetryAttempt(String candidateId, String result) {
        if (registry == null) return;
        registry.counter("llm.retry.attempts", "candidateId", candidateId, "result", result)
            .increment();
    }
}
