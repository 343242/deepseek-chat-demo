package com.smart.rag.chat.service;

import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.entity.ModelParams;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.provider.ModelProvider;
import com.smart.rag.chat.provider.ModelRouter;
import com.smart.rag.chat.provider.ProviderRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

/**
 * ChatClient 请求规格构建工厂
 * <p>
 * 封装 ChatClient.ChatClientRequestSpec 的组装逻辑，包括：
 * <ul>
 *   <li>用户消息设置</li>
 *   <li>Advisor 链挂载</li>
 *   <li>Tool Calling 绑定（尊重 ModeChainResult.skipGlobalTools）</li>
 *   <li>System Prompt 解析（复合格式 -> 纯 modelId 降级，尊重 ModeChainResult.skipDbSystemPrompt）</li>
 *   <li>模型参数热调整（复合格式 -> 纯 modelId 降级，尊重 ModeChainResult.skipDbModelOptions）</li>
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
     * 构建 ChatClientRequestSpec（Phase 2 新签名 -- 含 userId 和 route）
     *
     * @param chatClient      目标 ChatClient
     * @param route           模型路由结果
     * @param request         聊天请求
     * @param conversationId  隔离后的对话 ID
     * @param modeStrategy    对话模式策略
     * @param cagContext      CAG 请求上下文（可能为 null，表示 CAG 未启用）
     * @param userId          用户 ID
     * @return 已配置好的请求规格
     */
    public ChatClient.ChatClientRequestSpec createSpec(ChatClient chatClient,
                                                       ModelRouter.Route route,
                                                       ChatRequest request,
                                                       String conversationId,
                                                       ChatModeStrategy modeStrategy,
                                                       RequestContext cagContext,
                                                       Long userId) {
        ModeChainResult result = advisorChainFactory.buildChain(
            conversationId, request, modeStrategy, cagContext, userId, route);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.advisors(result.chain())
                        .param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                                conversationId));

        // Tool Calling -- Agent 模式跳过（有自建 ToolCallAdvisor）
        if (!result.skipGlobalTools() && advisorChainFactory.hasTools()) {
            spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());
        }

        // System Prompt（CAG 增强）-- Agent 模式跳过（有 AgentSystemPromptAdvisor）
        if (!result.skipDbSystemPrompt()) {
            String systemPrompt = resolveSystemPrompt(route);
            systemPrompt = contextPromptInjector.inject(systemPrompt, cagContext);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
        }

        // 模型参数 -- Agent 模式跳过（使用自有模型配置）
        if (!result.skipDbModelOptions()) {
            ChatOptions options = resolveChatOptions(route);
            if (options != null) {
                spec = spec.options(options);
            }
        }

        return spec;
    }

    /**
     * 构建 ChatClientRequestSpec（旧签名 -- 向后兼容，内部提取 userId）
     *
     * @deprecated 使用 {@link #createSpec(ChatClient, ModelRouter.Route, ChatRequest, String, ChatModeStrategy, RequestContext, Long)} 代替
     */
    @Deprecated
    public ChatClient.ChatClientRequestSpec createSpec(ChatClient chatClient,
                                                       ModelRouter.Route route,
                                                       ChatRequest request,
                                                       String conversationId,
                                                       ChatModeStrategy modeStrategy,
                                                       RequestContext cagContext) {
        return createSpec(chatClient, route, request, conversationId, modeStrategy,
            cagContext, com.smart.rag.security.util.SecurityUtils.getCurrentUserId());
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
