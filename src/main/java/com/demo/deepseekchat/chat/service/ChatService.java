package com.demo.deepseekchat.chat.service;

import com.demo.deepseekchat.chat.advisor.ConversationContextAdvisor;
import com.demo.deepseekchat.chat.client.ChatClientRegistry;
import com.demo.deepseekchat.chat.dto.ChatRequest;
import com.demo.deepseekchat.chat.dto.ChatResponse;
import com.demo.deepseekchat.chat.entity.ModelParams;
import com.demo.deepseekchat.chat.provider.ModelProvider;
import com.demo.deepseekchat.chat.provider.ModelRouter;
import com.demo.deepseekchat.chat.provider.ProviderRegistry;
import com.demo.deepseekchat.security.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
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
 * 集成功能：
 * - 动态 System Prompt（数据库 > XML 模板，带缓存）
 * - 模型参数热调整（从 ModelParamsService 获取，带缓存）
 * - Token 用量统计（记录到 UsageService）
 * - 用户级对话隔离（conversationId 自动绑定 userId）
 * <p>
 * 多 Provider 支持：
 * - 通过 ModelRouter 解析 model ID（支持 "provider/model" 复合格式和纯 modelId 向后兼容）
 * - 通过 ProviderRegistry 获取对应厂商的 Provider
 * - 通过 Provider.buildOptions() 构建厂商特定的 ChatOptions（不泄漏到本类）
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClientRegistry registry;
    private final ProviderRegistry providerRegistry;
    private final ModelRouter modelRouter;
    private final ChatMemory chatMemory;
    private final List<Advisor> advisors;
    private final SystemPromptService systemPromptService;
    private final ModelParamsService modelParamsService;
    private final UsageService usageService;

    public ChatService(ChatClientRegistry registry,
                       ProviderRegistry providerRegistry,
                       ModelRouter modelRouter,
                       ChatMemory chatMemory,
                       List<Advisor> advisors,
                       SystemPromptService systemPromptService,
                       ModelParamsService modelParamsService,
                       UsageService usageService) {
        this.registry = registry;
        this.providerRegistry = providerRegistry;
        this.modelRouter = modelRouter;
        this.chatMemory = chatMemory;
        this.advisors = advisors;
        this.systemPromptService = systemPromptService;
        this.modelParamsService = modelParamsService;
        this.usageService = usageService;
    }

    /**
     * 阻塞式聊天
     */
    public ChatResponse chat(ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedConversationId = buildIsolatedConversationId(userId, request.conversationId());

        log.debug("Chat request: userId={}, model={}, conversationId={}",
                userId, request.model(), isolatedConversationId);

        ChatClient chatClient = registry.get(request.model());
        long startTime = System.currentTimeMillis();

        ChatClient.ChatClientRequestSpec requestSpec = buildRequestSpec(
                chatClient, request.model(), request.message(), isolatedConversationId);

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();
        long duration = System.currentTimeMillis() - startTime;

        recordUsage(isolatedConversationId, request.model(), aiResponse, duration);

        return new ChatResponse(request.model(), aiResponse.getResult().getOutput().getText(),
                request.conversationId());
    }

    /**
     * 流式聊天，返回 SSE 事件流
     * <p>
     * 使用 StringBuilder 收集已生成的文本，在断连/错误时通过 doFinally
     * 确保部分回复也会保存到 ChatMemory，防止数据丢失。
     */
    public Flux<String> chatStream(ChatRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        String isolatedConversationId = buildIsolatedConversationId(userId, request.conversationId());

        log.debug("Stream chat request: userId={}, model={}, conversationId={}",
                userId, request.model(), isolatedConversationId);

        ChatClient chatClient = registry.get(request.model());
        long startTime = System.currentTimeMillis();

        ChatClient.ChatClientRequestSpec requestSpec = buildRequestSpec(
                chatClient, request.model(), request.message(), isolatedConversationId);

        // 收集已生成的文本，断连/取消时用于保存部分回复
        StringBuilder collectedContent = new StringBuilder();
        java.util.concurrent.atomic.AtomicBoolean usageRecorded = new java.util.concurrent.atomic.AtomicBoolean(false);

        return requestSpec.stream()
                .content()
                .doOnNext(chunk -> collectedContent.append(chunk))
                .doFinally(signal -> {
                    switch (signal) {
                        case ON_ERROR, CANCEL -> {
                            log.warn("Stream {} for conversation {}: collected {} chars",
                                    signal, isolatedConversationId, collectedContent.length());
                            savePartialResponse(isolatedConversationId, collectedContent.toString());
                        }
                        case ON_COMPLETE -> {
                            // 正常完成时不保存（MessageChatMemoryAdvisor 负责）
                            log.debug("Stream completed for conversation: {}", isolatedConversationId);
                        }
                        default -> {
                            savePartialResponse(isolatedConversationId, collectedContent.toString());
                        }
                    }
                    // 无论何种终止信号都记录用量（幂等）
                    if (usageRecorded.compareAndSet(false, true)) {
                        long duration = System.currentTimeMillis() - startTime;
                        usageService.recordUsage(isolatedConversationId, request.model(), -1, -1, -1, duration);
                    }
                });
    }

    /**
     * 保存流式中断时的部分回复到 ChatMemory
     * <p>
     * 仅在 doOnError 中调用，正常完成时 MessageChatMemoryAdvisor 的
     * aroundStream 回调会负责保存完整回复。
     */
    private void savePartialResponse(String conversationId, String content) {
        if (content != null && !content.isBlank()) {
            try {
                // 防重复：检查最后一条消息是否已是同内容的 assistant 回复
                var history = chatMemory.get(conversationId);
                if (!history.isEmpty()) {
                    var lastMsg = history.get(history.size() - 1);
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

    /**
     * 构建用户隔离的 conversationId
     * <p>
     * 格式: u_{userId}_{rawConversationId}
     * 确保不同用户的同名对话互不干扰
     */
    private String buildIsolatedConversationId(Long userId, String rawConversationId) {
        return "u_" + userId + "_" + rawConversationId;
    }

    /**
     * 构建统一的请求规格（system prompt + 动态参数 + advisor 链）
     * <p>
     * 通过 ModelRouter 解析 model ID，委托 Provider 构建 ChatOptions。
     * 本类不感知具体厂商的 ChatOptions 类型（DIP）。
     */
    private ChatClient.ChatClientRequestSpec buildRequestSpec(
            ChatClient chatClient, String rawModelId, String message, String conversationId) {

        // 解析 model ID 路由
        ModelRouter.Route route = modelRouter.resolve(rawModelId);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(message)
                .advisors(buildAdvisors(conversationId));

        // 注入动态 System Prompt（数据库 > XML 模板）
        String systemPrompt = systemPromptService.getPrompt(rawModelId);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }

        // 注入动态运行时参数 — 委托给对应 Provider 构建 ChatOptions
        ModelParams params = modelParamsService.getParams(route.modelId());
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
     * 构建 Advisor 列表
     */
    private List<Advisor> buildAdvisors(String conversationId) {
        List<Advisor> allAdvisors = new ArrayList<>();
        allAdvisors.add(new ConversationContextAdvisor(conversationId));
        allAdvisors.addAll(advisors);
        allAdvisors.add(MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(conversationId)
                .build());
        return allAdvisors;
    }

    /**
     * 记录 token 用量
     */
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
