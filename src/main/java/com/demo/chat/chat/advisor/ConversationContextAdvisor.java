package com.demo.chat.chat.advisor;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 会话上下文注入 Advisor
 * <p>
 * 在 Advisor 链最前面（order=-1）运行，将 conversationId 写入共享 context，
 * 确保后续的 RateLimitAdvisor 等能从 context 中获取 conversationId。
 * <p>
 * 每次请求创建新实例（非单例 Bean），因为 conversationId 是请求级别的。
 */
public class ConversationContextAdvisor implements BaseAdvisor {

    private final String conversationId;

    public ConversationContextAdvisor(String conversationId) {
        this.conversationId = conversationId;
    }

    @Override
    @NonNull
    public String getName() {
        return "ConversationContextAdvisor";
    }

    @Override
    public int getOrder() {
        return -1; // 最先执行
    }

    @Override
    @NonNull
    public ChatClientRequest before(ChatClientRequest request, @NonNull AdvisorChain chain) {
        // 通过 mutate + builder 将 conversationId 注入 context
        return request.mutate()
                .context(ChatMemory.CONVERSATION_ID, conversationId)
                .build();
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }
}
