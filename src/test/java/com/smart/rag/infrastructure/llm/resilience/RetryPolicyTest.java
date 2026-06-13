package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.ModelCircuitOpenException;
import com.smart.rag.infrastructure.fallback.ProbeTimeoutException;
import com.smart.rag.infrastructure.llm.config.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RetryPolicy}.
 * <p>
 * Uses a near-zero backoff configuration (1ms) to keep tests fast while still
 * exercising the exponential delay calculation.
 */
class RetryPolicyTest {

    private RetryPolicy policy;

    @BeforeEach
    void setUp() {
        // baseDelay=1ms, maxDelay=1ms, multiplier=1.0 → all delays 1ms
        policy = new RetryPolicy(new RetryConfig(3, 1L, 1L, 1.0));
    }

    // ==================== isRetryable ====================

    @Nested
    @DisplayName("isRetryable(Throwable)")
    class IsRetryableTests {

        @Test
        @DisplayName("ModelCircuitOpenException is NOT retryable (circuit open is a fallback trigger, not retry)")
        void circuitOpenIsNotRetryable() {
            assertThat(policy.isRetryable(new ModelCircuitOpenException("c1"))).isFalse();
        }

        @Test
        @DisplayName("UnsupportedOperationException is NOT retryable (programming error)")
        void unsupportedOperationIsNotRetryable() {
            assertThat(policy.isRetryable(new UnsupportedOperationException("no stream"))).isFalse();
        }

        @Test
        @DisplayName("RemoteException with LLM_RATE_LIMITED is retryable (429)")
        void rateLimitedIsRetryable() {
            RemoteException e = new RemoteException(RemoteErrorCode.LLM_RATE_LIMITED, "429");
            assertThat(policy.isRetryable(e)).isTrue();
        }

        @Test
        @DisplayName("RemoteException with LLM_TRANSIENT_ERROR is retryable (5xx / timeout)")
        void transientErrorIsRetryable() {
            RemoteException e = new RemoteException(RemoteErrorCode.LLM_TRANSIENT_ERROR, "503");
            assertThat(policy.isRetryable(e)).isTrue();
        }

        @Test
        @DisplayName("RemoteException with LLM_ALL_MODELS_FAILED is NOT retryable (chain exhausted)")
        void allModelsFailedIsNotRetryable() {
            RemoteException e = new RemoteException(RemoteErrorCode.LLM_ALL_MODELS_FAILED, "exhausted");
            assertThat(policy.isRetryable(e)).isFalse();
        }

        @Test
        @DisplayName("RemoteException with LLM_CONFIG_ERROR is NOT retryable (config issue)")
        void configErrorIsNotRetryable() {
            RemoteException e = new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR, "bad config");
            assertThat(policy.isRetryable(e)).isFalse();
        }

        @Test
        @DisplayName("IOException is retryable")
        void ioExceptionIsRetryable() {
            assertThat(policy.isRetryable(new IOException("network"))).isTrue();
        }

        @Test
        @DisplayName("ProbeTimeoutException is NOT retryable (extends RemoteException with LLM_PROBE_TIMEOUT code, not in retryable list)")
        void probeTimeoutIsNotRetryable() {
            // ProbeTimeoutException extends RemoteException; isRetryable checks RemoteException first
            // and only LLM_RATE_LIMITED / LLM_TRANSIENT_ERROR are retryable. LLM_PROBE_TIMEOUT is excluded.
            assertThat(policy.isRetryable(new ProbeTimeoutException("no first byte"))).isFalse();
        }

        @Test
        @DisplayName("java.util.concurrent.TimeoutException is retryable")
        void jucTimeoutIsRetryable() {
            assertThat(policy.isRetryable(new TimeoutException())).isTrue();
        }

        @Test
        @DisplayName("NullPointerException is NOT retryable (programming error)")
        void npeIsNotRetryable() {
            assertThat(policy.isRetryable(new NullPointerException())).isFalse();
        }

        @Test
        @DisplayName("IllegalArgumentException is NOT retryable (programming error)")
        void iaeIsNotRetryable() {
            assertThat(policy.isRetryable(new IllegalArgumentException("bad arg"))).isFalse();
        }

        @Test
        @DisplayName("Generic RuntimeException with IOException cause IS retryable (unwrap cause)")
        void wrappedIoExceptionIsRetryable() {
            RuntimeException wrapped = new RuntimeException("wrapped", new IOException("inner"));
            assertThat(policy.isRetryable(wrapped)).isTrue();
        }

