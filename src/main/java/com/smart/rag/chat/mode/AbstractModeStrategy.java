package com.smart.rag.chat.mode;

import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModelStreamRequestFactory;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.infrastructure.stream.OkHttpSseModelStreamClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模式策略抽象基类 — 封装阻塞式/流式执行的通用骨架。
 * <p>
 * 子类只需覆写 {@link #buildAdvisorChain} 和（可选的）{@link #onStreamComplete}。
 */
public abstract class AbstractModeStrategy implements ChatModeStrategy {

    protected final AdvisorInfrastructure infra;
    protected final ChatRequestSpecFactory requestSpecFactory;
    protected final ChatUsageTracker usageTracker;
    protected final ModelStreamRequestFactory streamRequestFactory;
    protected final OkHttpSseModelStreamClient streamClient;

    protected AbstractModeStrategy(AdvisorInfrastructure infra,
                                   ChatRequestSpecFactory requestSpecFactory,
                                   ChatUsageTracker usageTracker,
                                   ModelStreamRequestFactory streamRequestFactory,
                                   OkHttpSseModelStreamClient streamClient) {
        this.infra = infra;
        this.requestSpecFactory = requestSpecFactory;
        this.usageTracker = usageTracker;
        this.streamRequestFactory = streamRequestFactory;
        this.streamClient = streamClient;
    }

    @Override
    public final StrategyExecuteResult execute(StrategyExecutionContext ctx) {
        AdvisorChainContext chainCtx = new AdvisorChainContext(
            ctx.conversationId(), ctx.request(), ctx.userId(),
            ctx.cagContext(), ctx.route());
        ModeChainResult result = buildAdvisorChain(chainCtx);

        ChatResponse springResponse = requestSpecFactory.createSpec(
            ctx.chatClient(), ctx.route(), ctx.request(),
            ctx.conversationId(), result.chain(), ctx.cagContext()
        ).call().chatResponse();

        String content = extractContent(springResponse);
        return StrategyExecuteResult.standard(springResponse, content);
    }

    @Override
    public Flux<String> executeStream(StrategyExecutionContext ctx) {
        AtomicBoolean usageRecorded = new AtomicBoolean(false);

        return streamClient.stream(streamRequestFactory.create(ctx.route(), ctx.request()))
                .doFinally(signal -> {
                    if (usageRecorded.compareAndSet(false, true)) {
                        recordUsage(usageTracker, ctx, null);
                    }
                });
    }

    /**
     * 从 ChatResponse 提取文本内容（null-safe）
     */
    public static String extractContent(ChatResponse response) {
        if (response == null) return "";
        Generation gen = response.getResult();
        return gen != null && gen.getOutput() != null ? gen.getOutput().getText() : "";
    }

    /**
     * 统一 usage 记录逻辑（null-safe）
     */
    public static void recordUsage(ChatUsageTracker tracker,
                                   StrategyExecutionContext ctx,
                                   ChatResponse last) {
        String modelId = ctx.route().toCompositeId();
        if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
            tracker.recordUsage(ctx.conversationId(), modelId, last, ctx.elapsed());
        } else {
            tracker.recordUsage(ctx.conversationId(), modelId, ctx.elapsed());
        }
    }
}
