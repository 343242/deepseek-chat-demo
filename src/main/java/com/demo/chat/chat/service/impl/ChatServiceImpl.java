package com.demo.chat.chat.service.impl;

import com.demo.chat.chat.client.ChatClientRegistry;
import com.demo.chat.chat.context.CagProperties;
import com.demo.chat.chat.context.RequestContext;
import com.demo.chat.chat.context.RequestContextManager;
import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.dto.ChatResponse;
import com.demo.chat.chat.dto.FallbackMeta;
import com.demo.chat.chat.fallback.ChatFallbackProperties;
import com.demo.chat.chat.fallback.FallbackChainProvider;
import com.demo.chat.chat.fallback.FallbackEligibility;
import com.demo.chat.chat.fallback.StreamRetryHandler;
import com.demo.chat.chat.mode.ChatModeStrategy;
import com.demo.chat.chat.mode.ModeRouter;
import com.demo.chat.chat.provider.ModelRouter;
import com.demo.chat.chat.service.ChatConversationHelper;
import com.demo.chat.chat.service.ChatRequestSpecFactory;
import com.demo.chat.chat.service.ChatService;
import com.demo.chat.chat.service.ChatUsageTracker;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.common.uuid.UuidV7;
import com.demo.chat.conversation.util.ConversationIdUtil;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
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
                           CagProperties cagProperties) {
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

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy, cagCtx);

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();

        usageTracker.recordUsage(ctx.conversationId, ctx.route.toCompositeId(), aiResponse, ctx.elapsed());

        Generation generation = null;
        if (aiResponse != null) {
            generation = aiResponse.getResult();
        }
        String content = generation != null
                ? generation.getOutput().getText()
                : "";

        conversationHelper.saveMessagesAndNotify(ctx.conversationId, request.message(), content,
                ctx.route.toCompositeId(), aiResponse, ctx.elapsed());

        return new ChatResponse(ctx.route.toCompositeId(), content, ctx.rawConversationId, fallback);
    }

    /** 执行单次流式聊天（无降级逻辑） */
    private Flux<String> doStream(String modelId, ChatRequest request) {
        ChatContext ctx = prepareContext(request);
        conversationHelper.ensureConversationExists(ctx.userId, ctx.conversationId, request.model());
        RequestContext cagCtx = buildCagContext(ctx, request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy, cagCtx);

        StringBuilder collectedContent = new StringBuilder();
        // 流式响应内容上限：防止异常响应导致 OOM（正常回复不会超过 1MB）
        final int maxContentLength = 1 << 20; // 1 MB
        AtomicBoolean usageRecorded = new AtomicBoolean(false);
        AtomicReference<org.springframework.ai.chat.model.ChatResponse> lastAiResponse = new AtomicReference<>();

        return requestSpec.stream()
                .chatResponse()
                .mapNotNull(aiResponse -> {
                    lastAiResponse.set(aiResponse);
                    Generation gen = aiResponse.getResult();
                    if (gen == null || gen.getOutput() == null) {
                        return null; // 流结束标记或工具调用中间态，mapNotNull 自动跳过
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
                            // ON_SUBSCRIBE, ON_NEXT — 无需处理
                        }
                    }
                    if (usageRecorded.compareAndSet(false, true)) {
                        long duration = ctx.elapsed();
                        org.springframework.ai.chat.model.ChatResponse last = lastAiResponse.get();
                        if (last != null && last.getMetadata().getUsage() != null) {
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

        // 前端未传 conversationId 时，后端自动生成 UUIDv7
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

    /**
     * 构建 CAG 上下文。CAG 关闭时返回 null，下游收到 null 后不做增强。
     */
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
