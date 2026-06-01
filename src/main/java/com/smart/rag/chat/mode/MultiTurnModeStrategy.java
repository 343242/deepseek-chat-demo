package com.smart.rag.chat.mode;

import com.smart.rag.infrastructure.ai.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MULTI_TURN 模式策略 -- 多轮对话，自动维护记忆和上下文
 */
@Component
public class MultiTurnModeStrategy implements ChatModeStrategy {

    private static final Logger log = LoggerFactory.getLogger(MultiTurnModeStrategy.class);

    private final AdvisorInfrastructure infra;
    private final ChatRequestSpecFactory requestSpecFactory;
    private final ChatUsageTracker usageTracker;
    private final ChatConversationHelper conversationHelper;

    public MultiTurnModeStrategy(AdvisorInfrastructure infra,
                                 ChatRequestSpecFactory requestSpecFactory,
                                 ChatUsageTracker usageTracker,
                                 ChatConversationHelper conversationHelper) {
        this.infra = infra;
        this.requestSpecFactory = requestSpecFactory;
        this.usageTracker = usageTracker;
        this.conversationHelper = conversationHelper;
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
    public StrategyExecuteResult execute(StrategyExecutionContext ctx) {
        AdvisorChainContext chainCtx = new AdvisorChainContext(
            ctx.conversationId(), ctx.request(), ctx.userId(),
            ctx.cagContext(), ctx.route());
        ModeChainResult result = buildAdvisorChain(chainCtx);

        ChatResponse springResponse = requestSpecFactory.createSpec(
            ctx.chatClient(), ctx.route(), ctx.request(),
            ctx.conversationId(), result.chain(), ctx.cagContext()
        ).call().chatResponse();

        String content = SimpleModeStrategy.extractContent(springResponse);
        return StrategyExecuteResult.standard(springResponse, content);
    }

    @Override
    public Flux<String> executeStream(StrategyExecutionContext ctx) {
        AdvisorChainContext chainCtx = new AdvisorChainContext(
            ctx.conversationId(), ctx.request(), ctx.userId(),
            ctx.cagContext(), ctx.route());
        ModeChainResult result = buildAdvisorChain(chainCtx);

        AtomicReference<ChatResponse> lastAiResponse = new AtomicReference<>();
        StringBuilder collectedContent = new StringBuilder();
        final int maxContentLength = 1 << 20;
        AtomicBoolean usageRecorded = new AtomicBoolean(false);

        return requestSpecFactory.createSpec(
            ctx.chatClient(), ctx.route(), ctx.request(),
            ctx.conversationId(), result.chain(), ctx.cagContext()
        )
        .stream()
        .chatResponse()
        .mapNotNull(aiResponse -> {
            lastAiResponse.set(aiResponse);
            Generation gen = aiResponse.getResult();
            if (gen == null || gen.getOutput() == null) {
                return null;
            }
            String text = gen.getOutput().getText();
            if (text != null && collectedContent.length() < maxContentLength) {
                int remaining = maxContentLength - collectedContent.length();
                collectedContent.append(text, 0, Math.min(text.length(), remaining));
            }
            return text;
        })
        .doFinally(signal -> {
            // 消息持久化 — ON_COMPLETE 完整保存，ON_ERROR/CANCEL partial 保存
            onStreamComplete(ctx, collectedContent.toString(), lastAiResponse.get(), signal);

            // usage 记录 -- 所有信号都执行
            if (usageRecorded.compareAndSet(false, true)) {
                SimpleModeStrategy.recordUsage(usageTracker, ctx, lastAiResponse.get());
            }
        });
    }

    protected void onStreamComplete(StrategyExecutionContext ctx, String content,
                                     ChatResponse lastResp, SignalType signal) {
        switch (signal) {
            case ON_COMPLETE -> {
                if (lastResp != null) {
                    conversationHelper.saveMessagesAndNotify(ctx.conversationId(),
                        ctx.request().message(), content,
                        ctx.route().toCompositeId(), lastResp, ctx.elapsed());
                } else {
                    log.warn("Stream completed without usable ChatResponse for conversation: {}",
                        ctx.conversationId());
                    conversationHelper.saveMessagesAndNotify(ctx.conversationId(),
                        ctx.request().message(), content,
                        ctx.route().toCompositeId(), null, ctx.elapsed());
                }
            }
            case ON_ERROR, CANCEL -> {
                log.warn("Stream {} for conversation {}: collected {} chars",
                    signal, ctx.conversationId(), content.length());
                conversationHelper.savePartialResponse(ctx.conversationId(), content);
            }
            default -> {}
        }
    }

}
