package com.smart.rag.infrastructure.llm;

import reactor.core.publisher.Flux;

/**
 * Chat 能力契约
 * <p>
 * 定义 Chat 场景的核心操作。AbstractChatClient 和 ResilientChatClient 均实现此接口。
 * 调用方通过此接口与 Chat 客户端交互，无需关心是否经过弹性包装。
 * <p>
 * <b>不继承 Spring AI ChatModel</b>：ChatCapable 保持纯净的能力契约，
 * 不引入 Spring AI 的 Prompt/ChatResponse 类型依赖（ISP）。
 * 需要 ChatModel 的场景通过 {@code new ChatModelAdapter(capable)} 获取适配器视图。
 */
public interface ChatCapable extends CapabilityClient {

    /** 阻塞式对话 */
    LlmResponse chat(ChatRequest request);

    /** 流式对话（SSE） */
    Flux<String> chatStream(ChatRequest request);

    /** 是否支持流式（由 ModelCandidate.supportsStreaming 声明） */
    boolean supportsStreaming();
}
