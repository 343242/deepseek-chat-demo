package com.smart.rag.infrastructure.fallback.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SharedProbeRegistry")
class SharedProbeRegistryTest {

    @Test
    @DisplayName("first registration succeeds, second returns null (dedup)")
    void deduplicatesConcurrentRegistrations() {
        var registry = new SharedProbeRegistry();

        CompletableFuture<ProbeResult> first = registry.tryRegister("model-a");
        CompletableFuture<ProbeResult> second = registry.tryRegister("model-a");

        assertThat(first).isNotNull();
        assertThat(second).isNull();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("different models register independently")
    void differentModelsRegisterIndependently() {
        var registry = new SharedProbeRegistry();

        CompletableFuture<ProbeResult> probeA = registry.tryRegister("model-a");
        CompletableFuture<ProbeResult> probeB = registry.tryRegister("model-b");

        assertThat(probeA).isNotNull();
        assertThat(probeB).isNotNull();
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("auto-cleanup after successful completion")
    void autoCleanupOnSuccess() {
        var registry = new SharedProbeRegistry();

        CompletableFuture<ProbeResult> probe = registry.tryRegister("model-a");
        probe.complete(ProbeResult.success("model-a", 150));

        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("auto-cleanup after exceptional completion")
    void autoCleanupOnFailure() {
        var registry = new SharedProbeRegistry();

        CompletableFuture<ProbeResult> probe = registry.tryRegister("model-a");
        registry.fail("model-a", new RuntimeException("probe failed"));

        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("can re-register after completion")
    void canReRegisterAfterCompletion() {
        var registry = new SharedProbeRegistry();

        CompletableFuture<ProbeResult> first = registry.tryRegister("model-a");
        first.complete(ProbeResult.success("model-a", 100));

        CompletableFuture<ProbeResult> second = registry.tryRegister("model-a");
        assertThat(second).isNotNull();
    }

    @Test
    @DisplayName("getInFlight returns registered probe")
    void getInFlightReturnsProbe() {
        var registry = new SharedProbeRegistry();

        CompletableFuture<ProbeResult> probe = registry.tryRegister("model-a");
        assertThat(registry.getInFlight("model-a")).isSameAs(probe);
        assertThat(registry.getInFlight("model-b")).isNull();
    }

    @Test
    @DisplayName("concurrent threads share the same probe")
    void concurrentThreadsShareProbe() throws Exception {
        var registry = new SharedProbeRegistry();
        int threadCount = 10;
        var readyLatch = new CountDownLatch(threadCount);
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threadCount);
        AtomicInteger registerCount = new AtomicInteger();
        AtomicInteger dedupCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    CompletableFuture<ProbeResult> probe = registry.tryRegister("model-a");
                    if (probe != null) {
                        registerCount.incrementAndGet();
                        // Do NOT complete — keep probe in-flight so other threads see it
                    } else {
                        dedupCount.incrementAndGet();
                    }
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        readyLatch.await();
        startLatch.countDown(); // release all threads simultaneously
        doneLatch.await(5, TimeUnit.SECONDS);

        assertThat(registerCount.get()).isEqualTo(1);
        assertThat(dedupCount.get()).isEqualTo(threadCount - 1);
    }
}
