package com.demo.chat.chat.service;

import com.demo.chat.chat.context.ContextPromptInjector;
import com.demo.chat.chat.context.RequestContext;
import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.entity.ModelParams;
import com.demo.chat.chat.mode.ChatModeStrategy;
import com.demo.chat.chat.provider.ModelProvider;
import com.demo.chat.chat.provider.ModelRouter;
import com.demo.chat.chat.provider.ProviderRegistry;
import org.springframework.ai.chat.client.ChatClient;
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
 *   <li>System Prompt 解析（复合格式 → 纯 modelId 降级）</li>
 *   <li>模型参数热调整（复合格式 → 纯 modelId 降级）</li>
 * </ul>
 * <p>
 * 单一职责：只负责将请求参数转换为 ChatClientRequestSpec，不关心调用方式和结果处理。
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
     * 构建 ChatClientRequestSpec
     *
     * @param chatClient      目标 ChatClient
     * @param route           模型路由结果
     * @param request         聊天请求
     * @param conversationId  隔离后的对话 ID
     * @param modeStrategy    对话模式策略
     * @param cagContext      CAG 请求上下文（可能为 null，表示 CAG 未启用）
     * @return 已配置好的请求规格
     */
    public ChatClient.ChatClientRequestSpec createSpec(ChatClient chatClient,
                                                       ModelRouter.Route route,
                                                       ChatRequest request,
                                                       String conversationId,
                                                       ChatModeStrategy modeStrategy,
                                                       RequestContext cagContext) {
        List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors =
                advisorChainFactory.buildChain(conversationId, request, modeStrategy);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.advisors(advisors)
                        .param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                                conversationId));

        // Tool Calling
        if (advisorChainFactory.hasTools()) {
            spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());
        }

        // System Prompt（CAG 增强）
        String systemPrompt = resolveSystemPrompt(route);
        systemPrompt = contextPromptInjector.inject(systemPrompt, cagContext);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }

        // 模型参数
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
        return provider.buildOptions(params);
    }
}
