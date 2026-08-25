package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RateLimitedException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.CircuitOpenException;
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
        @DisplayName("CircuitOpenException is NOT retryable (circuit open is a fallback trigger, not retry)")
        void circuitOpenIsNotRetryable() {
            assertThat(policy.isRetryable(new CircuitOpenException(RemoteErrorCode.LLM_CIRCUIT_BREAKER_OPEN, "c1"))).isFalse();
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

    // ==================== computeDelay / Retry-After（WS1） ====================

    @Nested
    @DisplayName("computeDelay（退避计算）")
    class ComputeDelayTests {

        @Test
        @DisplayName("普通退避带 jitter：延迟 ∈ [0.5×, 1.5×) 计算值")
        void jitterBounds() {
            // base=1000, max=5000, multiplier=2 → attempt 0 计算值 1000，jitter ∈ [500, 1500)
            RetryPolicy p = new RetryPolicy(new RetryConfig(3, 1000L, 5000L, 2.0));
            for (int i = 0; i < 200; i++) {
                long d = p.computeDelay(new IOException("io"), 0);
                assertThat(d).isGreaterThanOrEqualTo(500L).isLessThan(1500L);
            }
        }

        @Test
        @DisplayName("指数退避 cap 到 maxDelayMs 后 jitter 仍生效")
        void jitterAppliesAfterCap() {
            RetryPolicy p = new RetryPolicy(new RetryConfig(3, 1000L, 2000L, 10.0));
            for (int i = 0; i < 100; i++) {
                long d = p.computeDelay(new IOException("io"), 5);
                assertThat(d).isGreaterThanOrEqualTo(1000L).isLessThan(3000L);
            }
        }

        @Test
        @DisplayName("RateLimitedException 携带 Retry-After → 原样延迟，不叠 jitter、不受 maxDelayMs 约束")
        void retryAfterUsedAsIs() {
            RetryPolicy p = new RetryPolicy(new RetryConfig(3, 1L, 1L, 1.0));
            long d = p.computeDelay(new RateLimitedException("429", 30_000L), 0);
            assertThat(d).isEqualTo(30_000L);
        }

        @Test
        @DisplayName("RateLimitedException 无 Retry-After → 走普通指数退避 + jitter")
        void nullRetryAfterFallsBackToBackoff() {
            RetryPolicy p = new RetryPolicy(new RetryConfig(3, 1000L, 5000L, 2.0));
            for (int i = 0; i < 100; i++) {
                long d = p.computeDelay(new RateLimitedException("429", null), 0);
                assertThat(d).isGreaterThanOrEqualTo(500L).isLessThan(1500L);
            }
        }
    }

    @Nested
    @DisplayName("Retry-After 放弃阈值（决策 15）")
    class RetryAfterAbandonTests {

        @Test
        @DisplayName("Retry-After ≤ 60s 的 RateLimitedException 可重试")
        void retryAfterWithinThresholdIsRetryable() {
            assertThat(policy.isRetryable(new RateLimitedException("429", 60_000L))).isTrue();
        }

        @Test
        @DisplayName("Retry-After > 60s 不可重试（直接降级），普通 LLM_RATE_LIMITED 仍可重试")
        void retryAfterBeyondThresholdNotRetryable() {
            assertThat(policy.isRetryable(new RateLimitedException("429", 60_001L))).isFalse();
            assertThat(policy.isRetryable(new RateLimitedException("429", 3_600_000L))).isFalse();
            assertThat(policy.isRetryable(new RemoteException(RemoteErrorCode.LLM_RATE_LIMITED, "429"))).isTrue();
        }

        @Test
        @DisplayName("Retry-After > 60s 时 executeWithBackoff 不重试直接抛出")
        void executeWithBackoffAbandonsLargeRetryAfter() {
            AtomicInteger calls = new AtomicInteger(0);
            assertThatThrownBy(() -> policy.executeWithBackoff(() -> {
                calls.incrementAndGet();
                throw new RateLimitedException("429", 3_600_000L);
            })).isInstanceOf(RateLimitedException.class);
            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("流式路径 Retry-After > 60s 不重试（退避同源）")
        void retryStreamAbandonsLargeRetryAfter() {
            AtomicInteger subscribes = new AtomicInteger(0);
            var flux = policy.retryStream(() -> {
                subscribes.incrementAndGet();
                return reactor.core.publisher.Flux.<String>error(new RateLimitedException("429", 3_600_000L));
            });

            assertThatThrownBy(() -> flux.collectList().block())
                .isInstanceOf(RateLimitedException.class);
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
