package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.CircuitOpenException;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.ProbeTimeoutException;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

/**
 * Unit tests for {@link CircuitBreaker}.
 * <p>
 * Verifies the adapter semantics: registry gating, eligibility-filtered failure
 * counting, and probe lifecycle on the streaming path.
 */
@ExtendWith(MockitoExtension.class)
class CircuitBreakerTest {

    private static final String CANDIDATE_ID = "test-candidate";

    @Mock
    private ModelCircuitBreakerRegistry registry;

    @Mock
    private FallbackEligibility eligibility;

    @Mock
    private LlmMetrics metrics;

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        // Use the no-metrics constructor to avoid gauge registration interference
        breaker = new CircuitBreaker(registry, eligibility, CANDIDATE_ID, RemoteErrorCode.LLM_CIRCUIT_BREAKER_OPEN, null);
    }

    // ==================== execute (blocking) ====================

    @Nested
    @DisplayName("execute(action) — blocking")
    class ExecuteBlockingTests {

        @Test
        @DisplayName("OPEN state throws CircuitOpenException, action NOT invoked")
        void openStateRejects() {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(false);

            AtomicInteger calls = new AtomicInteger(0);
            assertThatThrownBy(() -> breaker.execute(() -> {
                calls.incrementAndGet();
                return "x";
            })).isInstanceOf(CircuitOpenException.class)
                .hasMessageContaining(CANDIDATE_ID);

            assertThat(calls.get()).isEqualTo(0);
            verify(registry, never()).recordSuccess(any());
            verify(registry, never()).recordFailure(any());
        }

        @Test
        @DisplayName("successful action records success")
        void successRecordsSuccess() throws Exception {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(true);

            String result = breaker.execute(() -> "ok");

            assertThat(result).isEqualTo("ok");
            verify(registry, times(1)).recordSuccess(CANDIDATE_ID);
            verify(registry, never()).recordFailure(any());
        }

        @Test
        @DisplayName("eligible failure records failure")
        void eligibleFailureRecordsFailure() {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(true);
            IOException infra = new IOException("timeout");
            when(eligibility.isEligible(infra)).thenReturn(true);

            assertThatThrownBy(() -> breaker.execute(() -> {
                throw infra;
            })).isSameAs(infra);

            verify(registry, times(1)).recordFailure(CANDIDATE_ID);
            verify(registry, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("non-eligible failure does NOT record failure (user error)")
        void nonEligibleFailureDoesNotRecord() {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(true);
            IllegalArgumentException userError = new IllegalArgumentException("bad");
            when(eligibility.isEligible(userError)).thenReturn(false);

            assertThatThrownBy(() -> breaker.execute(() -> {
                throw userError;
            })).isSameAs(userError);

            verify(registry, never()).recordFailure(any());
            verify(registry, never()).recordSuccess(any());
        }
    }

    // ==================== executeStream ====================

    @Nested
    @DisplayName("executeStream(streamSupplier) — reactive")
    class ExecuteStreamTests {

        @Test
        @DisplayName("OPEN state emits CircuitOpenException error")
        void openStateEmitsError() {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(false);

            Throwable error = blockExpectError(breaker.executeStream(() -> Flux.just("x")));

            assertThat(error).isInstanceOf(CircuitOpenException.class);
            verify(registry, never()).recordSuccess(any());
            verify(registry, never()).recordFailure(any());
        }

        @Test
        @DisplayName("successful stream completion records success and releases probe")
        void successfulStreamRecordsSuccess() {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(true);

            List<String> items = breaker.executeStream(() -> Flux.just("a", "b"))
                .collectList().block();

            assertThat(items).containsExactly("a", "b");
            verify(registry, times(1)).recordSuccess(CANDIDATE_ID);
            verify(registry, times(1)).releaseProbe(CANDIDATE_ID);
            verify(registry, never()).recordFailure(any());
        }

        @Test
        @DisplayName("eligible stream error records failure and releases probe")
        void eligibleStreamErrorRecordsFailure() {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(true);
            IOException infra = new IOException("stream broke");
            when(eligibility.isEligible(infra)).thenReturn(true);

            Throwable error = blockExpectError(breaker.executeStream(() -> Flux.error(infra)));

            assertThat(error).isInstanceOf(IOException.class);
            verify(registry, times(1)).recordFailure(CANDIDATE_ID);
            verify(registry, times(1)).releaseProbe(CANDIDATE_ID);
        }

        @Test
        @DisplayName("ProbeTimeoutException does NOT record failure (handled by ProbeStreamHandler)")
        void probeTimeoutDoesNotRecordFailure() {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(true);
            // ProbeTimeoutException bypasses failure recording unconditionally

            Throwable error = blockExpectError(breaker.executeStream(
                () -> Flux.error(new ProbeTimeoutException("no first byte"))));

            assertThat(error).isInstanceOf(ProbeTimeoutException.class);
            verify(registry, never()).recordFailure(any());
            verify(registry, times(1)).releaseProbe(CANDIDATE_ID);
        }

        @Test
        @DisplayName("stream cancellation releases probe")
        void streamCancellationReleasesProbe() {
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(true);

            breaker.executeStream(() -> Flux.<String>never())
                .subscribe().dispose();

            // Allow reactor to process dispose signal
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            verify(registry, times(1)).releaseProbe(CANDIDATE_ID);
            verify(registry, never()).recordSuccess(any());
            verify(registry, never()).recordFailure(any());
        }

        @Test
        @DisplayName("stream cancellation after partial emission still releases probe, no counters")
        void streamCancellationAfterEmissionReleasesProbe() {
            // Validates doFinally fires on CANCEL even after items were emitted downstream.
            // Uses Flux.create so the source does not auto-complete after emit, forcing
            // explicit cancel via dispose().
            when(registry.isCallAllowed(CANDIDATE_ID)).thenReturn(true);

            breaker.executeStream(() -> Flux.<String>create(sink -> {
                sink.next("first");
                // do not complete — wait for downstream cancel
            })).subscribe().dispose();

            // Allow reactor to process dispose signal
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            verify(registry, times(1)).releaseProbe(CANDIDATE_ID);
            // Cancel path: no success/failure counter update
            verify(registry, never()).recordSuccess(any());
            verify(registry, never()).recordFailure(any());
        }
    }

    // ==================== accessors ====================

    @Nested
    @DisplayName("getState / recordProbeSuccess")
    class AccessorTests {

        @Test
        @DisplayName("getState delegates to registry")
        void getStateDelegates() {
            when(registry.stateOf(CANDIDATE_ID)).thenReturn(CircuitBreakerState.OPEN);

            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
        }

        @Test
        @DisplayName("recordProbeSuccess — HALF_OPEN → CLOSED transition returns true")
        void recordProbeSuccessRecoverable() {
            when(registry.tryRecoverFromHalfOpen(CANDIDATE_ID)).thenReturn(true);

            breaker.recordProbeSuccess();
            verify(registry, times(1)).tryRecoverFromHalfOpen(CANDIDATE_ID);
        }

        @Test
        @DisplayName("recordProbeSuccess — CLOSED state is no-op")
        void recordProbeSuccessNoOp() {
            when(registry.tryRecoverFromHalfOpen(CANDIDATE_ID)).thenReturn(false);

            breaker.recordProbeSuccess();
            verify(registry, times(1)).tryRecoverFromHalfOpen(CANDIDATE_ID);
        }
    }

    // ==================== constructor / metrics wiring ====================

    @Test
    @DisplayName("constructor with non-null metrics registers circuit breaker gauge")
    void constructorRegistersGauge() {
        // Constructor passes this::getState as Supplier without invoking it.
        // No stateOf stub needed — gauge is registered lazily and called on-demand.
        new CircuitBreaker(registry, eligibility, "with-metrics", RemoteErrorCode.LLM_CIRCUIT_BREAKER_OPEN, metrics);

        verify(metrics, times(1)).registerCircuitBreakerGauge(
            eq("with-metrics"), any(Supplier.class));
    }

    @Test
    @DisplayName("constructor with null metrics does not register gauge (null-safe)")
    void constructorWithNullMetricsSkipsRegistration() {
        // setUp() constructs breaker with null metrics — implicit verification
        // that no NPE is thrown
        verify(metrics, never()).registerCircuitBreakerGauge(any(), any());
    }

    // ==================== helpers ====================

    /**
     * Block on a Flux that is expected to error, returning the cause.
     * Throws AssertionError if the Flux completes successfully instead.
     */
    private static <T> Throwable blockExpectError(Flux<T> flux) {
        try {
            flux.collectList().block();
            throw new AssertionError("Expected Flux to error, but it completed successfully");
        } catch (Throwable t) {
            // Reactor wraps errors in RuntimeException on blocking — unwrap
            Throwable cur = t;
            while (cur.getCause() != null && cur != cur.getCause()
                && cur.getClass().getName().startsWith("reactor.")) {
                cur = cur.getCause();
            }
            return cur;
        }
    }
}
