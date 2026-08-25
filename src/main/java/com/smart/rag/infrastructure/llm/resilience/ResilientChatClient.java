package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.ToolCallingCapable;
import com.smart.rag.infrastructure.llm.StreamChunk;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * ResilientChatClient — Chat 能力的弹性装饰器（实现 ChatCapable）
 * <p>
 * 当底层 delegate 支持 {@link ToolCallingCapable} 时，chatWithTools() 可正常调用；
 * 否则抛出 {@code UnsupportedOperationException}。
 * <p>
 * 工具调用条件暴露：由 {@link ResilientToolCallingChatClient} 装饰器在 delegate 支持
 * {@code ToolCallingCapable} 时额外暴露 {@code ToolCallingCapable} 接口。
 * <p>
 * 策略矩阵：
 * <pre>
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ chat         │ 指数退避重试   │ ✓            │ ✗（阻塞式）   │
 * │ chatStream   │ 指数退避重试   │ ✓            │ ✓ ProbeHandler│
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 * </pre>
 */
public class ResilientChatClient extends AbstractResilientClient<ChatCapable>
        implements ChatCapable {

    @Nullable
    private final ProbeHandler probeHandler;

    public ResilientChatClient(ChatCapable delegate,
                                CircuitBreaker circuitBreaker,
                                RetryPolicy retryPolicy,
                                @Nullable ProbeHandler probeHandler,
                                @Nullable LlmMetrics metrics) {
        this(delegate, circuitBreaker, retryPolicy, probeHandler, metrics, null);
    }

    public ResilientChatClient(ChatCapable delegate,
                                CircuitBreaker circuitBreaker,
                                RetryPolicy retryPolicy,
                                @Nullable ProbeHandler probeHandler,
                                @Nullable LlmMetrics metrics,
                                @Nullable AdmissionControl admissionControl) {
        super(delegate, circuitBreaker, retryPolicy, metrics, admissionControl);
        this.probeHandler = probeHandler;
    }

    /** 底层 delegate 是否支持工具调用（Registry 层用于过滤） */
    public boolean delegateSupportsToolCalling() {
        return delegate instanceof ToolCallingCapable;
    }

    // ======== Chat 操作（带弹性包装） ========

    @Override
    public LlmResponse chat(ChatRequest request) {
        LlmResponse response = executeResilient(
            () -> delegate.chat(request),
            (cid, start) -> metrics.recordChatLatency(cid, start, "success"),
            (cid, start) -> metrics.recordChatLatency(cid, start, "error")
        );
        recordTokensIfPresent(response);
        return response;
    }

    /**
     * Streaming chat with resilience.
     * <p>
     * Note: Circuit breaker state is checked when the returned Flux is
     * <b>subscribed</b> (deferred evaluation), not when this method is called.
     * This is correct reactive behavior — the check happens lazily as part of
     * {@code circuitBreaker.executeStream()} — but may be surprising if callers
     * expect an eager guard. Callers needing an upfront check should invoke
     * {@link #isAvailable()} before subscribing.
     */
    @Override
    public Flux<StreamChunk> chatStream(ChatRequest request) {
        long start = metrics != null ? metrics.startNanos() : 0;
        // P0a 占位：SPI 已改 Flux<StreamChunk>，但 ProbeHandler/retryStream 仍是 Flux<String>（P2 泛型化 ProbeHandler）。
        // 内部 .map(StreamChunk::text) 回 String 走弹性层，末端再 .map 回 StreamChunk。占位阶段无 tool delta，语义无损。
        Flux<StreamChunk> body = circuitBreaker.executeStream(() ->
            retryPolicy.retryStream(() -> {
                // P2：ProbeHandler/retryStream/executeStream 已泛型化，透传 Flux<StreamChunk>。
                // text chunk + 轮末汇总包(toolCalls/finishReason/usage) 直达 ChatModelAdapter（P3 回灌）。
                Flux<StreamChunk> raw = delegate.chatStream(request);
                return probeHandler != null
                    ? probeHandler.wrap(candidateId(), raw, circuitBreaker::recordProbeSuccess)
                    : raw;
            })
        ).doOnComplete(() -> {
            if (metrics != null) metrics.recordChatLatency(candidateId(), start, "success");
        }).doOnError(e -> {
            if (metrics != null) metrics.recordChatLatency(candidateId(), start, "error");
        });
        // 闸门 acquire 先于 probe 订阅（决策 6：排队不消耗 3s 探测预算）；CANCEL 经 doFinally 释放
        return admissionControl != null ? admissionControl.gateStream(() -> body) : body;
    }

    // ======== Tool Calling 操作（委托给底层 delegate） ========

    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        if (!(delegate instanceof ToolCallingCapable tc)) {
            throw new UnsupportedOperationException(
                "Delegate '" + candidateId() + "' does not support tool calling");
        }
        LlmResponse response = executeResilient(
            () -> tc.chatWithTools(request, tools),
            (cid, start) -> metrics.recordChatLatency(cid, start, "success"),
            (cid, start) -> metrics.recordChatLatency(cid, start, "error")
        );
        recordTokensIfPresent(response);
        return response;
    }

    private void recordTokensIfPresent(LlmResponse response) {
        if (metrics == null || response.tokenUsage() == null) return;
        metrics.recordTokens(candidateId(), "prompt", response.tokenUsage().promptTokens());
        metrics.recordTokens(candidateId(), "completion", response.tokenUsage().completionTokens());
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }
}