        @Test
        @DisplayName("Generic RuntimeException with no IOException cause is NOT retryable")
        void genericRuntimeIsNotRetryable() {
            assertThat(policy.isRetryable(new RuntimeException("unrelated"))).isFalse();
        }
    }

    // ==================== executeWithBackoff ====================

    @Nested
    @DisplayName("executeWithBackoff(CheckedSupplier)")
    class ExecuteWithBackoffTests {

        @Test
        @DisplayName("first-attempt success returns value with no retries")
        void firstAttemptSuccess() throws Exception {
            AtomicInteger calls = new AtomicInteger(0);
            String result = policy.executeWithBackoff(() -> {
                calls.incrementAndGet();
                return "ok";
            });

            assertThat(result).isEqualTo("ok");
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("non-retryable exception propagates immediately without retry")
        void nonRetryablePropagatesImmediately() {
            AtomicInteger calls = new AtomicInteger(0);

            assertThatThrownBy(() -> policy.executeWithBackoff(() -> {
                calls.incrementAndGet();
                throw new IllegalArgumentException("won't retry");
            })).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("won't retry");

            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("retryable exception triggers retry up to maxAttempts")
        void retryableExceptionRetriesUntilMaxAttempts() {
            AtomicInteger calls = new AtomicInteger(0);

            assertThatThrownBy(() -> policy.executeWithBackoff(() -> {
                calls.incrementAndGet();
                throw new IOException("transient");
            })).isInstanceOf(RemoteException.class)
                .hasMessageContaining("3 attempts")
                .hasCauseInstanceOf(IOException.class);

            // maxAttempts=3 → 3 calls total
            assertThat(calls.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("retry succeeds on second attempt returns value")
        void retrySucceedsOnSecondAttempt() throws Exception {
            AtomicInteger calls = new AtomicInteger(0);
            String result = policy.executeWithBackoff(() -> {
                if (calls.incrementAndGet() == 1) {
                    throw new IOException("first fails");
                }
                return "recovered";
            });

            assertThat(result).isEqualTo("recovered");
            assertThat(calls.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("exhausted retries wrap cause as RemoteException(LLM_TRANSIENT_ERROR)")
        void exhaustedRetriesWrappedAsTransientError() {
            RemoteException ex = (RemoteException) catchThrowable(() ->
                policy.executeWithBackoff(() -> {
                    throw new TimeoutException();
                }));

            assertThat(ex.getErrorCode()).isEqualTo(RemoteErrorCode.LLM_TRANSIENT_ERROR);
            assertThat(ex.getMessage()).contains("3 attempts");
            assertThat(ex.getCause()).isInstanceOf(TimeoutException.class);
        }

        @Test
        @DisplayName("maxAttempts=1 → no retry, first failure propagates as RemoteException")
        void singleAttemptNoRetry() {
            RetryPolicy noRetry = new RetryPolicy(new RetryConfig(1, 1L, 1L, 1.0));
            AtomicInteger calls = new AtomicInteger(0);

            assertThatThrownBy(() -> noRetry.executeWithBackoff(() -> {
                calls.incrementAndGet();
                throw new IOException("only chance");
            })).isInstanceOf(RemoteException.class);

            assertThat(calls.get()).isEqualTo(1);
        }
    }

    // ==================== executeDirect ====================

    @Nested
    @DisplayName("executeDirect(CheckedSupplier)")
    class ExecuteDirectTests {

        @Test
        @DisplayName("returns value without any retry logic")
        void returnsValue() throws Exception {
            String result = policy.executeDirect(() -> "direct");
            assertThat(result).isEqualTo("direct");
        }

        @Test
        @DisplayName("propagates exception as-is (no wrapping)")
        void propagatesException() {
            assertThatThrownBy(() -> policy.executeDirect(() -> {
                throw new IOException("passthrough");
            })).isInstanceOf(IOException.class)
                .hasMessage("passthrough");
        }
    }

    // ==================== retryStream ====================

    @Nested
    @DisplayName("retryStream(Supplier<Flux>)")
    class RetryStreamTests {

        @Test
        @DisplayName("successful stream emits all items without retry")
        void successfulStreamEmitsAllItems() {
            AtomicInteger subscribes = new AtomicInteger(0);
            var flux = policy.<String>retryStream(() -> {
                subscribes.incrementAndGet();
                reactor.core.publisher.Flux<String> f = reactor.core.publisher.Flux.just("a", "b", "c");
                return f;
            });

            java.util.List<String> items = flux.collectList().block();
            assertThat(items).containsExactly("a", "b", "c");
            assertThat(subscribes.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("retryable error before any emit triggers resubscribe")
        void retryBeforeEmitResubscribes() {
            AtomicInteger subscribes = new AtomicInteger(0);
            var flux = policy.<String>retryStream(() -> {
                int n = subscribes.incrementAndGet();
                if (n < 2) {
                    return reactor.core.publisher.Flux.error(new IOException("transient"));
                }
                return reactor.core.publisher.Flux.just("ok");
            });

            java.util.List<String> items = flux.collectList().block();
            assertThat(items).containsExactly("ok");
            assertThat(subscribes.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("non-retryable error propagates without retry")
        void nonRetryableErrorDoesNotRetry() {
            AtomicInteger subscribes = new AtomicInteger(0);
            var flux = policy.<String>retryStream(() -> {
                subscribes.incrementAndGet();
                return reactor.core.publisher.Flux.<String>error(new IllegalArgumentException("nope"));
            });

            assertThatThrownBy(() -> flux.collectList().block())
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(subscribes.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("error AFTER items already emitted does NOT retry (avoid duplicates)")
        void errorAfterEmitDoesNotRetry() {
            AtomicInteger subscribes = new AtomicInteger(0);
            var flux = policy.<String>retryStream(() -> {
                subscribes.incrementAndGet();
                return reactor.core.publisher.Flux.<String>create(sink -> {
                    sink.next("partial");
                    sink.error(new IOException("stream broke"));
                });
            });

            // Should not retry — error propagates (reactor wraps checked exceptions as ReactiveException)
            assertThatThrownBy(() -> flux.collectList().block())
                .hasCauseInstanceOf(IOException.class);
            assertThat(subscribes.get()).isEqualTo(1);
        }
    }

    // ==================== helpers ====================

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Throwable catchThrowable(ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("Expected exception to be thrown");
        } catch (Throwable t) {
            return t;
        }
    }
}
