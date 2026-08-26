package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.StreamChunk;
import com.smart.rag.infrastructure.llm.config.RetryConfig;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WS7 观测补齐打点接线测试：llm.retry.attempts、llm.chat.ttft、
 * llm.chat.tokens{operation=cache_hit}（含流式轮末包）。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("WS7 观测打点接线")
class LlmObservabilityMetricsTest {

    @Mock private LlmMetrics metrics;
    @Mock private CircuitBreaker circuitBreaker;
    @Mock private ChatCapable delegate;

    // ==================== llm.retry.attempts ====================

    @Test
    @DisplayName("阻塞路径：可重试失败 → 每次重试决策打 retry，耗尽打 exhausted")
    void blockingRetryAttemptsRecorded() throws Exception {
        RetryPolicy policy = new RetryPolicy(new RetryConfig(2, 1L, 1L, 1.0), "c1", metrics);
        AtomicInteger calls = new AtomicInteger();
        try {
            policy.executeWithBackoff(() -> {
                calls.incrementAndGet();
                throw new IOException("transient");
            });
        } catch (Exception ignored) { }

        verify(metrics, times(1)).recordRetryAttempt("c1", "retry");
        verify(metrics, times(1)).recordRetryAttempt("c1", "exhausted");
    }

    @Test
    @DisplayName("旧构造器（无 metrics）：不打点、行为不变")
    void legacyConstructorNoMetrics() throws Exception {
        RetryPolicy policy = new RetryPolicy(new RetryConfig(2, 1L, 1L, 1.0));
        AtomicInteger calls = new AtomicInteger();
        try {
            policy.executeWithBackoff(() -> {
                calls.incrementAndGet();
                throw new IOException("transient");
            });
        } catch (Exception ignored) { }
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("流式路径：重试决策同源打点")
    void streamRetryAttemptsRecorded() {
        RetryPolicy policy = new RetryPolicy(new RetryConfig(3, 1L, 1L, 1.0), "c1", metrics);
        AtomicInteger subs = new AtomicInteger();
        policy.<String>retryStream(() -> {
            if (subs.incrementAndGet() < 3) {
                return Flux.error(new IOException("transient"));
            }
            return Flux.just("ok");
        }).collectList().block(java.time.Duration.ofSeconds(5));

        verify(metrics, times(2)).recordRetryAttempt("c1", "retry");
    }

    // ==================== llm.chat.ttft + 流式 cache_hit ====================

    private void mockDelegate() throws Exception {
        when(delegate.candidateId()).thenReturn("c1");
        when(delegate.providerId()).thenReturn("p");
        when(delegate.modelName()).thenReturn("m");
        when(delegate.capability()).thenReturn(LlmCapability.CHAT);
        when(delegate.supportsStreaming()).thenReturn(true);
        // circuitBreaker 直通
        when(circuitBreaker.executeStream(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked") reactor.core.publisher.Flux<StreamChunk> f =
                ((java.util.function.Supplier<reactor.core.publisher.Flux<StreamChunk>>) inv.getArgument(0)).get();
            return f;
        });
        when(circuitBreaker.execute(any())).thenAnswer(inv ->
            ((com.smart.rag.infrastructure.llm.resilience.RetryPolicy.CheckedSupplier<Object>) inv.getArgument(0)).get());
    }

    @Test
    @DisplayName("chatStream：首包打 TTFT（一次性），轮末汇总包打 cache_hit")
    void ttftAndStreamCacheHitRecorded() throws Exception {
        mockDelegate();
        LlmResponse.TokenUsage usage = new LlmResponse.TokenUsage(100, 50, 150, 80);
        when(delegate.chatStream(any())).thenReturn(Flux.just(
            new StreamChunk("hello", null, null, null),
            new StreamChunk(null, null, StreamChunk.FinishReason.STOP, usage)));

        ResilientChatClient client = new ResilientChatClient(delegate, circuitBreaker,
            new RetryPolicy(new RetryConfig(1, 1L, 1L, 1.0)), null, metrics, AdmissionControl.DISABLED);

        client.chatStream(ChatRequest.of("hi")).collectList().block(java.time.Duration.ofSeconds(5));

        verify(metrics, times(1)).recordTtft(eq("c1"), anyLong());
        verify(metrics, times(1)).recordTokens(eq("c1"), eq("cache_hit"), eq(80));
    }

    @Test
    @DisplayName("chat 阻塞路径：cacheHitTokens > 0 打 cache_hit；无缓存不打")
    void blockingCacheHitRecorded() throws Exception {
        mockDelegate();
        when(delegate.chat(any())).thenReturn(
            new LlmResponse("a", false, new LlmResponse.TokenUsage(100, 50, 150, 60), java.util.List.of(), java.util.Map.of(), ""));

        ResilientChatClient client = new ResilientChatClient(delegate, circuitBreaker,
            new RetryPolicy(new RetryConfig(1, 1L, 1L, 1.0)), null, metrics, AdmissionControl.DISABLED);
        client.chat(ChatRequest.of("hi"));

        verify(metrics, times(1)).recordTokens("c1", "cache_hit", 60);
        verify(metrics, times(1)).recordTokens("c1", "prompt", 100);
    }
}
