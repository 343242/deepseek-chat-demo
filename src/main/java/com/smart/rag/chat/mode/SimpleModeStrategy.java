package com.smart.rag.chat.mode;

import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.mode.ChatMode;
import com.smart.rag.mode.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.chat.service.ChatReferenceCollector;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatRetrievalService;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModeChainResult;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SIMPLE 模式策略 -- 单轮对话，无记忆、无上下文。
 * 阻塞式/流式执行 + 落库由 {@link AbstractModeStrategy} 统一实现（R8：SIMPLE 流式也落库）。
 */
@Component
public class SimpleModeStrategy extends AbstractModeStrategy {

    public SimpleModeStrategy(AdvisorInfrastructure infra,
                              ChatRequestSpecFactory requestSpecFactory,
                              ChatUsageTracker usageTracker,
                              ChatRetrievalService chatRetrievalService,
                              ChatReferenceCollector chatReferenceCollector,
                              ContextPromptInjector contextPromptInjector,
                              ChatMessagePublisher chatMessagePublisher,
                              ChatConversationHelper conversationHelper) {
        super(infra, requestSpecFactory, usageTracker,
            chatRetrievalService, chatReferenceCollector, contextPromptInjector,
            chatMessagePublisher, conversationHelper);
    }

    @Override
    public ChatMode getMode() {
        return ChatMode.SIMPLE;
    }

    @Override
    public ModeChainResult buildAdvisorChain(AdvisorChainContext ctx) {
        List<Advisor> chain = new ArrayList<>(infra.getGlobalAdvisors());

        // RAG 检索由 AbstractModeStrategy 经 ChatRetrievalService + RagContextAdvisor 处理（方案 A）

        if (infra.hasTools()) {
            chain.add(infra.getToolCallAdvisor());
        }

        return ModeChainResult.standard(chain);
    }
}
