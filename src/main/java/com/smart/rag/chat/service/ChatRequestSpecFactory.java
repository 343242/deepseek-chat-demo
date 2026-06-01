package com.smart.rag.chat.service;

import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.entity.ModelParams;
import com.smart.rag.infrastructure.ai.provider.ModelProvider;
import com.smart.rag.infrastructure.ai.provider.ModelRouter;
import com.smart.rag.infrastructure.ai.provider.ProviderRegistry;
import com.smart.rag.infrastructure.ai.model.ModelOptionSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ChatClient 请求规格构建工厂
 * <p>
 * 封装 ChatClient.ChatClientRequestSpec 的组装逻辑，包括：
 * <ul>
 *   <li>用户消息设置</li>
 *   <li>Advisor 链挂载</li>
 *   <li>Tool Calling 绑定</li>
 *   <li>System Prompt 解析（复合格式 -> 纯 modelId 降级）</li>
 *   <li>模型参数热调整（复合格式 -> 纯 modelId 降级）</li>
 * </ul>
 * <p>
 * 单一职责：只负责将请求参数转换为 ChatClientRequestSpec，不关心调用方式和结果处理。
 * Agent 模式不调用此方法 -- 直接构建 spec，跳过全局 tools / DB system prompt / DB model options。
 * <p>
 * <b>耦合说明：</b>本工厂直接注入 {@link SystemPromptService} 和 {@link ModelParamsService}，
 * 而非通过参数传入。这是有意为之的设计决策 -- 工厂的职责是将请求参数 + 模型路由结果
 * 完整转换为可执行的 ChatClientRequestSpec，system prompt 和模型参数的解析是规格构建的
 * 固有步骤。将其作为参数传入会迫使调用方承担本不应关心的解析细节，增加调用方的耦合度，
 * 同时破坏工厂封装的完整性。
 */
@Component
public class ChatRequestSpecFactory {

    private final ChatAdvisorChainFactory advisorChainFactory;
    private final SystemPromptService systemPromptService;
    private final ModelParamsService modelParamsService;
    private final ProviderRegistry providerRegistry;
    private final ContextPromptInjector contextPromptInjector;

    public ChatRequestSpecFactory(ChatAdvisorChainFactory advisorChainFactory,
                                  SystemPromptService systemPromptService,
                                  ModelParamsService modelParamsService,
                                  ProviderRegistry providerRegistry,
                                  ContextPromptInjector contextPromptInjector) {
        this.advisorChainFactory = advisorChainFactory;
        this.systemPromptService = systemPromptService;
        this.modelParamsService = modelParamsService;
        this.providerRegistry = providerRegistry;
        this.contextPromptInjector = contextPromptInjector;
    }

    /**
     * 构建 ChatClientRequestSpec（Step 2 简化签名 — 直接传 chain）
     *
     * @param chatClient      目标 ChatClient
     * @param route           模型路由结果
     * @param request         聊天请求
     * @param conversationId  隔离后的对话 ID
     * @param chain           已构建好的 Advisor 链
     * @param cagContext      CAG 请求上下文（可能为 null，表示 CAG 未启用）
     * @return 已配置好的请求规格
     */
    public ChatClient.ChatClientRequestSpec createSpec(ChatClient chatClient,
                                                       ModelRouter.Route route,
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

        String systemPrompt = resolveSystemPrompt(route);
        systemPrompt = contextPromptInjector.inject(systemPrompt, cagContext);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }

        ChatOptions options = resolveChatOptions(route);
        if (options != null) {
            spec = spec.options(options);
        }

        return spec;
    }

    /**
     * 解析 System Prompt：复合格式优先，纯 modelId 降级
     */
    private String resolveSystemPrompt(ModelRouter.Route route) {
        String prompt = systemPromptService.getPrompt(route.toCompositeId());
        if (prompt == null || prompt.isBlank()) {
            prompt = systemPromptService.getPrompt(route.modelId());
        }
        return prompt;
    }

    /**
     * 解析模型参数为 ChatOptions：复合格式优先，纯 modelId 降级
     */
    private ChatOptions resolveChatOptions(ModelRouter.Route route) {
        ModelParams params = modelParamsService.getParams(route.toCompositeId());
        if (params == null) {
            params = modelParamsService.getParams(route.modelId());
        }
        if (params == null) {
            return null;
        }
        ModelProvider provider = providerRegistry.get(route.providerId());
        return provider.buildOptions(toOptionSettings(params));
    }

    private ModelOptionSettings toOptionSettings(ModelParams params) {
        return new ModelOptionSettings(
                params.getTemperature(),
                params.getMaxTokens(),
                params.getTopP(),
                params.getFrequencyPenalty(),
                params.getPresencePenalty());
    }
}
