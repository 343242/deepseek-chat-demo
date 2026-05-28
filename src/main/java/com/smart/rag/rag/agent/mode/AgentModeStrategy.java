package com.smart.rag.rag.agent.mode;

import com.smart.rag.chat.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.client.ChatClientRegistry;
import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.chat.mode.ChatMode;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.provider.ModelRouter;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.rag.agent.advisor.AgentSystemPromptAdvisor;
import com.smart.rag.rag.agent.config.AgentRagProperties;
import com.smart.rag.rag.agent.guardrail.AgentGuardrails;
import com.smart.rag.rag.agent.guardrail.TokenCountingChatModel;
import com.smart.rag.rag.agent.intent.AgentIntent;
import com.smart.rag.rag.agent.intent.IntentClassifier;
import com.smart.rag.rag.agent.intent.IntentResult;
import com.smart.rag.rag.agent.tool.callback.AgentToolCallbackFactory;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.agent.workspace.ToolWorkspaceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 模式策略 -- 意图识别、动态 Tool 选择、ReAct 循环
 * <p>
 * 8 步 Advisor 链组装：
 * <ol>
 *   <li>ConversationContextAdvisor -- 上下文注入</li>
 *   <li>全局 Advisor（排除全局 ToolCallAdvisor）-- RateLimit、ContentFilter</li>
 *   <li>意图分类 -- IntentClassifier.classify()（阻塞式 LLM 调用）</li>
 *   <li>创建 Workspace -- 请求级局部变量</li>
 *   <li>创建 Tool 回调 -- 闭包捕获 Workspace</li>
 *   <li>自建 ToolCallAdvisor -- 不复用全局单例</li>
 *   <li>AgentSystemPromptAdvisor -- 动态 System Prompt + 每轮中间答案</li>
 *   <li>MessageChatMemoryAdvisor -- 对话记忆</li>
 * </ol>
 */
