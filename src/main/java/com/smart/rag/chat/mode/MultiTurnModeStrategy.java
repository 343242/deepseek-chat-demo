package com.smart.rag.chat.mode;

import com.smart.rag.chat.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ModeChainResult;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MULTI_TURN 模式策略 -- 多轮对话，自动维护记忆和上下文
 */
@Component
public class MultiTurnModeStrategy implements ChatModeStrategy {

    private final AdvisorInfrastructure infra;

    public MultiTurnModeStrategy(AdvisorInfrastructure infra) {
        this.infra = infra;
    }

    @Override
    public ChatMode getMode() {
        return ChatMode.MULTI_TURN;
    }

    @Override
    public ModeChainResult buildAdvisorChain(AdvisorChainContext ctx) {
        List<org.springframework.ai.chat.client.advisor.api.Advisor> chain = new ArrayList<>();

        // MULTI_TURN: 上下文注入 + 记忆
        chain.add(new ConversationContextAdvisor(ctx.conversationId()));
        chain.addAll(infra.getGlobalAdvisors());

        if (ctx.request().isRagEnabled()) {
            chain.add(infra.getRagAdvisorFactory()
                .create(ctx.userId(), ctx.request().teamId()));
        }

        if (infra.hasTools()) {
            chain.add(infra.getToolCallAdvisor());
        }

        chain.add(MessageChatMemoryAdvisor.builder(infra.getChatMemory()).build());

        return ModeChainResult.standard(chain);
    }

    @Override
    @Deprecated
    public boolean isMemoryEnabled() { return true; }
}
