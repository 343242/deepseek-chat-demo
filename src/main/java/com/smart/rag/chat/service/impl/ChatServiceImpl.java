package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.mode.RequestContext;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.mode.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.chat.dto.CancelReason;
import com.smart.rag.chat.dto.CancelStreamResponse;
import com.smart.rag.mode.Reference;
import com.smart.rag.chat.dto.FallbackMeta;
import com.smart.rag.mode.WorkspaceInfo;
import com.smart.rag.infrastructure.exception.ProviderNotFoundException;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.adapter.ChatModelAssembler;
import com.smart.rag.infrastructure.llm.usage.UsageScene;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.infrastructure.llm.resilience.FallbackExecutor;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.chat.service.ChatService;
import com.smart.rag.chat.service.ActiveStreamRegistry;
import com.smart.rag.chat.service.SseStreamBridge;
import com.smart.rag.chat.service.MdcPropagator;
import com.smart.rag.chat.service.UserContextProvider;
import com.smart.rag.mode.StreamFrame;
import com.smart.rag.mode.StreamResult;
import com.smart.rag.mode.StreamUsageSnapshot;
import com.smart.rag.mode.StrategyExecuteResult;
import com.smart.rag.mode.StrategyExecutionContext;
import com.smart.rag.common.util.UuidGeneratorUtil;
import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.team.service.TeamMembershipVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

