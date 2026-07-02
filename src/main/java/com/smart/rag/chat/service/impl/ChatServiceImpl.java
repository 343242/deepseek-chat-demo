package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.chat.dto.FallbackMeta;
import com.smart.rag.chat.dto.Reference;
import com.smart.rag.infrastructure.exception.ProviderNotFoundException;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.adapter.ChatModelAdapter;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.resilience.FallbackExecutor;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.chat.service.ChatService;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.MdcPropagator;
import com.smart.rag.chat.service.SseStreamBridge;
import com.smart.rag.chat.service.UserContextProvider;
import com.smart.rag.chat.service.StreamResult;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.common.util.UuidGeneratorUtil;
import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.team.service.TeamMembershipVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
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
    private final ChatUsageTracker usageTracker;
    private final ChatConversationHelper conversationHelper;
    private final ChatMessagePublisher chatMessagePublisher;
    private final SseStreamBridge sseStreamBridge;
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;
    private final UserContextProvider userContextProvider;
    private final TeamMembershipVerifier teamMembershipVerifier;

    public ChatServiceImpl(LlmClientRegistry llmRegistry,
                           FallbackEligibility fallbackEligibility,
                           ModeRouter modeRouter,
                           ChatUsageTracker usageTracker,
                           ChatConversationHelper conversationHelper,
                           ChatMessagePublisher chatMessagePublisher,
                           SseStreamBridge sseStreamBridge,
                           RequestContextManager cagContextManager,
                           CagProperties cagProperties,
                           UserContextProvider userContextProvider,
                           TeamMembershipVerifier teamMembershipVerifier) {
        this.llmRegistry = llmRegistry;
        this.fallbackExecutor = new FallbackExecutor(fallbackEligibility);
        this.modeRouter = modeRouter;
        this.usageTracker = usageTracker;
        this.conversationHelper = conversationHelper;
        this.chatMessagePublisher = chatMessagePublisher;
        this.sseStreamBridge = sseStreamBridge;
        this.cagContextManager = cagContextManager;
        this.cagProperties = cagProperties;
        this.userContextProvider = userContextProvider;
        this.teamMembershipVerifier = teamMembershipVerifier;
    }

    // ==================== 阻塞式聊天（跨模型 Fallback） ====================

    @Override
    public ChatResponse chat(ChatRequest request) {
        PreparedContext pctx = prepare(request);

        List<CapabilityClient> chain = buildChain(pctx);

        try {
            return fallbackExecutor.execute(chain, client -> {
                ChatCapable chatCapable = (ChatCapable) client;
                ChatClient chatClient = ChatClient.builder(new ChatModelAdapter(chatCapable)).build();
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

        Flux<String> stream = fallbackExecutor.executeStream(chain, client -> {
            ChatCapable chatCapable = (ChatCapable) client;
            ChatClient chatClient = ChatClient.builder(new ChatModelAdapter(chatCapable)).build();
            StrategyExecutionContext execCtx = new StrategyExecutionContext(
                chatClient, client.candidateId(), request,
                pctx.conversationId, pctx.rawConversationId, pctx.userId,
                pctx.cagCtx, System.currentTimeMillis());

            StreamResult sr = pctx.modeStrategy.executeStream(execCtx);
            refsRef.set(sr.references()); // 捕获最终成功模型的 references（fallback 时后者覆盖前者）
            Flux<String> flux = sr.content();
            if (parentMdc != null) {
                flux = flux.doOnSubscribe(s -> MdcPropagator.restore(parentMdc))
                           .doFinally(signal -> MdcPropagator.clear());
            }
            return flux;
        });

        return sseStreamBridge.bridge(stream, refsRef);
    }

    // ==================== 内部辅助 ====================

    /**
     * 构建 fallback 链：用户请求的模型优先（链首），其余按 BYOK/系统配置顺序。
     * <p>
     * 修复：原 getUserChain 不考虑 ChatRequest.model，用户指定的模型可能不在链中。
     * 现在：如果用户指定的模型在 registry 中存在，放到链首；不在链中的从 registry 补入。
     * 如果指定模型不存在（无效 ID），跳过，用原始链兜底。
     */
    private List<CapabilityClient> buildChain(PreparedContext pctx) {
        List<CapabilityClient> baseChain = llmRegistry.getUserChain(LlmCapability.CHAT, pctx.userId);
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
        // BYOK：用户未指定 model 时用其 BYOK 默认（无 BYOK → getUserDefault 内部 delegate 系统级 default）
        Long userId = userContextProvider.getCurrentUserId();
        return llmRegistry.getUserDefault(LlmCapability.CHAT, userId).candidateId();
    }

    private ChatResponse processResult(StrategyExecuteResult result,
                                        String candidateId,
                                        PreparedContext pctx,
                                        FallbackMeta fallback) {
        if (result.springAiResponse() != null) {
            usageTracker.recordUsage(pctx.conversationId, candidateId,
                result.springAiResponse(), elapsed(pctx.startTimeMs));
        } else {
            usageTracker.recordUsage(pctx.conversationId, candidateId,
                elapsed(pctx.startTimeMs));
        }

        chatMessagePublisher.publishMessageSave(pctx.conversationId,
            pctx.request().message(), result.content(),
            candidateId, result.springAiResponse(), elapsed(pctx.startTimeMs));

        if (result.agentMetadata() != null) {
            return new ChatResponse(candidateId, result.content(),
                pctx.rawConversationId, null, result.agentMetadata(), result.references());
        }
        return new ChatResponse(candidateId, result.content(),
            pctx.rawConversationId, fallback, null, result.references());
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
