package com.smart.rag.chat.service.impl;

import com.smart.rag.infrastructure.ai.client.ChatClientRegistry;
import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.context.RequestContextManager;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.dto.ChatResponse;
import com.smart.rag.chat.dto.FallbackMeta;
import com.smart.rag.infrastructure.ai.fallback.ChatFallbackProperties;
import com.smart.rag.infrastructure.ai.fallback.FallbackChainProvider;
import com.smart.rag.infrastructure.ai.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.ai.fallback.StreamRetryHandler;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.infrastructure.ai.provider.ModelRouter;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatService;
import com.smart.rag.chat.service.ChatUsageTracker;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.common.uuid.UuidV7;
import com.smart.rag.conversation.util.ConversationIdUtil;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.security.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;

    public ChatServiceImpl(ChatClientRegistry registry,
                           ModelRouter modelRouter,
                           ModeRouter modeRouter,
                           ChatUsageTracker usageTracker,
                           ChatConversationHelper conversationHelper,
                           ChatFallbackProperties fallbackProperties,
                           FallbackChainProvider fallbackChainProvider,
                           FallbackEligibility fallbackEligibility,
                           StreamRetryHandler streamRetryHandler,
                           RequestContextManager cagContextManager,
                           CagProperties cagProperties) {
        this.registry = registry;
        this.modelRouter = modelRouter;
        this.modeRouter = modeRouter;
        this.usageTracker = usageTracker;
        this.conversationHelper = conversationHelper;
        this.fallbackProperties = fallbackProperties;
        this.fallbackChainProvider = fallbackChainProvider;
        this.fallbackEligibility = fallbackEligibility;
        this.streamRetryHandler = streamRetryHandler;
        this.cagContextManager = cagContextManager;
        this.cagProperties = cagProperties;
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
                    log.info("Fallback succeeded: '{}' -> '{}' (attempt {}/{})",
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
                log.info("Stream fallback: '{}' -> '{}'", requestedModel, modelId);
            }

            return doStream(modelId, candidateRequest);
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
        // 在 Flux 订阅时恢复调用方的 MDC 上下文（如 traceId），流结束时清理。
        // TODO: 启用 io.micrometer:context-propagation 后可改用 .contextWrite() 实现自动传播
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        Flux<String> flux = ctx.modeStrategy.executeStream(execCtx);
        if (parentMdc != null) {
            flux = flux.doOnSubscribe(s -> MDC.setContextMap(parentMdc))
                       .doFinally(signal -> MDC.clear());
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
