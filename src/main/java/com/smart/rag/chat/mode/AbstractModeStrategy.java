package com.smart.rag.chat.mode;

import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.chat.dto.Reference;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.chat.service.ChatReferenceCollector;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatRetrievalService;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StreamCompletionHelper;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.chat.service.StreamResult;
import com.smart.rag.infrastructure.advisor.RagContextAdvisor;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模式策略抽象基类 — 封装阻塞式/流式执行的通用骨架。
 * <p>
 * 阻塞式 execute（方案 A，design §2.9）与流式 executeStream（R8，design §2.10）同源：
 * RAG 检索 → {@link ChatReferenceCollector} 编号 + {@code <<REF>>} → {@link RagContextAdvisor}
 * 动态尾注入；流式额外补齐 Redis 记忆（走 advisor 链）+ DB 落库（{@link #onStreamComplete}）。
 */
public abstract class AbstractModeStrategy implements ChatModeStrategy {

    /** MDC key：把 conversationId 注入 MDC，供 RAG trace 切面（Chat 路径）兜底取 sessionId */
    private static final String MDC_RAG_SESSION_ID = "ragSessionId";

    protected final AdvisorInfrastructure infra;
    protected final ChatRequestSpecFactory requestSpecFactory;
    protected final ChatUsageTracker usageTracker;
    protected final ChatRetrievalService chatRetrievalService;
    protected final ChatReferenceCollector chatReferenceCollector;
    protected final ContextPromptInjector contextPromptInjector;
    protected final ChatMessagePublisher chatMessagePublisher;
    protected final ChatConversationHelper conversationHelper;

    protected AbstractModeStrategy(AdvisorInfrastructure infra,
                                   ChatRequestSpecFactory requestSpecFactory,
                                   ChatUsageTracker usageTracker,
                                   ChatRetrievalService chatRetrievalService,
                                   ChatReferenceCollector chatReferenceCollector,
                                   ContextPromptInjector contextPromptInjector,
                                   ChatMessagePublisher chatMessagePublisher,
                                   ChatConversationHelper conversationHelper) {
        this.infra = infra;
        this.requestSpecFactory = requestSpecFactory;
        this.usageTracker = usageTracker;
        this.chatRetrievalService = chatRetrievalService;
        this.chatReferenceCollector = chatReferenceCollector;
        this.contextPromptInjector = contextPromptInjector;
        this.chatMessagePublisher = chatMessagePublisher;
        this.conversationHelper = conversationHelper;
    }

    @Override
    public final StrategyExecuteResult execute(StrategyExecutionContext ctx) {
        MDC.put(MDC_RAG_SESSION_ID, ctx.conversationId());
        try {
            return doExecute(ctx);
        } finally {
            MDC.remove(MDC_RAG_SESSION_ID);
        }
    }

    private StrategyExecuteResult doExecute(StrategyExecutionContext ctx) {
        AdvisorChainContext chainCtx = new AdvisorChainContext(
            ctx.conversationId(), ctx.request(), ctx.userId(),
            ctx.cagContext(), ctx.candidateId());
        ModeChainResult result = buildAdvisorChain(chainCtx);

        // 可变 chain copy，追加 per-request RagContextAdvisor
        List<Advisor> chain = new ArrayList<>(result.chain());

        // 方案 A：RAG 检索 → ChatRetrievalService（复用隔离/MMR/Rerank/Parent）→ ChatReferenceCollector
        // 编号 + <<REF>> 块 → RagContextAdvisor 注入动态尾（历史之后）
        List<Reference> references = null;
        String refBlock = null;
        if (ctx.request().isRagEnabled()) {
            List<Document> docs = chatRetrievalService.retrieve(
                ctx.request().message(), ctx.userId(), ctx.request().teamId());
            ChatReferenceCollector.ChatRefResult cr = chatReferenceCollector.collect(docs);
            refBlock = cr.refBlock();
            references = cr.references();
        }
        String cagSegment = contextPromptInjector.cagSegment(ctx.cagContext());
        chain.add(new RagContextAdvisor(cagSegment, refBlock));

        ChatResponse springResponse = requestSpecFactory.createSpec(
            ctx.chatClient(), ctx.candidateId(), ctx.request(),
            ctx.conversationId(), chain, ctx.cagContext()
        ).call().chatResponse();

        String content = extractContent(springResponse);
        return StrategyExecuteResult.standard(springResponse, content, references);
    }

    /**
     * 流式执行（R8）— 与阻塞式同源：走 advisor 链（含 MessageChatMemoryAdvisor → Redis 记忆 load/save），
     * RagContextAdvisor 注入动态尾，doFinally 落库。返回 {@link StreamResult}，references 由 chatStream
     * 用 AtomicReference 捕获最终成功模型的值（content Flux 经 fallbackExecutor 跨模型降级）。
     */
    @Override
    public StreamResult executeStream(StrategyExecutionContext ctx) {
        MDC.put(MDC_RAG_SESSION_ID, ctx.conversationId());
        try {
            return doExecuteStream(ctx);
        } finally {
            MDC.remove(MDC_RAG_SESSION_ID);
        }
    }

    private StreamResult doExecuteStream(StrategyExecutionContext ctx) {
        AdvisorChainContext chainCtx = new AdvisorChainContext(
            ctx.conversationId(), ctx.request(), ctx.userId(),
            ctx.cagContext(), ctx.candidateId());
        ModeChainResult result = buildAdvisorChain(chainCtx);

        List<Advisor> chain = new ArrayList<>(result.chain());

        List<Reference> references = null;
        String refBlock = null;
        if (ctx.request().isRagEnabled()) {
            List<Document> docs = chatRetrievalService.retrieve(
                ctx.request().message(), ctx.userId(), ctx.request().teamId());
            ChatReferenceCollector.ChatRefResult cr = chatReferenceCollector.collect(docs);
            refBlock = cr.refBlock();
            references = cr.references();
        }
        String cagSegment = contextPromptInjector.cagSegment(ctx.cagContext());
        chain.add(new RagContextAdvisor(cagSegment, refBlock));

        StringBuilder collectedContent = new StringBuilder();
        final int maxContentLength = 1 << 20;
        AtomicBoolean usageRecorded = new AtomicBoolean(false);

        Flux<String> content = requestSpecFactory.createSpec(
                ctx.chatClient(), ctx.candidateId(), ctx.request(),
                ctx.conversationId(), chain, ctx.cagContext())
            .stream()
            .content()
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

        return new StreamResult(content, references);
    }

    /**
     * 流式收尾 — 落库（ON_COMPLETE）或保存部分响应（ON_ERROR/CANCEL）。
     * 上提到抽象层让 SIMPLE 流式也落库（R8）；落库用 {@code ctx.request().message()} 干净原文，
     * 不含 <<REF>>（RagContextAdvisor 的动态 SystemMessage 不进 DB 落库路径）。
     */
    protected void onStreamComplete(StrategyExecutionContext ctx, String content, SignalType signal) {
        // P4-1：落库逻辑提取到 StreamCompletionHelper，AGENT 流式（不继承本类）复用同一语义
        StreamCompletionHelper.onComplete(ctx, content, signal, chatMessagePublisher, conversationHelper);
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
