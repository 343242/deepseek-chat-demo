package com.demo.chat.chat.service.impl;

import com.demo.chat.chat.advisor.ConversationContextAdvisor;
import com.demo.chat.chat.client.ChatClientRegistry;
import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.dto.ChatResponse;
import com.demo.chat.chat.entity.ModelParams;
import com.demo.chat.chat.mode.ChatModeStrategy;
import com.demo.chat.chat.mode.ModeRouter;
import com.demo.chat.chat.provider.ModelProvider;
import com.demo.chat.chat.provider.ModelRouter;
import com.demo.chat.chat.provider.ProviderRegistry;
import com.demo.chat.chat.service.*;
import com.demo.chat.chat.tool.ToolRegistry;
import com.demo.chat.chat.util.ConversationIdUtil;
import com.demo.chat.security.util.SecurityUtils;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天服务
 * <p>
 * 通过 ChatClientRegistry 获取模型对应的 ChatClient，
 * 通过 Spring 自动注入的 List&lt;Advisor&gt; 获取所有 Advisor，
 * 不直接依赖任何具体 Advisor 实现。
 * <p>
 * 支持两种对话模式（通过 ModeRouter 路由）：
 * <ul>
 *   <li>SIMPLE: 单轮直接调用 LLM，无记忆、无上下文注入</li>
 *   <li>MULTI_TURN: 多轮对话，自动维护会话记忆，支持 enableThinking</li>
 * </ul>
 * <p>
 * 其他集成功能：
 * - 动态 System Prompt（数据库 > XML 模板，带缓存）
 * - 模型参数热调整（从 ModelParamsService 获取，带缓存）
 * - Token 用量统计（记录到 UsageService）
 * - 用户级对话隔离（conversationId 自动绑定 userId）
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final ChatClientRegistry registry;
    private final ProviderRegistry providerRegistry;
    private final ModelRouter modelRouter;
    private final ModeRouter modeRouter;
    private final ChatMemory chatMemory;
    private final List<Advisor> advisors;
    private final ToolCallAdvisor toolCallAdvisor;
    private final ToolRegistry toolRegistry;
    private final SystemPromptService systemPromptService;
    private final ModelParamsService modelParamsService;
    private final UsageService usageService;
    private final RetrievalAugmentationAdvisor ragAdvisor;

    public ChatServiceImpl(ChatClientRegistry registry,
                           ProviderRegistry providerRegistry,
                           ModelRouter modelRouter,
                           ModeRouter modeRouter,
                           ChatMemory chatMemory,
                           List<Advisor> advisors,
                           ToolCallAdvisor toolCallAdvisor,
                           ToolRegistry toolRegistry,
                           SystemPromptService systemPromptService,
                           ModelParamsService modelParamsService,
                           UsageService usageService,
                           RetrievalAugmentationAdvisor ragAdvisor) {
        this.registry = registry;
        this.providerRegistry = providerRegistry;
        this.modelRouter = modelRouter;
        this.modeRouter = modeRouter;
        this.chatMemory = chatMemory;
        this.advisors = advisors;
        this.toolCallAdvisor = toolCallAdvisor;
        this.toolRegistry = toolRegistry;
        this.systemPromptService = systemPromptService;
        this.modelParamsService = modelParamsService;
        this.usageService = usageService;
        this.ragAdvisor = ragAdvisor;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ChatModeStrategy modeStrategy = modeRouter.route(request.mode());
        String isolatedConversationId = ConversationIdUtil.buildIsolatedId(userId, request.conversationId());

        ModelRouter.Route route = modelRouter.resolve(request.model());

        log.debug("Chat request: userId={}, rawModel={}, route={}, mode={}, conversationId={}",
                userId, request.model(), route.toCompositeId(), modeStrategy.getMode(), isolatedConversationId);

        ChatClient chatClient = registry.get(route.toCompositeId());
        long startTime = System.currentTimeMillis();

        ChatClient.ChatClientRequestSpec requestSpec = buildRequestSpec(
                chatClient, route, request, isolatedConversationId, modeStrategy);

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();
        long duration = System.currentTimeMillis() - startTime;

        recordUsage(isolatedConversationId, route.toCompositeId(), aiResponse, duration);

        return new ChatResponse(route.toCompositeId(), aiResponse.getResult().getOutput().getText(),
                request.conversationId());
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ChatModeStrategy modeStrategy = modeRouter.route(request.mode());
        String isolatedConversationId = ConversationIdUtil.buildIsolatedId(userId, request.conversationId());

        ModelRouter.Route route = modelRouter.resolve(request.model());

        log.debug("Stream chat request: userId={}, rawModel={}, route={}, mode={}, conversationId={}",
                userId, request.model(), route.toCompositeId(), modeStrategy.getMode(), isolatedConversationId);

        ChatClient chatClient = registry.get(route.toCompositeId());
        long startTime = System.currentTimeMillis();

        ChatClient.ChatClientRequestSpec requestSpec = buildRequestSpec(
                chatClient, route, request, isolatedConversationId, modeStrategy);

        StringBuilder collectedContent = new StringBuilder();
        java.util.concurrent.atomic.AtomicBoolean usageRecorded = new java.util.concurrent.atomic.AtomicBoolean(false);

        AtomicReference<org.springframework.ai.chat.model.ChatResponse> lastAiResponse = new AtomicReference<>();

        return requestSpec.stream()
                .chatResponse()
                .mapNotNull(aiResponse -> {
                    lastAiResponse.set(aiResponse);
                    aiResponse.getResult();
                    String text = aiResponse.getResult().getOutput().getText();
                    collectedContent.append(text);
                    return text;
                })
                .doFinally(signal -> {
                    // SIMPLE 模式不需要保存部分响应
                    if (modeStrategy.isMemoryEnabled()) {
                        switch (signal) {
                            case ON_ERROR, CANCEL -> {
                                log.warn("Stream {} for conversation {}: collected {} chars",
                                        signal, isolatedConversationId, collectedContent.length());
                                savePartialResponse(isolatedConversationId, collectedContent.toString());
                            }
                            case ON_COMPLETE -> log.debug("Stream completed for conversation: {}", isolatedConversationId);
                            default -> savePartialResponse(isolatedConversationId, collectedContent.toString());
                        }
                    }
                    if (usageRecorded.compareAndSet(false, true)) {
                        long duration = System.currentTimeMillis() - startTime;
                        org.springframework.ai.chat.model.ChatResponse last = lastAiResponse.get();
                        if (last != null && last.getMetadata().getUsage() != null) {
                            Usage usage = last.getMetadata().getUsage();
                            usageService.recordUsage(
                                    isolatedConversationId, route.toCompositeId(),
                                    usage.getPromptTokens(), usage.getCompletionTokens(),
                                    usage.getTotalTokens(), duration);
                        } else {
                            usageService.recordUsage(
                                    isolatedConversationId, route.toCompositeId(),
                                    -1, -1, -1, duration);
                        }
                    }
                });
    }

    private void savePartialResponse(String conversationId, String content) {
        if (content != null && !content.isBlank()) {
            try {
                var history = chatMemory.get(conversationId);
                if (!history.isEmpty()) {
                    var lastMsg = history.getLast();
                    if (lastMsg instanceof AssistantMessage am
                            && content.equals(am.getText())) {
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

    private ChatClient.ChatClientRequestSpec buildRequestSpec(
            ChatClient chatClient, ModelRouter.Route route,
            ChatRequest request, String conversationId,
            ChatModeStrategy modeStrategy) {

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(request.message())
                .advisors(buildAdvisors(conversationId, request, modeStrategy));

        if (toolRegistry.hasTools()) {
            spec = spec.tools((Object) toolRegistry.getToolCallbacks());
        }

        String systemPrompt = systemPromptService.getPrompt(route.toCompositeId());
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = systemPromptService.getPrompt(route.modelId());
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }

        ModelParams params = modelParamsService.getParams(route.toCompositeId());
        if (params == null) {
            params = modelParamsService.getParams(route.modelId());
        }
        if (params != null) {
            ModelProvider provider = providerRegistry.get(route.providerId());
            ChatOptions options = provider.buildOptions(params);
            if (options != null) {
                spec = spec.options(options);
            }
        }

        return spec;
    }

    /**
     * 根据对话模式组装不同的 Advisor 链
     * <p>
     * SIMPLE: RateLimit → ContentFilter → [RAG] → [ToolCall]
     * MULTI_TURN: ConversationContext → RateLimit → ContentFilter → [RAG] → [ToolCall] → Memory
     */
    private List<Advisor> buildAdvisors(String conversationId, ChatRequest request,
                                        ChatModeStrategy modeStrategy) {
        List<Advisor> allAdvisors = new ArrayList<>();

        // MULTI_TURN 模式：注入 conversationId 到 Advisor context
        if (modeStrategy.isContextEnabled()) {
            allAdvisors.add(new ConversationContextAdvisor(conversationId));
        }

        // 通用 Advisor（限流 + 内容安全）
        allAdvisors.addAll(advisors);

        // RAG（按请求启用，与模式无关）
        if (request.isRagEnabled()) {
            allAdvisors.add(ragAdvisor);
            log.debug("RAG advisor enabled for conversation: {}", conversationId);
        }

        // Tool Calling
        if (toolRegistry.hasTools()) {
            allAdvisors.add(toolCallAdvisor);
        }

        // MULTI_TURN 模式：追加 Memory Advisor
        if (modeStrategy.isMemoryEnabled()) {
            allAdvisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }

        return allAdvisors;
    }

    private void recordUsage(String conversationId, String modelId,
                             org.springframework.ai.chat.model.ChatResponse aiResponse, long durationMs) {
        try {
            Usage usage = aiResponse.getMetadata().getUsage();
            if (usage != null) {
                usageService.recordUsage(
                        conversationId, modelId,
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens(),
                        durationMs
                );
                log.debug("Usage recorded: model={}, prompt={}, completion={}, total={}, duration={}ms",
                        modelId, usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens(), durationMs);
            }
        } catch (Exception e) {
            log.warn("Failed to record usage: {}", e.getMessage());
        }
    }
}