/**
 * 聊天服务（编排层）
 * <p>
 * 集成跨模型 Fallback：阻塞式/流式均通过 FallbackExecutor 自动降级。
 * 每个候选客户端已内建 ResilientClient（重试 + 熔断 + 探测），
 * 本层只负责跨模型降级链编排。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final LlmClientRegistry llmRegistry;
    private final FallbackExecutor fallbackExecutor;
    private final ModeRouter modeRouter;
    private final ChatModelAssembler chatModelAssembler;
    private final ChatConversationHelper conversationHelper;
    private final ChatMessagePublisher chatMessagePublisher;
    private final SseStreamBridge sseStreamBridge;
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;
    private final UserContextProvider userContextProvider;
    private final TeamMembershipVerifier teamMembershipVerifier;
    private final ActiveStreamRegistry activeStreamRegistry;
    private final LlmMetrics llmMetrics;

    public ChatServiceImpl(LlmClientRegistry llmRegistry,
                           FallbackEligibility fallbackEligibility,
                           ModeRouter modeRouter,
                           ChatModelAssembler chatModelAssembler,
                           ChatConversationHelper conversationHelper,
                           ChatMessagePublisher chatMessagePublisher,
                           SseStreamBridge sseStreamBridge,
                           RequestContextManager cagContextManager,
                           CagProperties cagProperties,
                           UserContextProvider userContextProvider,
                           TeamMembershipVerifier teamMembershipVerifier,
                           ActiveStreamRegistry activeStreamRegistry,
                           LlmMetrics llmMetrics) {
        this.llmRegistry = llmRegistry;
        this.fallbackExecutor = new FallbackExecutor(fallbackEligibility);
        this.modeRouter = modeRouter;
        this.chatModelAssembler = chatModelAssembler;
        this.conversationHelper = conversationHelper;
        this.chatMessagePublisher = chatMessagePublisher;
        this.sseStreamBridge = sseStreamBridge;
        this.cagContextManager = cagContextManager;
        this.cagProperties = cagProperties;
        this.userContextProvider = userContextProvider;
        this.teamMembershipVerifier = teamMembershipVerifier;
        this.activeStreamRegistry = activeStreamRegistry;
        this.llmMetrics = llmMetrics;
    }

    // ==================== 阻塞式聊天（跨模型 Fallback） ====================

    @Override
    public ChatResponse chat(ChatRequest request) {
        PreparedContext pctx = prepare(request);

        List<CapabilityClient> chain = buildChain(pctx);

        try {
            return fallbackExecutor.execute(chain, client -> {
                ChatClient chatClient = chatModelAssembler.chatClient(
                    pctx.userId, client.candidateId(), UsageScene.CHAT, pctx.conversationId);
                StrategyExecutionContext execCtx = new StrategyExecutionContext(
                    chatClient, client.candidateId(), request,
                    pctx.conversationId, pctx.rawConversationId, pctx.userId,
                    pctx.cagCtx, System.currentTimeMillis());

                StrategyExecuteResult result = pctx.modeStrategy.execute(execCtx);
                boolean isFallback = !client.candidateId().equals(pctx.requestedCandidateId);
                FallbackMeta meta = isFallback
                    ? new FallbackMeta(pctx.requestedCandidateId, true) : null;
                return processResult(result, client.candidateId(), pctx, meta);
            });
        } catch (Exception e) {
            throw wrapException(e, pctx.requestedCandidateId);
        }
    }

    // ==================== 流式聊天（跨模型 Fallback） ====================

    @Override
    public SseEmitter chatStream(ChatRequest request) {
        PreparedContext pctx = prepare(request);
        List<CapabilityClient> chain = buildChain(pctx);
        Map<String, String> parentMdc = MdcPropagator.capture();

        AtomicReference<List<Reference>> refsRef = new AtomicReference<>();
        AtomicReference<Map<String, Object>> agentMetadataRef = new AtomicReference<>();
        AtomicReference<FallbackMeta> fallbackRef = new AtomicReference<>();
        AtomicReference<WorkspaceInfo> workspaceRef = new AtomicReference<>();
        AtomicReference<AtomicReference<StreamUsageSnapshot>> usageRef = new AtomicReference<>();
        // 降级链已尝试模型（每次 lambda 执行追加；error 帧在降级链耗尽时携带，供前端提示）
        List<String> attempted = Collections.synchronizedList(new ArrayList<>());

        Flux<StreamFrame> stream = fallbackExecutor.executeStream(chain, client -> {
            ChatClient chatClient = chatModelAssembler.chatClient(
                pctx.userId, client.candidateId(), UsageScene.CHAT, pctx.conversationId);
            StrategyExecutionContext execCtx = new StrategyExecutionContext(
                chatClient, client.candidateId(), request,
                pctx.conversationId, pctx.rawConversationId, pctx.userId,
                pctx.cagCtx, System.currentTimeMillis());

            attempted.add(client.candidateId());
            StreamResult sr = pctx.modeStrategy.executeStream(execCtx);
            refsRef.set(sr.references());                  // 标准模式同步 references
            agentMetadataRef.set(sr.agentMetadata());      // Agent 模式 intent/confidence（retrievalRounds 流后刷新）
            workspaceRef.set(sr.workspace());              // Agent 模式工作区（成功模型覆盖前序失败模型）
            usageRef.set(sr.usageRef());                   // 每轮用量快照（成功流 doOnComplete 写入 → event:usage 尾帧）
            boolean isFallback = !client.candidateId().equals(pctx.requestedCandidateId);
            fallbackRef.set(isFallback ? new FallbackMeta(pctx.requestedCandidateId, true) : null);
            Flux<StreamFrame> flux = sr.frames();
            if (parentMdc != null) {
                flux = flux.doOnSubscribe(s -> MdcPropagator.restore(parentMdc))
                           .doFinally(signal -> MdcPropagator.clear());
            }
            return flux;
        });

        // 外层 doOnComplete：Agent 模式 workspace 在 ReAct 检索期间填充，content 流结束后才就绪。
        // 此时现场构建 references 终值（修复 agent 流式 references 固化为空的 bug）+ 刷新 retrievalRounds。
        // reactor 语义保证 doOnComplete 副作用先于 SseStreamBridge subscribe 的 onComplete 回调（发帧）。
        stream = stream.doOnComplete(() -> {
            WorkspaceInfo ws = workspaceRef.get();
            if (ws != null) {
                refsRef.set(Reference.fromAll(ws.getRetrievedDocs()));
                Map<String, Object> meta = agentMetadataRef.get();
                if (meta != null) {
                    meta.put("retrievalRounds", ws.getRetrievalRound());
                }
            }
        });

        return bridgeCancellable(stream, pctx, refsRef, agentMetadataRef, fallbackRef, attempted, usageRef.get());
    }

    // ==================== 流式取消（design chat-stream-cancel.md §4/§5） ====================

    /**
     * 可取消桥接：创建 cancelSink/cancelled/emitterRef → 先 register（design §4.3，信号可重放）
     * → takeUntilOther 包装（design §4.4，软取消）→ bridge（后填充 emitter）。
     * <p>
     * register 先于 bridge 的 subscribe：窗口内的取消信号会被 sink 缓存，takeUntilOther 订阅时立即触发。
     * emitter 由 bridge 创建后回填 emitterRef，供 registry 兜底清理 / canceled 帧使用。
     * 单会话单流：register 检测到同 conversationId 旧流时，自动软取消旧流（design §5.1）。
     */
    private SseEmitter bridgeCancellable(Flux<StreamFrame> stream,
                                         PreparedContext pctx,
                                         AtomicReference<List<Reference>> refsRef,
                                         AtomicReference<Map<String, Object>> agentMetadataRef,
                                         AtomicReference<FallbackMeta> fallbackRef,
                                         List<String> attempted,
                                         AtomicReference<StreamUsageSnapshot> usageRef) {
        // 软取消状态（design §4.2/§4.3）
        Sinks.Empty<Void> cancelSink = Sinks.empty();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<String> cancelReason = new AtomicReference<>();
        AtomicReference<SseEmitter> emitterRef = new AtomicReference<>();

        // takeUntilOther: cancelSink 发信号后 cancel 上游(断 HTTP 读取)，下游以正常 onComplete 终止。
        // 注意：不保证 drain，取消后大概率截断（design §8.1）；已 dispatch 帧发完，缓冲内未 dispatch 丢弃。
        Flux<StreamFrame> cancellable = stream.takeUntilOther(cancelSink.asMono());

        // ① 先 register（emitter 尚未创建，用 AtomicReference 后填充；design §4.3）
        ActiveStreamRegistry.ActiveStream activeStream = new ActiveStreamRegistry.ActiveStream(
                cancelSink, cancelled, cancelReason, emitterRef,
                System.currentTimeMillis(), String.valueOf(pctx.userId));
        ActiveStreamRegistry.ActiveStream old = activeStreamRegistry.register(pctx.conversationId, activeStream);
        if (old != null) {
            log.debug("Replaced previous active stream for conversation {}", pctx.conversationId);
        }

        // ② 再 bridge —— subscribe 时 cancelSink 的信号可被重放（窗口内的取消能生效）
        return sseStreamBridge.bridge(cancellable,
                new SseStreamBridge.SseTailFrames(usageRef, refsRef, agentMetadataRef, fallbackRef, attempted),
                cancelSink, cancelled, cancelReason, emitterRef,
                pctx.conversationId, activeStream);
    }

    /**
     * 取消指定会话的活跃流式生成（design chat-stream-cancel.md §4/§6.1）。
     * <p>
     * 软取消：registry.cancel 先置 cancelled 标志、再 cancelSink.tryEmitEmpty()，
     * 触发 takeUntilOther → 下游正常 onComplete → 桥接层发 event:canceled → complete emitter。
     * 已生成内容不落库（StreamCompletionHelper CANCEL 分支）。
     */
    @Override
    public CancelStreamResponse cancelStream(String rawConversationId, CancelReason reason) {
        Long userId = userContextProvider.getCurrentUserId();
        String isolatedId = ConversationIdUtil.buildIsolatedId(userId, rawConversationId);
        String reasonName = reason != null ? reason.name() : CancelReason.USER_ABORT.name();

        boolean cancelled = activeStreamRegistry.cancel(isolatedId, reasonName);
        if (cancelled) {
            llmMetrics.recordStreamCancelled(reasonName);
            log.info("Stream cancelled by user: conversation={}, reason={}",
                    ConversationIdUtil.mask(isolatedId), reasonName);
        }
        return new CancelStreamResponse(cancelled, rawConversationId);
    }

    // ==================== 内部辅助 ====================

    /**
     * 构建 fallback 链：用户请求的模型优先（链首），其余按系统配置顺序。
     * <p>
     * 修复：原 getChain 不考虑 ChatRequest.model，用户指定的模型可能不在链中。
     * 现在：如果用户指定的模型在 registry 中存在，放到链首；不在链中的从 registry 补入。
     * 如果指定模型不存在（无效 ID），跳过，用原始链兜底。
     */
    private List<CapabilityClient> buildChain(PreparedContext pctx) {
        List<CapabilityClient> baseChain = llmRegistry.getChain(LlmCapability.CHAT);
        String requestedId = pctx.requestedCandidateId;

        // 用户指定模型已在链首 → 直接返回
        if (!baseChain.isEmpty() && baseChain.get(0).candidateId().equals(requestedId)) {
            return baseChain;
        }

        // 尝试从 registry 获取用户指定的模型
        CapabilityClient requestedClient = llmRegistry.find(requestedId);
        if (requestedClient == null) {
            // 指定模型不在 registry 中（无效 ID），用原始链兜底
            log.warn("Requested model '{}' not found in registry, using default fallback chain", requestedId);
            return baseChain;
        }

        // 能力校验：聊天链路只接受 CHAT 能力模型。
        // 防止前端被绕过后直传 embedding/reranking candidateId（运行时强转 ClassCastException 才暴露，
        // 错误信息不友好且非业务语义）。此处显式拒绝，返回业务码 103004。
        if (requestedClient.capability() != LlmCapability.CHAT) {
            log.warn("Requested model '{}' has capability {}, chat requires CHAT", requestedId, requestedClient.capability());
            throw new ClientException(ClientErrorCode.MODEL_CAPABILITY_NOT_CHAT);
        }

        // 把用户指定模型放到链首，其余去重追加
        List<CapabilityClient> prioritized = new ArrayList<>(baseChain.size() + 1);
        prioritized.add(requestedClient);
        for (CapabilityClient c : baseChain) {
            if (!c.candidateId().equals(requestedId)) {
                prioritized.add(c);
            }
        }
        log.debug("Prioritized requested model '{}' at chain head (chain size={})", requestedId, prioritized.size());
        return prioritized;
    }

    private PreparedContext prepare(ChatRequest request) {
        String candidateId = resolveCandidateId(request);
        Long userId = userContextProvider.getCurrentUserId();
        if (request.teamId() != null) {
            // teamId 是受保护的租户边界：检索前必须确认调用方是该团队活跃成员
            teamMembershipVerifier.verifyMember(request.teamId(), userId);
        }
        ChatModeStrategy modeStrategy = modeRouter.route(request.mode());

        String rawConversationId = request.conversationId();
        if (rawConversationId == null) {
            rawConversationId = UuidGeneratorUtil.generateCompact();
        }
        String conversationId = ConversationIdUtil.buildIsolatedId(userId, rawConversationId);

        conversationHelper.ensureConversationExists(userId, conversationId, candidateId);

        RequestContext cagCtx = null;
        if (cagProperties.isEnabled()) {
            int msgCount = conversationHelper.getMessageCount(conversationId);
            cagCtx = cagContextManager.buildContext(userId, conversationId, request.isRagEnabled(), msgCount);
        }

        log.debug("Chat request: userId={}, candidateId={}, mode={}, conversationId={}",
            userId, candidateId, modeStrategy.getMode(), conversationId);

        return new PreparedContext(candidateId, userId, conversationId,
            rawConversationId, modeStrategy, cagCtx, request);
    }

    String resolveCandidateId(ChatRequest request) {
        String model = request.model();
        if (model != null && !model.isBlank()) {
            if (model.contains("/")) {
                throw new IllegalArgumentException(
                    "Invalid model format: '" + model + "'. Expected registry candidate ID "
                    + "(e.g. 'deepseek-v4-flash'), not provider/model compound format. "
                    + "See docs/API-DOCS.md for valid candidate IDs.");
            }
            return model;
        }
        // 用户未指定 model 时用系统默认
        return llmRegistry.getDefault(LlmCapability.CHAT).candidateId();
    }

    private ChatResponse processResult(StrategyExecuteResult result,
                                        String candidateId,
                                        PreparedContext pctx,
                                        FallbackMeta fallback) {
        Integer totalTokens = ChatConversationHelper.extractTotalTokens(result.springAiResponse());
        long durationMs = elapsed(pctx.startTimeMs);
        chatMessagePublisher.publishMessageSave(pctx.conversationId,
            pctx.request().message(), result.content(),
            candidateId, totalTokens, durationMs);

        if (result.agentMetadata() != null) {
            return new ChatResponse(candidateId, result.content(),
                pctx.rawConversationId, null, result.agentMetadata(), result.references(),
                totalTokens, durationMs);
        }
        return new ChatResponse(candidateId, result.content(),
            pctx.rawConversationId, fallback, null, result.references(),
            totalTokens, durationMs);
    }

    private static long elapsed(long startTimeMs) {
        return System.currentTimeMillis() - startTimeMs;
    }

    private static RuntimeException wrapException(Exception e, String candidateId) {
        if (e instanceof RuntimeException re) return re;
        return new ProviderNotFoundException(candidateId, e.getMessage());
    }

    /** 预处理后的上下文 */
    private class PreparedContext {
        final String requestedCandidateId;
        final Long userId;
        final String conversationId;
        final String rawConversationId;
        final ChatModeStrategy modeStrategy;
        final RequestContext cagCtx;
        final long startTimeMs;
        final ChatRequest chatRequest;

        PreparedContext(String requestedCandidateId, Long userId, String conversationId,
                        String rawConversationId, ChatModeStrategy modeStrategy,
                        RequestContext cagCtx, ChatRequest chatRequest) {
            this.requestedCandidateId = requestedCandidateId;
            this.userId = userId;
            this.conversationId = conversationId;
            this.rawConversationId = rawConversationId;
            this.modeStrategy = modeStrategy;
            this.cagCtx = cagCtx;
            this.startTimeMs = System.currentTimeMillis();
            this.chatRequest = chatRequest;
        }

        ChatRequest request() { return chatRequest; }
    }
}
