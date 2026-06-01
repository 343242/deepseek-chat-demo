package com.smart.rag.infrastructure.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModelCircuitBreakerRegistry")
class ModelCircuitBreakerRegistryTest {

    @Test
    @DisplayName("opens a single model after failure threshold without affecting another model")
    void opensSingleModelAfterThreshold() {
        var registry = new ModelCircuitBreakerRegistry(
                new ModelCircuitBreakerProperties(true, 2, Duration.ofSeconds(5), 1));

        registry.recordFailure("deepseek/chat");
        assertThat(registry.isCallAllowed("deepseek/chat")).isTrue();
        assertThat(registry.stateOf("deepseek/chat")).isEqualTo(CircuitBreakerState.CLOSED);

        registry.recordFailure("deepseek/chat");

        assertThat(registry.isCallAllowed("deepseek/chat")).isFalse();
        assertThat(registry.stateOf("deepseek/chat")).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(registry.isCallAllowed("zhipu/glm")).isTrue();
        assertThat(registry.stateOf("zhipu/glm")).isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    @DisplayName("moves to half open after cooldown and closes on successful probe")
    void halfOpenProbeSuccessClosesBreaker() throws Exception {
        var registry = new ModelCircuitBreakerRegistry(
                new ModelCircuitBreakerProperties(true, 1, Duration.ofMillis(30), 1));

        registry.recordFailure("deepseek/chat");
        assertThat(registry.isCallAllowed("deepseek/chat")).isFalse();

        Thread.sleep(50);

        assertThat(registry.isCallAllowed("deepseek/chat")).isTrue();
        assertThat(registry.stateOf("deepseek/chat")).isEqualTo(CircuitBreakerState.HALF_OPEN);

        registry.recordSuccess("deepseek/chat");

        assertThat(registry.stateOf("deepseek/chat")).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(registry.isCallAllowed("deepseek/chat")).isTrue();
    }

    @Test
    @DisplayName("half open failure reopens breaker and restarts cooldown")
    void halfOpenFailureReopensBreaker() throws Exception {
        var registry = new ModelCircuitBreakerRegistry(
                new ModelCircuitBreakerProperties(true, 1, Duration.ofMillis(60), 1));

        registry.recordFailure("deepseek/chat");
        Thread.sleep(80);
        assertThat(registry.isCallAllowed("deepseek/chat")).isTrue();

        registry.recordFailure("deepseek/chat");

        assertThat(registry.stateOf("deepseek/chat")).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(registry.isCallAllowed("deepseek/chat")).isFalse();
    }

    @Test
    @DisplayName("limits concurrent half open probes")
    void limitsConcurrentHalfOpenProbes() throws Exception {
        var registry = new ModelCircuitBreakerRegistry(
                new ModelCircuitBreakerProperties(true, 1, Duration.ofMillis(20), 1));
        registry.recordFailure("deepseek/chat");
        Thread.sleep(40);

        int threadCount = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);
        var allowed = new AtomicInteger();
        List<Boolean> results = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        boolean result = registry.isCallAllowed("deepseek/chat");
                        synchronized (results) {
                            results.add(result);
                        }
                        if (result) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).hasSize(threadCount);
        assertThat(allowed.get()).isEqualTo(1);
        assertThat(registry.stateOf("deepseek/chat")).isEqualTo(CircuitBreakerState.HALF_OPEN);
    }

    @Test
    @DisplayName("releases half open probe without changing state when request is cancelled")
    void releaseHalfOpenProbeAllowsAnotherProbe() throws Exception {
        var registry = new ModelCircuitBreakerRegistry(
                new ModelCircuitBreakerProperties(true, 1, Duration.ofMillis(20), 1));
        registry.recordFailure("deepseek/chat");
        Thread.sleep(40);

        assertThat(registry.isCallAllowed("deepseek/chat")).isTrue();
        assertThat(registry.isCallAllowed("deepseek/chat")).isFalse();

        registry.releaseProbe("deepseek/chat");

        assertThat(registry.stateOf("deepseek/chat")).isEqualTo(CircuitBreakerState.HALF_OPEN);
        assertThat(registry.isCallAllowed("deepseek/chat")).isTrue();
    }

    @Test
    @DisplayName("disabled breaker always allows calls and ignores state transitions")
    void disabledBreakerAlwaysAllowsCalls() {
        var registry = new ModelCircuitBreakerRegistry(
                new ModelCircuitBreakerProperties(false, 1, Duration.ofSeconds(5), 1));

        registry.recordFailure("deepseek/chat");
        registry.recordFailure("deepseek/chat");

        assertThat(registry.isCallAllowed("deepseek/chat")).isTrue();
        assertThat(registry.stateOf("deepseek/chat")).isEqualTo(CircuitBreakerState.CLOSED);
    }
}
