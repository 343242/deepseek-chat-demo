package com.smart.rag.chat.mode;

import com.smart.rag.infrastructure.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModelStreamRequestFactory;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.infrastructure.stream.OkHttpSseModelStreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MULTI_TURN 模式策略 -- 多轮对话，自动维护记忆和上下文
 */
@Component
public class MultiTurnModeStrategy extends AbstractModeStrategy {

    private static final Logger log = LoggerFactory.getLogger(MultiTurnModeStrategy.class);

    private final ChatConversationHelper conversationHelper;

    public MultiTurnModeStrategy(AdvisorInfrastructure infra,
                                 ChatRequestSpecFactory requestSpecFactory,
                                 ChatUsageTracker usageTracker,
                                 ChatConversationHelper conversationHelper,
                                 ModelStreamRequestFactory streamRequestFactory,
                                 OkHttpSseModelStreamClient streamClient) {
        super(infra, requestSpecFactory, usageTracker, streamRequestFactory, streamClient);
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
    public Flux<String> executeStream(StrategyExecutionContext ctx) {
        StringBuilder collectedContent = new StringBuilder();
        final int maxContentLength = 1 << 20;
        AtomicBoolean usageRecorded = new AtomicBoolean(false);

        return streamClient.stream(streamRequestFactory.create(ctx.route(), ctx.request()))
        .doOnNext(text -> {
            if (text != null && collectedContent.length() < maxContentLength) {
                int remaining = maxContentLength - collectedContent.length();
                collectedContent.append(text, 0, Math.min(text.length(), remaining));
            }
        })
        .doFinally(signal -> {
            onStreamComplete(ctx, collectedContent.toString(), signal);

            if (usageRecorded.compareAndSet(false, true)) {
                recordUsage(usageTracker, ctx, null);
            }
        });
    }

    protected void onStreamComplete(StrategyExecutionContext ctx, String content,
                                     SignalType signal) {
        switch (signal) {
            case ON_COMPLETE -> {
                conversationHelper.saveMessagesAndNotify(ctx.conversationId(),
                    ctx.request().message(), content,
                    ctx.route().toCompositeId(), null, ctx.elapsed());
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
