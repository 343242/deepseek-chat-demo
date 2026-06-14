package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.exception.ModelNotFoundException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 查询改写专用 ChatClient 解析器。
 * <p>
 * 把 {@code LlmClientRegistry} 的候选 {@code ChatCapable} 通过 {@link ChatModelAdapter}
 * 适配为 Spring AI 的 {@link ChatClient}，集中处理 fail-fast 异常包装。
 * <p>
 * 设计原因：消除 chat / rag / agent 三层对 Spring AI {@code ChatClientAutoConfiguration}
 * 的隐式依赖（构造函数注入 {@code ChatClient.Builder} 需要容器存在 {@code ChatModel} bean，
 * 而基础设施层不暴露该 bean）。
 */
@Component
public class RewriteClientResolver {

    private final LlmClientRegistry registry;

    public RewriteClientResolver(LlmClientRegistry registry) {
        this.registry = registry;
    }

    /**
     * 按候选 ID 解析 ChatClient。
     *
     * @param candidateId 候选 ID；为 null/blank 时走 {@link #resolveDefault()}
     * @return 已构建的 ChatClient
     * @throws RemoteException 当 candidateId 非空但 registry 中不存在该候选时，
     *         原 {@code RemoteException} 直接向上抛（fail-fast，不二次包装）
     */
    public ChatClient resolve(@Nullable String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            return resolveDefault();
        }
        ChatCapable chatCapable = registry.get(candidateId, ChatCapable.class);
        return ChatClient.builder(new ChatModelAdapter(chatCapable)).build();
    }

    /**
     * 解析默认 chat 候选的 ChatClient。
     *
     * @return 已构建的 ChatClient
     * @throws ModelNotFoundException 当 registry 中没有任何 CHAT 能力候选时
     */
    public ChatClient resolveDefault() {
        ChatCapable chatCapable;
        try {
            chatCapable = registry.getDefault(LlmCapability.CHAT, ChatCapable.class);
        } catch (RemoteException e) {
            throw new ModelNotFoundException("default-chat",
                "未配置任何 CHAT 能力候选，无法初始化 RewriteClient/QueryRewriteTool");
        }
        return ChatClient.builder(new ChatModelAdapter(chatCapable)).build();
    }
}
