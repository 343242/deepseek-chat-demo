package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FallbackExecutor}.
 * <p>
 * Verifies the chain traversal semantics: empty-chain rejection, non-eligible
 * short-circuit, first-success shortcut, fallback-through, and exhaustion.
 * Both blocking and streaming paths are covered.
 */
@ExtendWith(MockitoExtension.class)
class FallbackExecutorTest {

    @Mock
    private FallbackEligibility eligibility;

    @Mock
    private LlmMetrics metrics;

    private List<FallbackEvent> capturedEvents;
    private FallbackExecutor executor;

    @BeforeEach
    void setUp() {
        capturedEvents = new ArrayList<>();
        executor = new FallbackExecutor(eligibility, capturedEvents::add, metrics);
    }

    // ==================== execute (blocking) ====================

    @Nested
    @DisplayName("execute(chain, action) — blocking")
    class ExecuteBlockingTests {

        @Test
        @DisplayName("empty chain (all unavailable) throws LLM_CONFIG_ERROR")
        void emptyChainThrowsConfigError() {
            List<TestClient> chain = List.of(unavailableClient("c1"));

            assertThatThrownBy(() -> executor.execute(chain, c -> {
                throw new AssertionError("should not be called");
            })).isInstanceOf(RemoteException.class)
                .hasMessageContaining("Fallback chain is empty");

            assertThat(capturedEvents).isEmpty();
        }

