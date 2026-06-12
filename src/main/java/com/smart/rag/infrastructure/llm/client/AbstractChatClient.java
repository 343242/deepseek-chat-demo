package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.llm.*;
import reactor.core.publisher.Flux;

/**
 * Chat 客户端抽象基类
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientChatClient} 装饰器在外部施加。
 * <p>
 * <b>不包含 Spring AI ChatModel 桥接代码</b>——桥接逻辑集中在独立的 {@code ChatModelAdapter} 中。
 * <p>
 * <b>工具调用</b>：需要支持工具调用的子类额外实现 {@link ToolCallingCapable}。
 */
public abstract class AbstractChatClient implements ChatCapable {

    protected final ModelCandidate candidate;
    protected final String providerId;

    protected AbstractChatClient(ModelCandidate candidate, String providerId) {
        this.candidate = candidate;
        this.providerId = providerId;
    }

    @Override
    public final String candidateId() { return candidate.id(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelName() { return candidate.model(); }

    @Override
    public final LlmCapability capability() { return candidate.capability(); }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public boolean supportsStreaming() {
        return candidate.supportsStreaming();
    }

    @Override
    public abstract LlmResponse chat(ChatRequest request);

    @Override
    public abstract Flux<String> chatStream(ChatRequest request);
}
