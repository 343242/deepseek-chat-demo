package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.client.ChatClientRegistry;
import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.chat.dto.FallbackMeta;
import com.smart.rag.chat.fallback.ChatFallbackProperties;
import com.smart.rag.chat.fallback.FallbackChainProvider;
import com.smart.rag.chat.fallback.FallbackEligibility;
import com.smart.rag.chat.fallback.StreamRetryHandler;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.chat.provider.ModelRouter;
import com.smart.rag.chat.service.AgentChainResult;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatRequestSpecFactory;
import com.smart.rag.chat.service.ChatService;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.common.uuid.UuidV7;
import com.smart.rag.conversation.util.ConversationIdUtil;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.rag.agent.config.AgentRagProperties;
import com.smart.rag.rag.agent.guardrail.AgentDegradationStrategy;
import com.smart.rag.security.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天服务（编排层）— 请求预处理 → 委托工厂构建请求 → 调用并处理响应。
 * 集成兜底策略：阻塞式全链路降级，流式同模型重试后降级。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final ChatClientRegistry registry;
    private final ModelRouter modelRouter;
    private final ModeRouter modeRouter;
    private final ChatRequestSpecFactory requestSpecFactory;
    private final ChatUsageTracker usageTracker;
    private final ChatConversationHelper conversationHelper;
    private final ChatFallbackProperties fallbackProperties;
    private final FallbackChainProvider fallbackChainProvider;
    private final FallbackEligibility fallbackEligibility;
    private final StreamRetryHandler streamRetryHandler;
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;
    private final com.smart.rag.chat.service.ChatAdvisorChainFactory advisorChainFactory;
    private final AgentRagProperties agentProperties;
    private final AgentDegradationStrategy degradationStrategy;

    public ChatServiceImpl(ChatClientRegistry registry,
                           ModelRouter modelRouter,
                           ModeRouter modeRouter,
                           ChatRequestSpecFactory requestSpecFactory,
                           ChatUsageTracker usageTracker,
                           ChatConversationHelper conversationHelper,
                           ChatFallbackProperties fallbackProperties,
                           FallbackChainProvider fallbackChainProvider,
                           FallbackEligibility fallbackEligibility,
                           StreamRetryHandler streamRetryHandler,
                           RequestContextManager cagContextManager,
                           CagProperties cagProperties,
                           com.smart.rag.chat.service.ChatAdvisorChainFactory advisorChainFactory,
                           AgentRagProperties agentProperties,
                           AgentDegradationStrategy degradationStrategy) {
        this.registry = registry;
        this.modelRouter = modelRouter;
        this.modeRouter = modeRouter;
        this.requestSpecFactory = requestSpecFactory;
        this.usageTracker = usageTracker;
        this.conversationHelper = conversationHelper;
        this.fallbackProperties = fallbackProperties;
        this.fallbackChainProvider = fallbackChainProvider;
        this.fallbackEligibility = fallbackEligibility;
        this.streamRetryHandler = streamRetryHandler;
        this.cagContextManager = cagContextManager;
        this.cagProperties = cagProperties;
        this.advisorChainFactory = advisorChainFactory;
        this.agentProperties = agentProperties;
        this.degradationStrategy = degradationStrategy;
    }
    // ==================== 阻塞式聊天 ====================

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (!fallbackProperties.enabled()) {
            return doChat(request, null);
        }

        List<String> chain = fallbackChainProvider.resolve(request.model());
        String requestedModel = request.model();
        Exception lastException = null;

        for (int i = 0; i < chain.size(); i++) {
            String candidateModel = chain.get(i);
            boolean isFallback = i > 0;

            try {
                ChatRequest candidateRequest = isFallback
                        ? request.withModel(candidateModel)
                        : request;

                FallbackMeta meta = isFallback
                        ? new FallbackMeta(requestedModel, true)
                        : null;

                ChatResponse response = doChat(candidateRequest, meta);

                if (isFallback) {
                    log.info("Fallback succeeded: '{}' → '{}' (attempt {}/{})",
                            requestedModel, candidateModel, i + 1, chain.size());
                }
                return response;
            } catch (Exception e) {
                if (!fallbackEligibility.isEligible(e)) {
                    throw e;
                }
                lastException = e;
                log.warn("Chat attempt {}/{} failed for model '{}': {}",
                        i + 1, chain.size(), candidateModel, e.getMessage());
            }
        }

        log.error("All fallback attempts exhausted for model '{}', tried: {}",
                requestedModel, chain, lastException);
        throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
                "所有模型均不可用，请稍后重试（已尝试 " + chain.size() + " 个模型）");
    }
    // ==================== 流式聊天 ====================

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        if (!fallbackProperties.enabled()) {
            return doStream(request.model(), request);
        }

        List<String> chain = fallbackChainProvider.resolve(request.model());
        String requestedModel = request.model();

        return streamRetryHandler.execute(chain, 0, 0, modelId -> {
            boolean isFallback = !modelId.equals(requestedModel);
            ChatRequest candidateRequest = isFallback
                    ? request.withModel(modelId)
                    : request;

            if (isFallback) {
                log.info("Stream fallback: '{}' → '{}'", requestedModel, modelId);
            }

            return doStream(modelId, candidateRequest);
        });
    }

    // ==================== 单次调用核心 ====================

    /** 执行单次阻塞式聊天（无降级逻辑） */
    private ChatResponse doChat(ChatRequest request, FallbackMeta fallback) {
        ChatContext ctx = prepareContext(request);
        conversationHelper.ensureConversationExists(ctx.userId, ctx.conversationId, request.model());
        RequestContext cagCtx = buildCagContext(ctx, request);

        // Agent 模式：独立编排流程
        if (ctx.modeStrategy.isAgentMode()) {
            return doAgentChat(ctx, request, cagCtx);
        }

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy, cagCtx);

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();

        if (aiResponse != null) {
            usageTracker.recordUsage(ctx.conversationId, ctx.route.toCompositeId(), aiResponse, ctx.elapsed());
        } else {
            usageTracker.recordUsage(ctx.conversationId, ctx.route.toCompositeId(), ctx.elapsed());
        }

        Generation generation = (aiResponse != null) ? aiResponse.getResult() : null;
        String content = generation != null ? generation.getOutput().getText() : "";

        conversationHelper.saveMessagesAndNotify(ctx.conversationId, request.message(), content,
                ctx.route.toCompositeId(), aiResponse, ctx.elapsed());

        return new ChatResponse(ctx.route.toCompositeId(), content, ctx.rawConversationId, fallback);
    }

    /** 执行 Agent 模式聊天 */
    private ChatResponse doAgentChat(ChatContext ctx, ChatRequest request, RequestContext cagCtx) {
        long agentStart = System.currentTimeMillis();

        try {
            // 1. 构建 Agent Advisor 链（含意图分类）
            AgentChainResult agentResult = advisorChainFactory.buildAgentChain(
                ctx.conversationId, request, ctx.modeStrategy, cagCtx, ctx.userId);

            // 2. 构建 ChatClient 请求（跳过 spec.tools()、DB System Prompt、DB ModelParams）
            ChatClient.ChatClientRequestSpec spec = ctx.chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.advisors(agentResult.chain())
                    .param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                        ctx.conversationId));

            // 3. 执行 Agent 调用（ReAct 循环在 ToolCallAdvisor 内部完成）
            org.springframework.ai.chat.model.ChatResponse aiResponse = spec.call().chatResponse();

            // 4. 提取结果
            Generation generation = (aiResponse != null) ? aiResponse.getResult() : null;
            String content = generation != null ? generation.getOutput().getText() : "";
            long durationMs = System.currentTimeMillis() - agentStart;

            // 5. 记录 usage
            if (aiResponse != null) {
                usageTracker.recordUsage(ctx.conversationId, ctx.route.toCompositeId(), aiResponse, ctx.elapsed());
            } else {
                usageTracker.recordUsage(ctx.conversationId, ctx.route.toCompositeId(), ctx.elapsed());
            }

            // 6. 保存消息
            conversationHelper.saveMessagesAndNotify(ctx.conversationId, request.message(), content,
                ctx.route.toCompositeId(), aiResponse, ctx.elapsed());

            // 7. 构建 Agent 元数据
            Map<String, Object> agentMetadata = new LinkedHashMap<>();
            agentMetadata.put("intent", agentResult.intentResult().intent().name());
            agentMetadata.put("confidence", agentResult.intentResult().confidence());
            agentMetadata.put("retrievalRounds", agentResult.workspace().getRetrievalRound());
            agentMetadata.put("durationMs", durationMs);

            log.info("Agent chat completed: intent={}, rounds={}, duration={}ms, conversation={}",
                agentResult.intentResult().intent(), agentResult.workspace().getRetrievalRound(),
                durationMs, ctx.conversationId);

            return new ChatResponse(ctx.route.toCompositeId(), content, ctx.rawConversationId,
                null, agentMetadata);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - agentStart;
            log.error("Agent chat failed after {}ms: {}", durationMs, e.getMessage(), e);

            if (agentProperties.degradeOnFailure() && degradationStrategy.shouldDegrade(e)) {
                log.warn("Agent degradation triggered, falling back to standard chat");
                return doStandardFallbackChat(ctx, request);
            }
            throw e;
        }
    }

    /** Agent 降级：回退到标准 MULTI_TURN 模式 */
    private ChatResponse doStandardFallbackChat(ChatContext ctx, ChatRequest request) {
        RequestContext cagCtx = buildCagContext(ctx, request);

        // 降级为 MULTI_TURN 模式
        ChatModeStrategy multiTurnStrategy = modeRouter.route("MULTI_TURN");

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
            ctx.chatClient, ctx.route, request, ctx.conversationId, multiTurnStrategy, cagCtx);

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();

        Generation generation = (aiResponse != null) ? aiResponse.getResult() : null;
        String content = generation != null ? generation.getOutput().getText() : "";

        conversationHelper.saveMessagesAndNotify(ctx.conversationId, request.message(), content,
            ctx.route.toCompositeId(), aiResponse, ctx.elapsed());

        return new ChatResponse(ctx.route.toCompositeId(), content, ctx.rawConversationId);
    }

    /** 执行单次流式聊天（无降级逻辑） */
    private Flux<String> doStream(String modelId, ChatRequest request) {
        ChatContext ctx = prepareContext(request);
        conversationHelper.ensureConversationExists(ctx.userId, ctx.conversationId, request.model());
        RequestContext cagCtx = buildCagContext(ctx, request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy, cagCtx);

        StringBuilder collectedContent = new StringBuilder();
        final int maxContentLength = 1 << 20; // 1 MB
        AtomicBoolean usageRecorded = new AtomicBoolean(false);
        AtomicReference<org.springframework.ai.chat.model.ChatResponse> lastAiResponse = new AtomicReference<>();

        return requestSpec.stream()
                .chatResponse()
                .mapNotNull(aiResponse -> {
                    lastAiResponse.set(aiResponse);
                    Generation gen = aiResponse.getResult();
                    if (gen == null || gen.getOutput() == null) {
                        return null;
                    }
                    String text = gen.getOutput().getText();
                    if (text != null && collectedContent.length() < maxContentLength) {
                        collectedContent.append(text);
                    }
                    return text;
                })
                .doFinally(signal -> {
                    if (ctx.modeStrategy.isMemoryEnabled()) {
                        switch (signal) {
                            case ON_ERROR, CANCEL -> {
                                log.warn("Stream {} for conversation {}: collected {} chars",
                                        signal, ctx.conversationId, collectedContent.length());
                                conversationHelper.savePartialResponse(ctx.conversationId,
                                        collectedContent.toString());
                            }
                            case ON_COMPLETE -> {
                                log.debug("Stream completed for conversation: {}", ctx.conversationId);
                                conversationHelper.saveMessagesAndNotify(ctx.conversationId, request.message(),
                                        collectedContent.toString(), ctx.route.toCompositeId(),
                                        lastAiResponse.get(), ctx.elapsed());
                            }
                            default -> {}
                        }
                    }
                    if (usageRecorded.compareAndSet(false, true)) {
                        long duration = ctx.elapsed();
                        org.springframework.ai.chat.model.ChatResponse last = lastAiResponse.get();
                        if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
                            usageTracker.recordUsage(ctx.conversationId, ctx.route.toCompositeId(),
                                    last, duration);
                        } else {
                            usageTracker.recordUsage(ctx.conversationId, ctx.route.toCompositeId(),
                                    duration);
                        }
                    }
                });
    }
    // ==================== 内部辅助 ====================

    /** 预处理请求上下文：用户隔离、模式路由、模型路由 */
    private ChatContext prepareContext(ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ChatModeStrategy modeStrategy = modeRouter.route(request.mode());

        String rawConversationId = request.conversationId();
        if (rawConversationId == null) {
            rawConversationId = UuidV7.generateCompact();
        }
        String conversationId = ConversationIdUtil.buildIsolatedId(userId, rawConversationId);
        ModelRouter.Route route = modelRouter.resolve(request.model());
        ChatClient chatClient = registry.get(route.toCompositeId());

        log.debug("Chat request: userId={}, rawModel={}, route={}, mode={}, conversationId={}",
                userId, request.model(), route.toCompositeId(), modeStrategy.getMode(), conversationId);

        return new ChatContext(chatClient, route, conversationId, rawConversationId, modeStrategy, userId);
    }

    private RequestContext buildCagContext(ChatContext ctx, ChatRequest request) {
        if (!cagProperties.isEnabled()) {
            return null;
        }
        int msgCount = conversationHelper.getMessageCount(ctx.conversationId);
        return cagContextManager.buildContext(
                ctx.userId, ctx.conversationId, request.isRagEnabled(), msgCount);
    }

    /** 请求上下文 — 封装预处理结果 */
    private static class ChatContext {
        final ChatClient chatClient;
        final ModelRouter.Route route;
        final String conversationId;
        final String rawConversationId;
        final ChatModeStrategy modeStrategy;
        final Long userId;
        final long startTimeMs;

        ChatContext(ChatClient chatClient, ModelRouter.Route route,
                    String conversationId, String rawConversationId,
                    ChatModeStrategy modeStrategy, Long userId) {
            this.chatClient = chatClient;
            this.route = route;
            this.conversationId = conversationId;
            this.rawConversationId = rawConversationId;
            this.modeStrategy = modeStrategy;
            this.userId = userId;
            this.startTimeMs = System.currentTimeMillis();
        }

        long elapsed() {
            return System.currentTimeMillis() - startTimeMs;
        }
    }
}