        @Test
        @DisplayName("first client success returns value, action called once")
        void firstClientSuccess() throws Exception {
            TestClient c1 = availableClient("c1");
            TestClient c2 = availableClient("c2");
            List<TestClient> chain = List.of(c1, c2);

            String result = executor.execute(chain, c -> "result-from-" + c.candidateId());

            assertThat(result).isEqualTo("result-from-c1");
            assertThat(capturedEvents).isEmpty();
            verify(metrics, never()).recordFallback(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("non-eligible exception short-circuits — no fallback, no metrics")
        void nonEligibleExceptionShortCircuits() {
            TestClient c1 = availableClient("c1");
            TestClient c2 = availableClient("c2");
            List<TestClient> chain = List.of(c1, c2);

            IllegalArgumentException userError = new IllegalArgumentException("bad input");
            when(eligibility.isEligible(userError)).thenReturn(false);

            assertThatThrownBy(() -> executor.execute(chain, c -> {
                if (c.candidateId().equals("c1")) throw userError;
                return "ok";
            })).isSameAs(userError);

            assertThat(capturedEvents).isEmpty();
            verify(metrics, never()).recordFallback(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("eligible exception triggers fallback to next client — event + metrics published")
        void eligibleExceptionTriggersFallback() throws Exception {
            TestClient c1 = availableClient("c1");
            TestClient c2 = availableClient("c2");
            List<TestClient> chain = List.of(c1, c2);

            IOException transientError = new java.io.IOException("timeout");
            when(eligibility.isEligible(transientError)).thenReturn(true);

            String result = executor.execute(chain, c -> {
                if (c.candidateId().equals("c1")) throw transientError;
                return "result-from-" + c.candidateId();
            });

            assertThat(result).isEqualTo("result-from-c2");
            assertThat(capturedEvents).hasSize(1);
            FallbackEvent event = capturedEvents.get(0);
            assertThat(event.capability()).isEqualTo(LlmCapability.CHAT);
            assertThat(event.fromCandidateId()).isEqualTo("c1");
            assertThat(event.toCandidateId()).isEqualTo("c2");
            assertThat(event.cause()).isSameAs(transientError);

            verify(metrics, times(1)).recordFallback(
                eq(LlmCapability.CHAT), eq("c1"), eq("c2"));
        }

        @Test
        @DisplayName("all clients fail → throws LLM_ALL_MODELS_FAILED with last cause")
        void allClientsFailThrowsAllModelsFailed() {
            TestClient c1 = availableClient("c1");
            TestClient c2 = availableClient("c2");
            TestClient c3 = availableClient("c3");
            List<TestClient> chain = List.of(c1, c2, c3);

            RemoteException last = new RemoteException(RemoteErrorCode.LLM_RATE_LIMITED, "rate");
            when(eligibility.isEligible(any())).thenReturn(true);

            assertThatThrownBy(() -> executor.execute(chain, c -> {
                if (c.candidateId().equals("c3")) throw last;
                throw new java.io.IOException("transient");
            })).isInstanceOf(RemoteException.class)
                .hasMessageContaining("所有模型均不可用")
                .hasCause(last);

            // 2 fallback events (c1→c2, c2→c3), not c3→nothing
            assertThat(capturedEvents).hasSize(2);
            verify(metrics, times(2)).recordFallback(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("unavailable clients are filtered out before traversal")
        void unavailableClientsSkipped() throws Exception {
            TestClient unavailable = unavailableClient("c0");
            TestClient c1 = availableClient("c1");
            List<TestClient> chain = List.of(unavailable, c1);

            String result = executor.execute(chain, c -> "ok-" + c.candidateId());

            assertThat(result).isEqualTo("ok-c1");
        }
    }

    // ==================== executeStream ====================

    @Nested
    @DisplayName("executeStream(chain, action) — reactive")
    class ExecuteStreamTests {

        @Test
        @DisplayName("empty chain emits LLM_ALL_MODELS_FAILED error")
        void emptyChainEmitsError() {
            List<TestClient> chain = List.of(unavailableClient("c1"));

            Throwable error = blockExpectError(executor.executeStream(chain, c -> Flux.just("x")));

            assertThat(error).isInstanceOf(RemoteException.class);
            assertThat(((RemoteException) error).getErrorCode())
                .isEqualTo(RemoteErrorCode.LLM_ALL_MODELS_FAILED);
        }

        @Test
        @DisplayName("first client emits all items, no fallback")
        void firstClientEmitsAllItems() {
            TestClient c1 = availableClient("c1");
            TestClient c2 = availableClient("c2");
            List<TestClient> chain = List.of(c1, c2);

            List<String> items = executor.executeStream(chain,
                    c -> Flux.just("a", "b", "c"))
                .collectList().block();

            assertThat(items).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("eligible error on first client falls back to next")
        void eligibleErrorFallsBack() {
            TestClient c1 = availableClient("c1");
            TestClient c2 = availableClient("c2");
            List<TestClient> chain = List.of(c1, c2);

            when(eligibility.isEligible(any())).thenReturn(true);

            List<String> items = executor.executeStream(chain, c -> {
                    if (c.candidateId().equals("c1")) return Flux.error(new java.io.IOException("transient"));
                    return Flux.just("from-c2");
                })
                .collectList().block();

            assertThat(items).containsExactly("from-c2");

            // Verify metrics + event for c1→c2 fallback
            verify(metrics, times(1)).recordFallback(eq(LlmCapability.CHAT), eq("c1"), eq("c2"));
            assertThat(capturedEvents).hasSize(1);
        }

        @Test
        @DisplayName("non-eligible error propagates without fallback")
        void nonEligibleErrorPropagates() {
            TestClient c1 = availableClient("c1");
            TestClient c2 = availableClient("c2");
            List<TestClient> chain = List.of(c1, c2);

            IllegalArgumentException userError = new IllegalArgumentException("bad");
            when(eligibility.isEligible(userError)).thenReturn(false);

            Throwable error = blockExpectError(executor.executeStream(chain, c -> {
                    if (c.candidateId().equals("c1")) return Flux.error(userError);
                    return Flux.just("x");
                }));

            // userError may be wrapped by reactor's blocking wrapper — unwrap & compare class
            Throwable cur = error;
            while (cur.getCause() != null && cur != cur.getCause()
                && cur.getClass().getName().startsWith("reactor.")) {
                cur = cur.getCause();
            }
            assertThat(cur).isInstanceOf(IllegalArgumentException.class);

            verify(metrics, never()).recordFallback(any(), anyString(), anyString());
            assertThat(capturedEvents).isEmpty();
        }

        @Test
        @DisplayName("all clients fail → LLM_ALL_MODELS_FAILED")
        void allClientsFailEmitsAllModelsFailed() {
            TestClient c1 = availableClient("c1");
            TestClient c2 = availableClient("c2");
            List<TestClient> chain = List.of(c1, c2);

            when(eligibility.isEligible(any())).thenReturn(true);

            Throwable error = blockExpectError(executor.executeStream(chain, c ->
                    Flux.error(new java.io.IOException("transient"))));

            assertThat(error).isInstanceOf(RemoteException.class);
            assertThat(((RemoteException) error).getErrorCode())
                .isEqualTo(RemoteErrorCode.LLM_ALL_MODELS_FAILED);
        }
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
            return t;
        }
    }

    // ==================== test doubles ====================

    /**
     * Minimal CapabilityClient stub for testing.
     * Tests control isAvailable() and candidateId() to construct chains.
     */
    private static TestClient availableClient(String id) {
        return new TestClient(id, true);
    }

    private static TestClient unavailableClient(String id) {
        return new TestClient(id, false);
    }

    private static final class TestClient implements CapabilityClient {
        private final String id;
        private final boolean available;

        TestClient(String id, boolean available) {
            this.id = id;
            this.available = available;
        }

        @Override public String candidateId() { return id; }
        @Override public String providerId() { return "test-provider"; }
        @Override public String modelName() { return "test-model"; }
        @Override public LlmCapability capability() { return LlmCapability.CHAT; }
        @Override public boolean isAvailable() { return available; }
    }
}
