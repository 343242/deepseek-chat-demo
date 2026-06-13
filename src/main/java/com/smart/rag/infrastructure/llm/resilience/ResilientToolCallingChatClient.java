package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.ToolCallingCapable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * ResilientToolCallingChatClient — 条件暴露 ToolCallingCapable 的薄装饰器
 * <p>
 * 仅当底层 delegate 支持 {@link ToolCallingCapable} 时，由
 * {@link com.smart.rag.infrastructure.llm.strategy.ChatCapabilityStrategy}
 * 包装 {@link ResilientChatClient} 生成此装饰器。
 * <p>
 * Design note: This class intentionally uses delegation (not inheritance) to
 * avoid constructing a second circuit-breaker / retry-policy wrapping the same
 * inner delegate. All {@link ChatCapable} and {@link ToolCallingCapable} methods
 * delegate to the already-resilient {@link ResilientChatClient}.
 */
public class ResilientToolCallingChatClient implements ChatCapable, ToolCallingCapable {

    private final ResilientChatClient delegate;

    public ResilientToolCallingChatClient(ResilientChatClient delegate) {
        this.delegate = delegate;
    }

    // ======== ChatCapable — 委托给已含弹性保护的 ResilientChatClient ========

    @Override
    public LlmResponse chat(ChatRequest request) {
        return delegate.chat(request);
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        return delegate.chatStream(request);
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }

    // ======== ToolCallingCapable ========

    @Override
    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        return delegate.chatWithTools(request, tools);
    }

    // ======== CapabilityClient — 透传 ========

    @Override public String candidateId() { return delegate.candidateId(); }
    @Override public String providerId()  { return delegate.providerId(); }
    @Override public String modelName()   { return delegate.modelName(); }
    @Override public LlmCapability capability() { return delegate.capability(); }
    @Override public boolean isAvailable() { return delegate.isAvailable(); }
    @Override public void close() { delegate.close(); }
}
