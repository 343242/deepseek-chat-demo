package com.smart.rag.chat.service;

import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.mode.ChatModeStrategy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Advisor 链构建工厂（门面）
 */
@Component
public class ChatAdvisorChainFactory {

    private final AdvisorInfrastructure infra;

    public ChatAdvisorChainFactory(AdvisorInfrastructure infra) {
        this.infra = infra;
    }

    public boolean hasTools() {
        return infra.hasTools();
    }

    public ToolCallback[] getToolCallbacks() {
        return infra.getToolCallbacks();
    }

    /**
     * 构建 Advisor 链 -- 委托给策略，返回 ModeChainResult
     */
    public ModeChainResult buildChain(String conversationId,
                                      ChatRequest request,
                                      ChatModeStrategy modeStrategy,
                                      RequestContext cagContext,
                                      Long userId,
                                      String candidateId) {
        AdvisorChainContext ctx = new AdvisorChainContext(
            conversationId, request, userId, cagContext, candidateId);
        return modeStrategy.buildAdvisorChain(ctx);
    }
}
