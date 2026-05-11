package com.demo.chat.chat.service.impl;

import com.demo.chat.chat.client.ChatClientRegistry;
import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.dto.ChatResponse;
import com.demo.chat.chat.fallback.ChatFallbackProperties;
import com.demo.chat.chat.fallback.FallbackChainResolver;
import com.demo.chat.chat.mode.ChatModeStrategy;
import com.demo.chat.chat.mode.ModeRouter;
import com.demo.chat.chat.provider.ModelRouter;
import com.demo.chat.chat.service.ChatRequestSpecFactory;
import com.demo.chat.chat.service.ChatService;
import com.demo.chat.chat.service.UsageService;
import com.demo.chat.chat.util.ConversationIdUtil;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.security.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;
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
 *   <li>流式（chatStream）— 连接阶段降级，首个 token 发出后不再切换</li>
 * </ul>
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final ChatClientRegistry registry;
    private final ModelRouter modelRouter;
    private final ModeRouter modeRouter;
    private final ChatRequestSpecFactory requestSpecFactory;
    private final UsageService usageService;
    private final ChatMemory chatMemory;
    private final ChatFallbackProperties fallbackProperties;
    private final FallbackChainResolver fallbackChainResolver;

    public ChatServiceImpl(ChatClientRegistry registry,
                           ModelRouter modelRouter,
                           ModeRouter modeRouter,
                           ChatRequestSpecFactory requestSpecFactory,
                           UsageService usageService,
                           ChatMemory chatMemory,
                           ChatFallbackProperties fallbackProperties,
                           FallbackChainResolver fallbackChainResolver) {
        this.registry = registry;
        this.modelRouter = modelRouter;
        this.modeRouter = modeRouter;
        this.requestSpecFactory = requestSpecFactory;
        this.usageService = usageService;
        this.chatMemory = chatMemory;
        this.fallbackProperties = fallbackProperties;
        this.fallbackChainResolver = fallbackChainResolver;
    }

    // ==================== 阻塞式聊天 ====================

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (!fallbackProperties.enabled()) {
            return doChat(request, false);
        }

        List<String> chain = fallbackChainResolver.resolve(request.model());
        Exception lastException = null;

        for (int i = 0; i < chain.size(); i++) {
            String candidateModel = chain.get(i);
            boolean isFallback = i > 0;

            try {
                ChatRequest candidateRequest = isFallback
                        ? request.withModel(candidateModel)
                        : request;

                ChatResponse response = doChat(candidateRequest, isFallback);

                if (isFallback) {
                    log.info("Fallback succeeded: '{}' → '{}' (attempt {}/{})",
                            request.model(), candidateModel, i + 1, chain.size());
                }
                return response;
            } catch (Exception e) {
                if (!fallbackChainResolver.isFallbackEligible(e)) {
                    throw e;
                }
                lastException = e;
                log.warn("Chat attempt {}/{} failed for model '{}': {}",
                        i + 1, chain.size(), candidateModel, e.getMessage());
            }
        }

        log.error("All {} fallback attempts exhausted for model '{}'",
                chain.size(), request.model(), lastException);
        throw new BusinessException(
                "所有模型均不可用，请稍后重试（已尝试 " + chain.size() + " 个模型）");
    }

    // ==================== 流式聊天 ====================

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        if (!fallbackProperties.enabled()) {
            return doStream(request);
        }

        List<String> chain = fallbackChainResolver.resolve(request.model());
        return streamWithFallback(request, chain, 0);
    }

    /**
     * 流式降级 — 递归构建降级链
     * <p>
     * 对每个候选模型创建延迟执行的 Flux，当前候选失败时切换到下一个。
     * 递归深度受 maxAttempts 限制（默认 3），不会栈溢出。
     *
     * @param request 原始请求（模型字段会在递归中被替换）
     * @param chain   降级候选链
     * @param index   当前尝试的索引
     * @return Flux 流
     */
    private Flux<String> streamWithFallback(ChatRequest request, List<String> chain, int index) {
        if (index >= chain.size()) {
            return Flux.error(new BusinessException(
                    "所有模型均不可用，请稍后重试（已尝试 " + chain.size() + " 个模型）"));
        }

        String candidateModel = chain.get(index);
        boolean isFallback = index > 0;
        ChatRequest candidateRequest = isFallback ? request.withModel(candidateModel) : request;

        return Flux.defer(() -> doStream(candidateRequest))
                .onErrorResume(e -> {
                    if (!fallbackChainResolver.isFallbackEligible(e)) {
                        return Flux.error(e);
                    }
                    log.warn("Stream attempt {}/{} failed for model '{}': {}",
                            index + 1, chain.size(), candidateModel, e.getMessage());
                    return streamWithFallback(request, chain, index + 1);
                });
    }

    // ==================== 单次调用核心 ====================

    /**
     * 执行单次阻塞式聊天（无降级逻辑）
     *
     * @param request   聊天请求
     * @param fallback  是否为降级调用
     * @return 聊天响应
     */
    private ChatResponse doChat(ChatRequest request, boolean fallback) {
        ChatContext ctx = prepareContext(request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy);

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();

        recordUsage(ctx.conversationId, ctx.route.toCompositeId(), aiResponse, ctx.elapsed());

        var generation = aiResponse.getResult();
        String content = (generation != null && generation.getOutput() != null)
                ? generation.getOutput().getText()
                : "";
        return new ChatResponse(ctx.route.toCompositeId(), content, request.conversationId(),
                fallback ? true : null);
    }

    /**
     * 执行单次流式聊天（无降级逻辑）
     *
     * @param request 聊天请求
     * @return SSE 文本流
     */
    private Flux<String> doStream(ChatRequest request) {
        ChatContext ctx = prepareContext(request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy);

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
                            case ON_COMPLETE -> log.debug("Stream completed for conversation: {}", ctx.conversationId);
                            // ON_SUBSCRIBE, ON_NEXT — 无需处理
                        }
                    }
                    if (usageRecorded.compareAndSet(false, true)) {
                        long duration = ctx.elapsed();
                        org.springframework.ai.chat.model.ChatResponse last = lastAiResponse.get();
                        if (last != null && last.getMetadata().getUsage() != null) {
                            Usage usage = last.getMetadata().getUsage();
                            usageService.recordUsage(
                                    ctx.conversationId, ctx.route.toCompositeId(),
                                    usage.getPromptTokens(), usage.getCompletionTokens(),
                                    usage.getTotalTokens(), duration);
                        } else {
                            usageService.recordUsage(
                                    ctx.conversationId, ctx.route.toCompositeId(),
                                    -1, -1, -1, duration);
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
        String conversationId = ConversationIdUtil.buildIsolatedId(userId, request.conversationId());
        ModelRouter.Route route = modelRouter.resolve(request.model());
        ChatClient chatClient = registry.get(route.toCompositeId());

        log.debug("Chat request: userId={}, rawModel={}, route={}, mode={}, conversationId={}",
                userId, request.model(), route.toCompositeId(), modeStrategy.getMode(), conversationId);

        return new ChatContext(chatClient, route, conversationId, modeStrategy);
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
     * 记录 Token 用量
     */
    private void recordUsage(String conversationId, String modelId,
                             org.springframework.ai.chat.model.ChatResponse aiResponse, long durationMs) {
        try {
            Usage usage = aiResponse.getMetadata().getUsage();
            if (usage != null) {
                usageService.recordUsage(
                        conversationId, modelId,
                        usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens(), durationMs);
                log.debug("Usage recorded: model={}, prompt={}, completion={}, total={}, duration={}ms",
                        modelId, usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens(), durationMs);
            }
        } catch (Exception e) {
            log.warn("Failed to record usage: {}", e.getMessage());
        }
    }

    /**
     * 请求上下文 — 封装预处理结果，避免方法间传大量参数
     */
    private static class ChatContext {
        final ChatClient chatClient;
        final ModelRouter.Route route;
        final String conversationId;
        final ChatModeStrategy modeStrategy;
        final long startTimeMs;

        ChatContext(ChatClient chatClient, ModelRouter.Route route,
                    String conversationId, ChatModeStrategy modeStrategy) {
            this.chatClient = chatClient;
            this.route = route;
            this.conversationId = conversationId;
            this.modeStrategy = modeStrategy;
            this.startTimeMs = System.currentTimeMillis();
        }

        long elapsed() {
            return System.currentTimeMillis() - startTimeMs;
        }
    }
}
