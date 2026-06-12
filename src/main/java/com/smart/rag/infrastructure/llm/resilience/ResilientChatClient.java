package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.ToolCallingCapable;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * ResilientChatClient — Chat 能力的弹性装饰器（实现 ChatCapable + ToolCallingCapable）
 * <p>
 * 工具调用已是主流大模型标配能力，chatWithTools() 与 chat() 共享同一弹性保护路径。
 * 当底层 delegate 不支持 {@code ToolCallingCapable} 时，{@code chatWithTools()} 抛出
 * {@code UnsupportedOperationException}——这是能力缺失的正确语义。
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
        implements ChatCapable, ToolCallingCapable {

    @Nullable
    private final ProbeHandler probeHandler;

    public ResilientChatClient(ChatCapable delegate,
                                CircuitBreaker circuitBreaker,
                                RetryPolicy retryPolicy,
                                @Nullable ProbeHandler probeHandler) {
        super(delegate, circuitBreaker, retryPolicy);
        this.probeHandler = probeHandler;
    }

    /** 底层 delegate 是否支持工具调用（Registry 层用于过滤） */
    public boolean delegateSupportsToolCalling() {
        return delegate instanceof ToolCallingCapable;
    }

    // ======== Chat 操作（带弹性包装） ========

    @Override
    public LlmResponse chat(ChatRequest request) {
        try {
            return circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    delegate.chat(request)
                )
            );
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        return circuitBreaker.executeStream(() ->
            retryPolicy.retryStream(() -> {
                Flux<String> raw = delegate.chatStream(request);
                return probeHandler != null
                    ? probeHandler.wrap(candidateId(), raw, circuitBreaker::recordProbeSuccess)
                    : raw;
            })
        );
    }

    // ======== Tool Calling 操作（统一弹性路径） ========

    @Override
    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        if (!(delegate instanceof ToolCallingCapable tc)) {
            throw new UnsupportedOperationException(
                "Delegate '" + candidateId() + "' does not support tool calling");
        }
        try {
            return circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    tc.chatWithTools(request, tools)
                )
            );
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }
}
