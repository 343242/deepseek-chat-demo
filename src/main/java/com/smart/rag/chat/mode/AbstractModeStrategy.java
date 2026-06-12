package com.smart.rag.chat.mode;

import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模式策略抽象基类 — 封装阻塞式/流式执行的通用骨架。
 */
public abstract class AbstractModeStrategy implements ChatModeStrategy {

    protected final AdvisorInfrastructure infra;
    protected final ChatRequestSpecFactory requestSpecFactory;
    protected final ChatUsageTracker usageTracker;

    protected AbstractModeStrategy(AdvisorInfrastructure infra,
                                   ChatRequestSpecFactory requestSpecFactory,
                                   ChatUsageTracker usageTracker) {
        this.infra = infra;
        this.requestSpecFactory = requestSpecFactory;
        this.usageTracker = usageTracker;
    }

    @Override
    public final StrategyExecuteResult execute(StrategyExecutionContext ctx) {
        AdvisorChainContext chainCtx = new AdvisorChainContext(
            ctx.conversationId(), ctx.request(), ctx.userId(),
            ctx.cagContext(), ctx.candidateId());
        ModeChainResult result = buildAdvisorChain(chainCtx);

        ChatResponse springResponse = requestSpecFactory.createSpec(
            ctx.chatClient(), ctx.candidateId(), ctx.request(),
            ctx.conversationId(), result.chain(), ctx.cagContext()
        ).call().chatResponse();

        String content = extractContent(springResponse);
        return StrategyExecuteResult.standard(springResponse, content);
    }

    @Override
    public Flux<String> executeStream(StrategyExecutionContext ctx) {
        AtomicBoolean usageRecorded = new AtomicBoolean(false);

        return ctx.chatClient().prompt()
                .user(ctx.request().message())
                .stream()
                .content()
                .doFinally(signal -> {
                    if (usageRecorded.compareAndSet(false, true)) {
                        recordUsage(usageTracker, ctx, null);
                    }
                });
    }

    public static String extractContent(ChatResponse response) {
        if (response == null) return "";
        Generation gen = response.getResult();
        return gen != null && gen.getOutput() != null ? gen.getOutput().getText() : "";
    }

    public static void recordUsage(ChatUsageTracker tracker,
                                   StrategyExecutionContext ctx,
                                   ChatResponse last) {
        String candidateId = ctx.candidateId();
        if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
            tracker.recordUsage(ctx.conversationId(), candidateId, last, ctx.elapsed());
        } else {
            tracker.recordUsage(ctx.conversationId(), candidateId, ctx.elapsed());
        }
    }
}
