package com.smart.rag.chat.mode;

import com.smart.rag.infrastructure.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModelStreamRequestFactory;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.infrastructure.stream.OkHttpSseModelStreamClient;
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
    private final ModelStreamRequestFactory streamRequestFactory;
    private final OkHttpSseModelStreamClient streamClient;

    public MultiTurnModeStrategy(AdvisorInfrastructure infra,
                                 ChatRequestSpecFactory requestSpecFactory,
                                 ChatUsageTracker usageTracker,
                                 ChatConversationHelper conversationHelper,
                                 ModelStreamRequestFactory streamRequestFactory,
                                 OkHttpSseModelStreamClient streamClient) {
        this.infra = infra;
        this.requestSpecFactory = requestSpecFactory;
        this.usageTracker = usageTracker;
        this.conversationHelper = conversationHelper;
        this.streamRequestFactory = streamRequestFactory;
        this.streamClient = streamClient;
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
            // 消息持久化 — ON_COMPLETE 完整保存，ON_ERROR/CANCEL partial 保存
            onStreamComplete(ctx, collectedContent.toString(), null, signal);

            // OkHttp SSE 不返回最终 Spring AI ChatResponse，usage 降级为耗时记录
            if (usageRecorded.compareAndSet(false, true)) {
                SimpleModeStrategy.recordUsage(usageTracker, ctx, null);
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
