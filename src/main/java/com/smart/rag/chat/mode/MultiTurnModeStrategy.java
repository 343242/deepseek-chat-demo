package com.smart.rag.chat.mode;

import com.smart.rag.infrastructure.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.chat.service.ChatReferenceCollector;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatRetrievalService;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModeChainResult;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MULTI_TURN 模式策略 -- 多轮对话，自动维护记忆和上下文。
 * <p>
 * 阻塞式/流式执行 + 落库 + onStreamComplete 由 {@link AbstractModeStrategy} 统一实现；
 * 本类只负责 buildAdvisorChain（含 MessageChatMemoryAdvisor → Redis 记忆 load/save）。
 */
@Component
public class MultiTurnModeStrategy extends AbstractModeStrategy {

    public MultiTurnModeStrategy(AdvisorInfrastructure infra,
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
        return ChatMode.MULTI_TURN;
    }

    @Override
    public ModeChainResult buildAdvisorChain(AdvisorChainContext ctx) {
        List<Advisor> chain = new ArrayList<>();

        chain.add(new ConversationContextAdvisor(ctx.conversationId()));
        chain.addAll(infra.getGlobalAdvisors());

        // RAG 检索由 AbstractModeStrategy 经 ChatRetrievalService + RagContextAdvisor 处理（方案 A）

        if (infra.hasTools()) {
            chain.add(infra.getToolCallAdvisor());
        }

        chain.add(MessageChatMemoryAdvisor.builder(infra.getChatMemory()).build());

        return ModeChainResult.standard(chain);
    }
}
