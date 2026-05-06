package com.demo.deepseekchat.service;

import com.demo.deepseekchat.advisor.ConversationContextAdvisor;
import com.demo.deepseekchat.chat.ChatClientRegistry;
import com.demo.deepseekchat.model.dto.ChatRequest;
import com.demo.deepseekchat.model.dto.ChatResponse;
import com.demo.deepseekchat.model.entity.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天服务
 * <p>
 * 通过 ChatClientRegistry 获取模型对应的 ChatClient，
 * 通过 Spring 自动注入的 List&lt;Advisor&gt; 获取所有 Advisor，
 * 不直接依赖任何具体 Advisor 实现。
 * <p>
 * 集成功能：
 * - 动态 System Prompt（从 SystemPromptService 获取）
 * - 模型参数热调整（从 ModelParamsService 获取）
 * - Token 用量统计（记录到 UsageService）
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClientRegistry registry;
    private final ChatMemory chatMemory;
    private final List<Advisor> advisors;
    private final SystemPromptService systemPromptService;
    private final ModelParamsService modelParamsService;
    private final UsageService usageService;

    public ChatService(ChatClientRegistry registry, ChatMemory chatMemory,
                       List<Advisor> advisors,
                       SystemPromptService systemPromptService,
                       ModelParamsService modelParamsService,
                       UsageService usageService) {
        this.registry = registry;
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
        log.debug("Chat request: model={}, conversationId={}", request.model(), request.conversationId());

        ChatClient chatClient = registry.get(request.model());
        String conversationId = request.conversationId();
        long startTime = System.currentTimeMillis();

        // 构建请求
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                .user(request.message())
                .advisors(buildAdvisors(conversationId));

        // 注入动态 System Prompt
        String systemPrompt = systemPromptService.getPrompt(request.model());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestSpec = requestSpec.system(systemPrompt);
        }

        // 注入动态运行时参数
        ModelParams params = modelParamsService.getParams(request.model());
        if (params != null) {
            DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder();
            if (params.getTemperature() != null) optionsBuilder.temperature(params.getTemperature());
            if (params.getMaxTokens() != null) optionsBuilder.maxTokens(params.getMaxTokens());
            if (params.getTopP() != null) optionsBuilder.topP(params.getTopP());
            if (params.getFrequencyPenalty() != null) optionsBuilder.frequencyPenalty(params.getFrequencyPenalty());
            if (params.getPresencePenalty() != null) optionsBuilder.presencePenalty(params.getPresencePenalty());
            requestSpec = requestSpec.options(optionsBuilder.build());
        }

        org.springframework.ai.chat.model.ChatResponse aiResponse = requestSpec.call().chatResponse();
        long duration = System.currentTimeMillis() - startTime;

        // 记录 token 用量
        recordUsage(conversationId, request.model(), aiResponse, duration);

        return new ChatResponse(request.model(), aiResponse.getResult().getOutput().getText(), conversationId);
    }

    /**
     * 流式聊天，返回 SSE 事件流
     */
    public Flux<String> chatStream(ChatRequest request) {
        log.debug("Stream chat request: model={}, conversationId={}", request.model(), request.conversationId());

        ChatClient chatClient = registry.get(request.model());
        String conversationId = request.conversationId();
        long startTime = System.currentTimeMillis();

        // 构建请求
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                .user(request.message())
                .advisors(buildAdvisors(conversationId));

        // 注入动态 System Prompt
        String systemPrompt = systemPromptService.getPrompt(request.model());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestSpec = requestSpec.system(systemPrompt);
        }

        // 注入动态运行时参数
        ModelParams params = modelParamsService.getParams(request.model());
        if (params != null) {
            DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder();
            if (params.getTemperature() != null) optionsBuilder.temperature(params.getTemperature());
            if (params.getMaxTokens() != null) optionsBuilder.maxTokens(params.getMaxTokens());
            if (params.getTopP() != null) optionsBuilder.topP(params.getTopP());
            if (params.getFrequencyPenalty() != null) optionsBuilder.frequencyPenalty(params.getFrequencyPenalty());
            if (params.getPresencePenalty() != null) optionsBuilder.presencePenalty(params.getPresencePenalty());
            requestSpec = requestSpec.options(optionsBuilder.build());
        }

        // 流式调用 — token 用量在流结束后估算记录
        return requestSpec.stream()
                .content()
                .doOnComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    // 流式模式下 usage 可能不可用，记录耗时即可
                    usageService.recordUsage(conversationId, request.model(), 0, 0, 0, duration);
                });
    }

    /**
     * 构建 Advisor 列表
     * <p>
     * 执行顺序：
     * 1. ConversationContextAdvisor (order=-1) — 注入 conversationId 到 context
     * 2. 自定义 Advisors（RateLimitAdvisor order=0, ContentFilterAdvisor order=1）
     * 3. MessageChatMemoryAdvisor (order=2) — 对话记忆管理
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
            // 用量记录失败不应影响正常聊天
            log.warn("Failed to record usage: {}", e.getMessage());
        }
    }
}
