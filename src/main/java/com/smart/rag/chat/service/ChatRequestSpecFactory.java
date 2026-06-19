package com.smart.rag.chat.service;

import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ChatClient 请求规格构建工厂
 */
@Component
public class ChatRequestSpecFactory {

    private final ChatAdvisorChainFactory advisorChainFactory;
    private final SystemPromptService systemPromptService;
    private final LlmClientRegistry llmRegistry;
    private final ContextPromptInjector contextPromptInjector;

    public ChatRequestSpecFactory(ChatAdvisorChainFactory advisorChainFactory,
                                  SystemPromptService systemPromptService,
                                  LlmClientRegistry llmRegistry,
                                  ContextPromptInjector contextPromptInjector) {
        this.advisorChainFactory = advisorChainFactory;
        this.systemPromptService = systemPromptService;
        this.llmRegistry = llmRegistry;
        this.contextPromptInjector = contextPromptInjector;
    }

    public ChatClient.ChatClientRequestSpec createSpec(ChatClient chatClient,
                                                       String candidateId,
                                                       ChatRequest request,
                                                       String conversationId,
                                                       List<Advisor> chain,
                                                       RequestContext cagContext) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.advisors(chain)
                        .param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                                conversationId));

        if (advisorChainFactory.hasTools()) {
            spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());
        }

        // system = 纯 default.xml 静态基座（跨请求字节稳定 → 前缀缓存命中）；
        // CAG 段 + <<REF>> 检索块改由 RagContextAdvisor 以动态 SystemMessage 注入历史之后（design §2.8）。
        // cagContext 仍透传，但 CAG 不再在此注入静态基座。
        String systemPrompt = resolveSystemPrompt(candidateId);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }

        return spec;
    }

    private String resolveSystemPrompt(String candidateId) {
        String prompt = systemPromptService.getPrompt(candidateId);
        if (prompt == null || prompt.isBlank()) {
            CapabilityClient client = llmRegistry.find(candidateId);
            if (client != null) {
                prompt = systemPromptService.getPrompt(client.modelName());
            }
        }
        return prompt;
    }
}
