package com.smart.rag.chat.mode;

import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.mode.ModeSupport;
import com.smart.rag.mode.ChatModeStrategy;
import com.smart.rag.mode.Reference;
import com.smart.rag.mode.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.chat.service.ChatReferenceCollector;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatRetrievalService;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.mode.ModeChainResult;
import com.smart.rag.chat.service.StreamCompletionHelper;
import com.smart.rag.mode.StrategyExecuteResult;
import com.smart.rag.mode.StrategyExecutionContext;
import com.smart.rag.mode.StreamFrame;
import com.smart.rag.mode.StreamResult;
import com.smart.rag.infrastructure.advisor.RagContextAdvisor;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
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
    /** MDC key：检索路径模式（注入 CHAT，供 trace 切面 + PATH_RECALL 兜底取 mode） */
    private static final String MDC_RAG_MODE = "ragMode";
    /** MDC key：当前用户 ID（注入，供 trace 切面对无上下文参数的埋点方法兜底取 userId，如 ChatReferenceCollector.collect） */
    private static final String MDC_RAG_USER_ID = "ragUserId";

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
        MDC.put(MDC_RAG_MODE, "CHAT");
        putRagUserId(ctx);
        try {
            return doExecute(ctx);
        } finally {
            MDC.remove(MDC_RAG_SESSION_ID);
            MDC.remove(MDC_RAG_MODE);
            MDC.remove(MDC_RAG_USER_ID);
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
        appendPromptLogging(chain);

        ChatResponse springResponse = requestSpecFactory.createSpec(
            ctx.chatClient(), ctx.candidateId(), ctx.request(),
            ctx.conversationId(), chain, ctx.cagContext()
        ).call().chatResponse();

        String content = ModeSupport.extractContent(springResponse);
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
        MDC.put(MDC_RAG_MODE, "CHAT");
        putRagUserId(ctx);
        try {
            return doExecuteStream(ctx);
        } finally {
            MDC.remove(MDC_RAG_SESSION_ID);
            MDC.remove(MDC_RAG_MODE);
            MDC.remove(MDC_RAG_USER_ID);
        }
    }

    /**
     * 链尾追加最终提示词日志 Advisor（ORDER 最大，打印所有前置 Advisor 处理后的最终消息）。
     * 开关关闭时（app.chat.prompt-log-enabled=false）为 no-op。
     */
    private void appendPromptLogging(List<Advisor> chain) {
        Advisor advisor = infra.getPromptLoggingAdvisor();
        if (advisor != null) {
            chain.add(advisor);
        }
    }

    private static void putRagUserId(StrategyExecutionContext ctx) {
        Long userId = ctx.userId();
        if (userId != null) {
            MDC.put(MDC_RAG_USER_ID, Long.toString(userId));
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
        appendPromptLogging(chain);

        StringBuilder collectedContent = new StringBuilder();
        final int maxContentLength = 1 << 20;
        AtomicBoolean usageRecorded = new AtomicBoolean(false);

        // 用 .chatResponse() 替代 .content()：后者只取文本且 filter(hasLength) 丢弃 reasoning-only chunk，
        // 导致思考过程（reasoning_content）在标准模式流式下完全丢失。这里手动拆出 text + reasoning，
        // 包装为 StreamFrame 下发（REASONING 帧供 SseStreamBridge 发 event:reasoning）。
        Flux<StreamFrame> frames = requestSpecFactory.createSpec(
                ctx.chatClient(), ctx.candidateId(), ctx.request(),
                ctx.conversationId(), chain, ctx.cagContext())
            .stream()
            .chatResponse()
            .flatMapIterable(AbstractModeStrategy::splitIntoFrames)
            .doOnNext(frame -> {
                if (frame.isContent() && collectedContent.length() < maxContentLength) {
                    int remaining = maxContentLength - collectedContent.length();
                    collectedContent.append(frame.payload(), 0, Math.min(frame.payload().length(), remaining));
                }
            })
            .doFinally(signal -> {
                onStreamComplete(ctx, collectedContent.toString(), signal);
                if (usageRecorded.compareAndSet(false, true)) {
                    recordUsage(usageTracker, ctx, null);
                }
            });

        return new StreamResult(frames, references);
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

    /**
     * 从 Spring AI {@link ChatResponse} 拆出正文与思考帧。
     * <p>
     * 一个 ChatResponse 可能同时携带 content（{@code AssistantMessage.getText()}）
     * 和 reasoning_content（{@code AssistantMessage.getMetadata().get("reasoning_content")}）。
     * 拆为独立的 {@link StreamFrame}，保留时序：reasoning 先（模型先思考后作答的天然顺序），
     * content 后。两者皆空时返回空列表（被 flatMapIterable 跳过，等价于原 .content() 的 filter(hasLength)）。
     * <p>
     * 供 {@link AbstractModeStrategy#doExecuteStream} 与 {@code AgentModeStrategy.executeStream} 共用。
     */
    public static List<StreamFrame> splitIntoFrames(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return List.of();
        }
        org.springframework.ai.chat.messages.AssistantMessage msg = resp.getResult().getOutput();
        java.util.List<StreamFrame> out = new java.util.ArrayList<>(2);
        Object rc = msg.getMetadata() != null ? msg.getMetadata().get("reasoning_content") : null;
        if (rc instanceof String rs && !rs.isEmpty()) {
            out.add(StreamFrame.reasoning(rs));
        }
        String text = msg.getText();
        if (text != null && !text.isEmpty()) {
            out.add(StreamFrame.content(text));
        }
        return out;
    }
}
