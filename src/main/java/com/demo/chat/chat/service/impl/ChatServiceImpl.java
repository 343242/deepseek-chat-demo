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
import com.demo.chat.chat.service.ChatRequestSpecFactory;
import com.demo.chat.chat.service.ChatService;
import com.demo.chat.chat.service.ChatUsageTracker;
import com.demo.chat.conversation.service.ConversationMessageService;
import com.demo.chat.conversation.entity.Message;
import com.demo.chat.common.uuid.UuidV7;
import com.demo.chat.conversation.util.ConversationIdUtil;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天服务（编排层）
 * <p>
 * 职责：请求预处理（路由、隔离）→ 委托工厂构建请求 → 调用并处理响应。
 * 集成兜底策略：主模型调用失败时自动降级到备选模型。
 * <p>
 * 兜底策略：
 * <ul>
 *   <li>阻塞式（chat）— 全链路降级，每次尝试独立，失败后立即切换</li>
 *   <li>流式（chatStream）— 同模型重试（maxRetries 次）→ 降级切换</li>
 * </ul>
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final ChatClientRegistry registry;
    private final ModelRouter modelRouter;
    private final ModeRouter modeRouter;
    private final ChatRequestSpecFactory requestSpecFactory;
    private final ChatUsageTracker usageTracker;
    private final ChatMemory chatMemory;
    private final ChatFallbackProperties fallbackProperties;
    private final FallbackChainProvider fallbackChainProvider;
    private final FallbackEligibility fallbackEligibility;
    private final StreamRetryHandler streamRetryHandler;
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;
    private final com.demo.chat.conversation.service.ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final TransactionTemplate transactionTemplate;

    public ChatServiceImpl(ChatClientRegistry registry,
                           ModelRouter modelRouter,
                           ModeRouter modeRouter,
                           ChatRequestSpecFactory requestSpecFactory,
                           ChatUsageTracker usageTracker,
                           ChatMemory chatMemory,
                           ChatFallbackProperties fallbackProperties,
                           FallbackChainProvider fallbackChainProvider,
                           FallbackEligibility fallbackEligibility,
                           StreamRetryHandler streamRetryHandler,
                           RequestContextManager cagContextManager,
                           CagProperties cagProperties,
                           com.demo.chat.conversation.service.ConversationService conversationService,
                           ConversationMessageService conversationMessageService,
                           TransactionTemplate transactionTemplate) {
        this.registry = registry;
        this.modelRouter = modelRouter;
        this.modeRouter = modeRouter;
        this.requestSpecFactory = requestSpecFactory;
        this.usageTracker = usageTracker;
        this.chatMemory = chatMemory;
        this.fallbackProperties = fallbackProperties;
        this.fallbackChainProvider = fallbackChainProvider;
        this.fallbackEligibility = fallbackEligibility;
        this.streamRetryHandler = streamRetryHandler;
        this.cagContextManager = cagContextManager;
        this.cagProperties = cagProperties;
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.transactionTemplate = transactionTemplate;
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

    /**
     * 执行单次阻塞式聊天（无降级逻辑）
     *
     * @param request  聊天请求
     * @param fallback 降级元数据，null 表示非降级
     * @return 聊天响应
     */
    private ChatResponse doChat(ChatRequest request, FallbackMeta fallback) {
        ChatContext ctx = prepareContext(request);
        ensureConversationExists(ctx, request);
        RequestContext cagCtx = buildCagContext(ctx, request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy, cagCtx);

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();

        recordUsage(ctx.conversationId, ctx.route.toCompositeId(), aiResponse, ctx.elapsed());

        Generation generation = null;
        if (aiResponse != null) {
            generation = aiResponse.getResult();
        }
        String content = generation != null
                ? generation.getOutput().getText()
                : "";

        // 写入业务消息记录 + 通知会话更新
        saveMessagesAndNotify(ctx, request.message(), content, ctx.route.toCompositeId(),
                aiResponse, ctx.elapsed());

        return new ChatResponse(ctx.route.toCompositeId(), content, ctx.rawConversationId, fallback);
    }

    /**
     * 执行单次流式聊天（无降级逻辑）
     *
     * @param modelId  当前模型 ID（用于日志）
     * @param request  聊天请求
     * @return SSE 文本流
     */
    private Flux<String> doStream(String modelId, ChatRequest request) {
        ChatContext ctx = prepareContext(request);
        ensureConversationExists(ctx, request);
        RequestContext cagCtx = buildCagContext(ctx, request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy, cagCtx);

        StringBuilder collectedContent = new StringBuilder();
        AtomicBoolean usageRecorded = new AtomicBoolean(false);
        AtomicReference<org.springframework.ai.chat.model.ChatResponse> lastAiResponse = new AtomicReference<>();

        return requestSpec.stream()
                .chatResponse()
                .mapNotNull(aiResponse -> {
                    lastAiResponse.set(aiResponse);
                    String text = aiResponse.getResult().getOutput().getText();
                    collectedContent.append(text);
                    return text;
                })
                .doFinally(signal -> {
                    if (ctx.modeStrategy.isMemoryEnabled()) {
                        switch (signal) {
                            case ON_ERROR, CANCEL -> {
                                log.warn("Stream {} for conversation {}: collected {} chars",
                                        signal, ctx.conversationId, collectedContent.length());
                                savePartialResponse(ctx.conversationId, collectedContent.toString());
                            }
                            case ON_COMPLETE -> {
                                log.debug("Stream completed for conversation: {}", ctx.conversationId);
                                // 写入业务消息记录 + 通知会话更新
                                saveMessagesAndNotify(ctx, request.message(),
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

    /**
     * 预处理请求上下文：用户隔离、模式路由、模型路由、获取 ChatClient
     */
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
     * 构建 CAG 上下文（上下文增强生成）
     * <p>
     * CAG 关闭时返回 null，下游（ChatRequestSpecFactory / ContextPromptInjector）
     * 收到 null 后不做任何增强，保持原有行为。
     *
     * @param ctx     请求上下文（含 userId 和隔离后的 conversationId）
     * @param request 聊天请求
     * @return RequestContext，CAG 未启用时返回 null
     */
    private RequestContext buildCagContext(ChatContext ctx, ChatRequest request) {
        if (!cagProperties.isEnabled()) {
            return null;
        }
        int msgCount = chatMemory.get(ctx.conversationId).size();
        return cagContextManager.buildContext(
                ctx.userId, ctx.conversationId, request.isRagEnabled(), msgCount);
    }

    /**
     * 确保会话记录存在（自动创建）
     */
    private void ensureConversationExists(ChatContext ctx, ChatRequest request) {
        try {
            conversationService.getOrCreate(ctx.userId, ctx.conversationId, request.model());
        } catch (DuplicateKeyException e) {
            // 并发创建冲突，唯一约束兜底，忽略
            log.debug("Conversation already exists (concurrent create): {}", ctx.conversationId);
        } catch (Exception e) {
            log.error("Failed to ensure conversation exists: conversationId={}", ctx.conversationId, e);
            throw e;
        }
    }

    /**
     * 保存业务消息记录并通知会话更新
     */
    /**
     * 保存业务消息记录并通知会话更新
     * <p>
     * 使用编程式事务保证 USER 消息 + ASSISTANT 消息 + 会话计数的原子性。
     * 事务失败时向上传播异常，调用方决定是否影响主流程。
     */
    private void saveMessagesAndNotify(ChatContext ctx, String userContent, String assistantContent,
                                       String modelId,
                                       org.springframework.ai.chat.model.ChatResponse aiResponse,
                                       long durationMs) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 写入 USER 消息
                Message userMsg = Message.userMessage(ctx.conversationId, null, userContent);
                conversationMessageService.saveMessage(userMsg);

                // 写入 ASSISTANT 消息
                int totalTokens = -1;
                if (aiResponse != null && aiResponse.getMetadata().getUsage() != null) {
                    Usage usage = aiResponse.getMetadata().getUsage();
                    totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : -1;
                }
                Message assistantMsg = Message.assistantMessage(
                        ctx.conversationId, userMsg.getId(), assistantContent,
                        modelId, totalTokens, durationMs);
                conversationMessageService.saveMessage(assistantMsg);

                // 通知会话更新计数和标题
                conversationService.onNewMessages(ctx.conversationId, userContent, 2);
            });
        } catch (Exception e) {
            // 消息持久化失败不影响已返回给用户的响应，但必须记录完整异常栈
            log.error("Failed to save message records: conversationId={}, model={}",
                    ctx.conversationId, modelId, e);
        }
    }

    /**
     * 保存流式部分响应（仅在流中断时调用）
     */
    private void savePartialResponse(String conversationId, String content) {
        if (content != null && !content.isBlank()) {
            try {
                var history = chatMemory.get(conversationId);
                if (!history.isEmpty()) {
                    var lastMsg = history.getLast();
                    if (lastMsg instanceof AssistantMessage am && content.equals(am.getText())) {
                        log.debug("MessageChatMemoryAdvisor already saved identical response, skipping");
                        return;
                    }
                }
                chatMemory.add(conversationId, new AssistantMessage(content));
                log.info("Saved partial stream response for conversation: {}", conversationId);
            } catch (Exception e) {
                log.warn("Failed to save partial stream response: {}", e.getMessage());
            }
        }
    }

    /**
     * 记录 Token 用量（委托给 ChatUsageTracker）
     */
    private void recordUsage(String conversationId, String modelId,
                             org.springframework.ai.chat.model.ChatResponse aiResponse, long durationMs) {
        usageTracker.recordUsage(conversationId, modelId, aiResponse, durationMs);
    }

    /**
     * 请求上下文 — 封装预处理结果，避免方法间传大量参数
     */
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
