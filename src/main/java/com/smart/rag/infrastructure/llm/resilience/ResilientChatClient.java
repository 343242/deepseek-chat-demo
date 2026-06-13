package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.ToolCallingCapable;
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
        super(delegate, circuitBreaker, retryPolicy, metrics);
        this.probeHandler = probeHandler;
    }

    /** 底层 delegate 是否支持工具调用（Registry 层用于过滤） */
    public boolean delegateSupportsToolCalling() {
        return delegate instanceof ToolCallingCapable;
    }

    // ======== Chat 操作（带弹性包装） ========

    @Override
    public LlmResponse chat(ChatRequest request) {
        long start = metrics != null ? metrics.startNanos() : 0;
        try {
            LlmResponse response = circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    delegate.chat(request)
                )
            );
            if (metrics != null) {
                metrics.recordChatLatency(candidateId(), start, "success");
                if (response.tokenUsage() != null) {
                    metrics.recordTokens(candidateId(), "prompt", response.tokenUsage().promptTokens());
                    metrics.recordTokens(candidateId(), "completion", response.tokenUsage().completionTokens());
                }
            }
            return response;
        } catch (Exception e) {
            if (metrics != null) metrics.recordChatLatency(candidateId(), start, "error");
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        long start = metrics != null ? metrics.startNanos() : 0;
        return circuitBreaker.executeStream(() ->
            retryPolicy.retryStream(() -> {
                Flux<String> raw = delegate.chatStream(request);
                return probeHandler != null
                    ? probeHandler.wrap(candidateId(), raw, circuitBreaker::recordProbeSuccess)
                    : raw;
            })
        ).doOnComplete(() -> {
            if (metrics != null) metrics.recordChatLatency(candidateId(), start, "success");
        }).doOnError(e -> {
            if (metrics != null) metrics.recordChatLatency(candidateId(), start, "error");
        });
    }

    // ======== Tool Calling 操作（委托给底层 delegate） ========

    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        if (!(delegate instanceof ToolCallingCapable tc)) {
            throw new UnsupportedOperationException(
                "Delegate '" + candidateId() + "' does not support tool calling");
        }
        long start = metrics != null ? metrics.startNanos() : 0;
        try {
            LlmResponse response = circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    tc.chatWithTools(request, tools)
                )
            );
            if (metrics != null) {
                metrics.recordChatLatency(candidateId(), start, "success");
                if (response.tokenUsage() != null) {
                    metrics.recordTokens(candidateId(), "prompt", response.tokenUsage().promptTokens());
                    metrics.recordTokens(candidateId(), "completion", response.tokenUsage().completionTokens());
                }
            }
            return response;
        } catch (Exception e) {
            if (metrics != null) metrics.recordChatLatency(candidateId(), start, "error");
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }
}
