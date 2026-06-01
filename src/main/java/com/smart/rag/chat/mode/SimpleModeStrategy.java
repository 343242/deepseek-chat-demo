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
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SIMPLE 模式策略 -- 单轮对话，无记忆、无上下文
 */
@Component
public class SimpleModeStrategy implements ChatModeStrategy {

    private final AdvisorInfrastructure infra;
    private final ChatRequestSpecFactory requestSpecFactory;
    private final ChatUsageTracker usageTracker;
    private final ModelStreamRequestFactory streamRequestFactory;
    private final OkHttpSseModelStreamClient streamClient;

    public SimpleModeStrategy(AdvisorInfrastructure infra,
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

        String content = extractContent(springResponse);
        return StrategyExecuteResult.standard(springResponse, content);
    }

    @Override
    public Flux<String> executeStream(StrategyExecutionContext ctx) {
        AtomicBoolean usageRecorded = new AtomicBoolean(false);

        return streamClient.stream(streamRequestFactory.create(ctx.route(), ctx.request()))
                .doFinally(signal -> {
                    // SIMPLE: 不持久化消息

                    // OkHttp SSE 不返回最终 Spring AI ChatResponse，usage 降级为耗时记录
                    if (usageRecorded.compareAndSet(false, true)) {
                        recordUsage(usageTracker, ctx, null);
                    }
                });
    }

    /**
     * 从 ChatResponse 提取文本内容（null-safe）。
     * <p>
     * 供所有策略复用，避免各策略各自实现。
     */
    public static String extractContent(ChatResponse response) {
        if (response == null) return "";
        Generation gen = response.getResult();
        return gen != null && gen.getOutput() != null ? gen.getOutput().getText() : "";
    }

    /**
     * 统一 usage 记录逻辑（null-safe）。
     * <p>
     * 供所有策略复用。
     * 优先使用含 usage metadata 的 ChatResponse 记录，否则仅记录耗时。
     */
    public static void recordUsage(ChatUsageTracker usageTracker,
                            StrategyExecutionContext ctx,
                            ChatResponse last) {
        String modelId = ctx.route().toCompositeId();
        if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
            usageTracker.recordUsage(ctx.conversationId(), modelId, last, ctx.elapsed());
        } else {
            usageTracker.recordUsage(ctx.conversationId(), modelId, ctx.elapsed());
        }
    }
}
