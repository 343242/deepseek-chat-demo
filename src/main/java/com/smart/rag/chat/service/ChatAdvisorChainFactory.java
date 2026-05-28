package com.smart.rag.chat.service;

import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.provider.ModelRouter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Advisor 链构建工厂（门面）
 * <p>
 * 委托给策略模式组装 Advisor 链，返回 ModeChainResult。
 * hasTools() / getToolCallbacks() 委托给 AdvisorInfrastructure。
 */
@Component
public class ChatAdvisorChainFactory {

    private final AdvisorInfrastructure infra;

    public ChatAdvisorChainFactory(AdvisorInfrastructure infra) {
        this.infra = infra;
    }

    // ==================== 委托 AdvisorInfrastructure ====================

    public boolean hasTools() {
        return infra.hasTools();
    }

    public ToolCallback[] getToolCallbacks() {
        return infra.getToolCallbacks();
    }

    // ==================== 统一入口（委托给策略） ====================

    /**
     * 构建 Advisor 链 -- 委托给策略，返回 ModeChainResult
     *
     * @param conversationId 对话 ID
     * @param request        聊天请求
     * @param modeStrategy   对话模式策略
     * @param cagContext     CAG 请求上下文（可 null）
     * @param userId         用户 ID
     * @param route          模型路由结果
     * @return ModeChainResult（含 chain + 执行指示）
     */
    public ModeChainResult buildChain(String conversationId,
                                      ChatRequest request,
                                      ChatModeStrategy modeStrategy,
                                      RequestContext cagContext,
                                      Long userId,
                                      ModelRouter.Route route) {
        AdvisorChainContext ctx = new AdvisorChainContext(
            conversationId, request, userId, cagContext, route);
        return modeStrategy.buildAdvisorChain(ctx);
    }
}
