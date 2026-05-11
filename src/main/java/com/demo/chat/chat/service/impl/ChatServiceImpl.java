package com.demo.chat.chat.service.impl;

import com.demo.chat.chat.client.ChatClientRegistry;
import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.dto.ChatResponse;
import com.demo.chat.chat.mode.ChatModeStrategy;
import com.demo.chat.chat.mode.ModeRouter;
import com.demo.chat.chat.provider.ModelRouter;
import com.demo.chat.chat.service.ChatRequestSpecFactory;
import com.demo.chat.chat.service.ChatService;
import com.demo.chat.chat.service.UsageService;
import com.demo.chat.chat.util.ConversationIdUtil;
import com.demo.chat.security.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天服务（编排层）
 * <p>
 * 职责：请求预处理（路由、隔离） → 委托工厂构建请求 → 调用并处理响应。
 * 不再直接组装 Advisor 链或解析 System Prompt，这些逻辑分别委托给：
 * <ul>
 *   <li>{@link ChatRequestSpecFactory} — 请求规格构建（含 Advisor 链、Prompt、参数）</li>
 *   <li>{@link com.demo.chat.chat.service.ChatAdvisorChainFactory} — Advisor 链组装</li>
 * </ul>
 * <p>
 * 依赖数量从 12 降至 6，每个依赖都有明确的单一职责。
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

    public ChatServiceImpl(ChatClientRegistry registry,
                           ModelRouter modelRouter,
                           ModeRouter modeRouter,
                           ChatRequestSpecFactory requestSpecFactory,
                           UsageService usageService,
                           ChatMemory chatMemory) {
        this.registry = registry;
        this.modelRouter = modelRouter;
        this.modeRouter = modeRouter;
        this.requestSpecFactory = requestSpecFactory;
        this.usageService = usageService;
        this.chatMemory = chatMemory;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatContext ctx = prepareContext(request);

        ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
                ctx.chatClient, ctx.route, request, ctx.conversationId, ctx.modeStrategy);

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();

        recordUsage(ctx.conversationId, ctx.route.toCompositeId(), aiResponse, ctx.elapsed());

        var generation = aiResponse.getResult();
        String content = (generation != null && generation.getOutput() != null)
                ? generation.getOutput().getText()
                : "";
        return new ChatResponse(ctx.route.toCompositeId(), content, request.conversationId());
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
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

    // ===== 内部辅助 =====

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
