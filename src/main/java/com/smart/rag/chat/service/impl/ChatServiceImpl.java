package com.smart.rag.chat.service.impl;

import com.smart.rag.infrastructure.client.ChatClientRegistry;
import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.chat.dto.FallbackMeta;
import com.smart.rag.infrastructure.fallback.ChatFallbackProperties;
import com.smart.rag.infrastructure.fallback.FallbackChainProvider;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitOpenException;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.ProbeStreamHandler;
import com.smart.rag.infrastructure.fallback.StreamRetryHandler;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.infrastructure.provider.ModelRouter;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatService;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.MdcPropagator;
import com.smart.rag.chat.service.SseStreamBridge;
import com.smart.rag.chat.service.UserContextProvider;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.infrastructure.exception.errorcode.ErrorCode;
import com.smart.rag.common.util.UuidGeneratorUtil;
import com.smart.rag.common.util.ConversationIdUtil;
import com.smart.rag.infrastructure.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务（编排层）-- 请求预处理 -> 委托工厂构建请求 -> 调用并处理响应。
 * 集成兜底策略：阻塞式全链路降级，流式同模型重试后降级。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final ChatClientRegistry registry;
    private final ModelRouter modelRouter;
    private final ModeRouter modeRouter;
    private final ChatUsageTracker usageTracker;
    private final ChatConversationHelper conversationHelper;
    private final ChatFallbackProperties fallbackProperties;
    private final FallbackChainProvider fallbackChainProvider;
    private final FallbackEligibility fallbackEligibility;
    private final StreamRetryHandler streamRetryHandler;
    private final ProbeStreamHandler probeStreamHandler;
    private final ModelCircuitBreakerRegistry circuitBreakers;
    private final SseStreamBridge sseStreamBridge;
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;
    private final UserContextProvider userContextProvider;

    public ChatServiceImpl(ChatClientRegistry registry,
                           ModelRouter modelRouter,
                           ModeRouter modeRouter,
                           ChatUsageTracker usageTracker,
                           ChatConversationHelper conversationHelper,
                           ChatFallbackProperties fallbackProperties,
                           FallbackChainProvider fallbackChainProvider,
                           FallbackEligibility fallbackEligibility,
                           StreamRetryHandler streamRetryHandler,
                           @Nullable ProbeStreamHandler probeStreamHandler,
                           ModelCircuitBreakerRegistry circuitBreakers,
                           SseStreamBridge sseStreamBridge,
                           RequestContextManager cagContextManager,
                           CagProperties cagProperties,
                           UserContextProvider userContextProvider) {
        this.registry = registry;
        this.modelRouter = modelRouter;
        this.modeRouter = modeRouter;
        this.usageTracker = usageTracker;
        this.conversationHelper = conversationHelper;
        this.fallbackProperties = fallbackProperties;
        this.fallbackChainProvider = fallbackChainProvider;
        this.fallbackEligibility = fallbackEligibility;
        this.streamRetryHandler = streamRetryHandler;
        this.probeStreamHandler = probeStreamHandler;
        this.circuitBreakers = circuitBreakers;
        this.sseStreamBridge = sseStreamBridge;
        this.cagContextManager = cagContextManager;
        this.cagProperties = cagProperties;
        this.userContextProvider = userContextProvider;
    }
    // ==================== 阻塞式聊天 ====================

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (!fallbackProperties.enabled()) {
            return doChat(request, null);
        }

        List<String> chain = fallbackChainProvider.resolve(request.model(), request.enableThinking());
        String requestedModel = request.model();
        Exception lastException = null;

        for (int i = 0; i < chain.size(); i++) {
            String candidateModel = chain.get(i);
            boolean isFallback = i > 0;
            if (!circuitBreakers.isCallAllowed(candidateModel)) {
                log.warn("Skipping model '{}' because circuit breaker is open", candidateModel);
                continue;
            }

            try {
                ChatRequest candidateRequest = isFallback
                        ? request.withModel(candidateModel)
                        : request;

                FallbackMeta meta = isFallback
                        ? new FallbackMeta(requestedModel, true)
                        : null;

                ChatResponse response = doChat(candidateRequest, meta);
                circuitBreakers.recordSuccess(candidateModel);

                if (isFallback) {
                    log.info("Fallback succeeded: '{}' -> '{}' (attempt {}/{})",
                            requestedModel, candidateModel, i + 1, chain.size());
                }
                return response;
            } catch (Exception e) {
                if (!fallbackEligibility.isEligible(e)) {
                    throw e;
                }
                circuitBreakers.recordFailure(candidateModel);
                lastException = e;
                log.warn("Chat attempt {}/{} failed for model '{}': {}",
                        i + 1, chain.size(), candidateModel, e.getMessage());
            }
        }

        log.error("All fallback attempts exhausted for model '{}', tried: {}",
                requestedModel, chain, lastException);
        throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
                "所有模型均不可用，请稍后重试（已尝试 " + chain.size() + " 个模型）", lastException);
    }
    // ==================== 流式聊天 ====================

    @Override
    public SseEmitter chatStream(ChatRequest request) {
        Flux<String> stream = fallbackProperties.enabled()
                ? fallbackStream(request)
                : doStream(request.model(), request);
        return sseStreamBridge.bridge(stream);
    }

    private Flux<String> fallbackStream(ChatRequest request) {
        if (!fallbackProperties.enabled()) {
            return doStream(request.model(), request);
        }

        List<String> chain = fallbackChainProvider.resolve(request.model(), request.enableThinking());
        String requestedModel = request.model();

        return streamRetryHandler.execute(chain, 0, 0, new StreamRetryHandler.StreamFactory() {
            @Override
            public Flux<String> create(String modelId) {
                return buildProbeStream(modelId);
            }

            @Override
            public Flux<String> createDirect(String modelId) {
                return buildRawStream(modelId);
            }

            private Flux<String> buildRawStream(String modelId) {
                if (!circuitBreakers.isCallAllowed(modelId)) {
                    log.warn("Skipping stream model '{}' because circuit breaker is open", modelId);
                    return Flux.error(new ModelCircuitOpenException(modelId));
                }
                boolean isFallback = !modelId.equals(requestedModel);
                ChatRequest candidateRequest = isFallback ? request.withModel(modelId) : request;
                if (isFallback) {
                    log.info("Stream fallback: '{}' -> '{}'", requestedModel, modelId);
                }
                return doStream(modelId, candidateRequest)
                        .doOnComplete(() -> circuitBreakers.recordSuccess(modelId))
                        .doOnError(e -> {
                            if (fallbackEligibility.isEligible(e)) {
                                circuitBreakers.recordFailure(modelId);
                            }
                        })
                        .doFinally(signal -> {
                            if (signal == SignalType.CANCEL) {
                                circuitBreakers.releaseProbe(modelId);
                            }
                        });
            }

            private Flux<String> buildProbeStream(String modelId) {
                Flux<String> raw = buildRawStream(modelId);
                return probeStreamHandler != null
                        ? probeStreamHandler.wrapWithProbe(modelId, raw)
                        : raw;
            }
        });
    }

    // ==================== 单次调用核心 ====================

    /** 执行单次阻塞式聊天（无降级逻辑） */
    private ChatResponse doChat(ChatRequest request, FallbackMeta fallback) {
        ChatContext ctx = prepareContext(request);
        StrategyExecutionContext execCtx = buildExecutionContext(ctx, request);
        StrategyExecuteResult result = ctx.modeStrategy.execute(execCtx);
        return processResult(result, execCtx, fallback);
    }

    /** 执行单次流式聊天（无降级逻辑） */
    private Flux<String> doStream(String modelId, ChatRequest request) {
        ChatContext ctx = prepareContext(request);
        StrategyExecutionContext execCtx = buildExecutionContext(ctx, request);
        Map<String, String> parentMdc = MdcPropagator.capture();
        Flux<String> flux = ctx.modeStrategy.executeStream(execCtx);
        if (parentMdc != null) {
            flux = flux.doOnSubscribe(s -> MdcPropagator.restore(parentMdc))
                       .doFinally(signal -> MdcPropagator.clear());
        }
        return flux;
    }

    /**
     * 统一后处理 — 从 StrategyExecuteResult 完成后续操作。
     * rawConversationId → DTO 返回客户端；conversationId → 消息保存、usage 记录。
     */
    private ChatResponse processResult(StrategyExecuteResult result,
                                        StrategyExecutionContext ctx,
                                        FallbackMeta fallback) {
        String compositeModelId = ctx.route().toCompositeId();

        if (result.springAiResponse() != null) {
            usageTracker.recordUsage(ctx.conversationId(), compositeModelId,
                result.springAiResponse(), ctx.elapsed());
        } else {
            usageTracker.recordUsage(ctx.conversationId(), compositeModelId, ctx.elapsed());
        }

        conversationHelper.saveMessagesAndNotify(ctx.conversationId(),
            ctx.request().message(), result.content(),
            compositeModelId, result.springAiResponse(), ctx.elapsed());

        if (result.agentMetadata() != null) {
            return new ChatResponse(compositeModelId, result.content(),
                ctx.rawConversationId(), null, result.agentMetadata());
        }
        return new ChatResponse(compositeModelId, result.content(),
            ctx.rawConversationId(), fallback);
    }
    // ==================== 内部辅助 ====================

    /** 构建策略执行上下文：会话确保、CAG 上下文、统一入参 */
    private StrategyExecutionContext buildExecutionContext(ChatContext ctx, ChatRequest request) {
        conversationHelper.ensureConversationExists(ctx.userId, ctx.conversationId, request.model());
        RequestContext cagCtx = buildCagContext(ctx, request);
        return new StrategyExecutionContext(
            ctx.chatClient, ctx.route, request,
            ctx.conversationId, ctx.rawConversationId, ctx.userId, cagCtx,
            ctx.startTimeMs);
    }

    /** 预处理请求上下文：用户隔离、模式路由、模型路由 */
    private ChatContext prepareContext(ChatRequest request) {
        Long userId = userContextProvider.getCurrentUserId();
        ChatModeStrategy modeStrategy = modeRouter.route(request.mode());

        String rawConversationId = request.conversationId();
        if (rawConversationId == null) {
            rawConversationId = UuidGeneratorUtil.generateCompact();
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

    /** 请求上下文 -- 封装预处理结果 */
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
    }
}
