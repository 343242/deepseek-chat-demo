package com.smart.rag.chat.mode;

import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModeChainResult;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SIMPLE 模式策略 -- 单轮对话，无记忆、无上下文
 */
@Component
public class SimpleModeStrategy extends AbstractModeStrategy {

    public SimpleModeStrategy(AdvisorInfrastructure infra,
                              ChatRequestSpecFactory requestSpecFactory,
                              ChatUsageTracker usageTracker) {
        super(infra, requestSpecFactory, usageTracker);
    }

    @Override
    public ChatMode getMode() {
        return ChatMode.SIMPLE;
    }

    @Override
    public ModeChainResult buildAdvisorChain(AdvisorChainContext ctx) {
        List<Advisor> chain = new ArrayList<>(infra.getGlobalAdvisors());

        if (ctx.request().isRagEnabled()) {
            chain.add(infra.getRagAdvisorFactory()
                .create(ctx.userId(), ctx.request().teamId()));
        }

        if (infra.hasTools()) {
            chain.add(infra.getToolCallAdvisor());
        }

        return ModeChainResult.standard(chain);
    }
}