@Component
public class AgentModeStrategy implements ChatModeStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentModeStrategy.class);

    // 门面（共享基础设施）
    private final AdvisorInfrastructure infra;

    // Agent 专用依赖
    private final IntentClassifier intentClassifier;
    private final ToolWorkspaceFactory workspaceFactory;
    private final AgentToolCallbackFactory agentToolCallbackFactory;
    private final AgentRagProperties agentProperties;
    private final ContextPromptInjector contextPromptInjector;
    private final ChatClientRegistry chatClientRegistry;

    public AgentModeStrategy(AdvisorInfrastructure infra,
                              IntentClassifier intentClassifier,
                              ToolWorkspaceFactory workspaceFactory,
                              AgentToolCallbackFactory agentToolCallbackFactory,
                              AgentRagProperties agentProperties,
                              ContextPromptInjector contextPromptInjector,
                              ChatClientRegistry chatClientRegistry) {
        this.infra = infra;
        this.intentClassifier = intentClassifier;
        this.workspaceFactory = workspaceFactory;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
        this.agentProperties = agentProperties;
        this.contextPromptInjector = contextPromptInjector;
        this.chatClientRegistry = chatClientRegistry;
    }

    @Override
    public ChatMode getMode() { return ChatMode.AGENT; }

    @Override
    public ModeChainResult buildAdvisorChain(AdvisorChainContext ctx) {
        List<Advisor> chain = new ArrayList<>();

        // Step 1: ConversationContextAdvisor
        chain.add(new ConversationContextAdvisor(ctx.conversationId()));

        // Step 2: 全局 Advisor（排除 ToolCallAdvisor，避免双重 Tool 调用处理）
        for (Advisor advisor : infra.getGlobalAdvisors()) {
            if (!(advisor instanceof ToolCallAdvisor)) {
                chain.add(advisor);
            }
        }

        // Step 3: 意图分类（阻塞式 LLM 调用）
        IntentResult intentResult = intentClassifier.classify(ctx.request().message());
        log.info("Agent intent classified: intent={}, confidence={}, queryLength={}",
            intentResult.intent(), intentResult.confidence(), ctx.request().message().length());

        // Step 4: 创建请求级 Workspace
        ToolWorkspace workspace = workspaceFactory.create(ctx.userId(), ctx.request().teamId());

        // Step 5: 根据意图创建 Tool 回调（闭包捕获 workspace）
        ToolCallback[] toolCallbacks = agentToolCallbackFactory.createToolCallbacks(
            intentResult.intent(), workspace);

        // Step 6: 自建独立 ToolCallAdvisor（不复用全局单例）
        if (toolCallbacks.length > 0) {
            DefaultToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(toolCallbacks)))
                .build();

            ToolCallAdvisor agentToolCallAdvisor = ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .disableMemory()
                .advisorOrder(2)
                .build();

            chain.add(agentToolCallAdvisor);
            log.debug("Agent ToolCallAdvisor built: {} tools for intent {}",
                toolCallbacks.length, intentResult.intent());
        }

        // Step 7: AgentSystemPromptAdvisor -- 动态 System Prompt + 每轮中间答案注入 + 护栏检查
        String mergedPrompt = resolveAgentPrompt(intentResult.intent(), ctx.cagContext());
        AgentGuardrails guardrails = createGuardrails(ctx.route());
        chain.add(new AgentSystemPromptAdvisor(intentResult.intent(), mergedPrompt, workspace, guardrails));

        // Step 8: 对话记忆
        chain.add(MessageChatMemoryAdvisor.builder(infra.getChatMemory()).build());

        // tokenCountingModel 必须来自 guardrails -- 同一个实例用于护栏检查和 ChatClient 包装
        return ModeChainResult.agent(chain, intentResult, workspace,
            guardrails.getTokenCountingModel());
    }

    /**
     * 基于 ctx.route() 指向的模型构建 guardrails。
     * <p>
     * 旧逻辑从 ChatClientRegistry 遍历取第一个可用模型（可能与本次请求模型不一致），
     * 现在通过 AdvisorChainContext.route 显式传入，确保 tokenCountingModel 包装的是正确的 ChatModel。
     */
    private AgentGuardrails createGuardrails(ModelRouter.Route route) {
        ChatModel chatModel = chatClientRegistry.getChatModel(route.toCompositeId());

        TokenCountingChatModel tokenCountingModel;
        if (chatModel != null) {
            tokenCountingModel = new TokenCountingChatModel(chatModel);
        } else {
            // 无法获取 ChatModel，创建一个仅用字符估算的兜底计数器
            tokenCountingModel = new TokenCountingChatModel(new NoOpChatModel());
        }

        // token 上限估算：128K 默认上下文窗口 x contextWindowRatio
        long tokenLimit = (long) (128_000 * agentProperties.contextWindowRatio());

        return new AgentGuardrails(agentProperties, tokenCountingModel, tokenLimit);
    }

    /**
     * 根据意图选择 System Prompt 模板，并合并 CAG 上下文
     */
    private String resolveAgentPrompt(AgentIntent intent, com.smart.rag.chat.context.RequestContext cagContext) {
        String template = switch (intent) {
            case DIRECT_ANSWER -> agentProperties.directAnswerPrompt();
            case RETRIEVAL -> agentProperties.retrievalPrompt();
            case DEEP_RETRIEVAL -> agentProperties.deepRetrievalPrompt();
            case GENERAL_TOOL -> agentProperties.generalToolPrompt();
        };

        if (template == null || template.isBlank()) {
            template = "你是一个 AI 助手。请根据用户的问题提供准确的回答。";
        }

        return contextPromptInjector.inject(template, cagContext);
    }

    /**
     * 兜底 ChatModel 实现 -- 仅用于 TokenCountingChatModel 字符估算场景
     */
    private static class NoOpChatModel implements ChatModel {
        @Override
        public org.springframework.ai.chat.model.ChatResponse call(
            org.springframework.ai.chat.prompt.Prompt prompt) {
            return null;
        }

        @Override
        public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
            org.springframework.ai.chat.prompt.Prompt prompt) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return null;
        }
    }

    @Override
    @Deprecated
    public boolean isMemoryEnabled() { return true; }

    @Override
    @Deprecated
    public boolean isAgentMode() { return true; }
}
