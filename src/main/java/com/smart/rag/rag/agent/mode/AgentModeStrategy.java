package com.smart.rag.rag.agent.mode;

import com.smart.rag.chat.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.client.ChatClientRegistry;
import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.chat.mode.ChatMode;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.MultiTurnModeStrategy;
import com.smart.rag.chat.mode.SimpleModeStrategy;
import com.smart.rag.chat.provider.ModelRouter;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.rag.agent.advisor.AgentSystemPromptAdvisor;
import com.smart.rag.rag.agent.config.AgentRagProperties;
import com.smart.rag.rag.agent.guardrail.AgentDegradationStrategy;
import com.smart.rag.rag.agent.guardrail.AgentGuardrails;
import com.smart.rag.rag.agent.guardrail.TokenCountingChatModel;
import com.smart.rag.rag.agent.intent.AgentIntent;
import com.smart.rag.rag.agent.intent.IntentClassifier;
import com.smart.rag.rag.agent.intent.IntentResult;
import com.smart.rag.rag.agent.tool.callback.AgentToolCallbackFactory;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.agent.workspace.ToolWorkspaceFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

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
    private final AgentDegradationStrategy degradationStrategy;
    private final ObjectProvider<MultiTurnModeStrategy> multiTurnProvider;

    public AgentModeStrategy(AdvisorInfrastructure infra,
                              IntentClassifier intentClassifier,
                              ToolWorkspaceFactory workspaceFactory,
                              AgentToolCallbackFactory agentToolCallbackFactory,
                              AgentRagProperties agentProperties,
                              ContextPromptInjector contextPromptInjector,
                              ChatClientRegistry chatClientRegistry,
                              AgentDegradationStrategy degradationStrategy,
                              ObjectProvider<MultiTurnModeStrategy> multiTurnProvider) {
        this.infra = infra;
        this.intentClassifier = intentClassifier;
        this.workspaceFactory = workspaceFactory;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
        this.agentProperties = agentProperties;
        this.contextPromptInjector = contextPromptInjector;
        this.chatClientRegistry = chatClientRegistry;
        this.degradationStrategy = degradationStrategy;
        this.multiTurnProvider = multiTurnProvider;
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
        // 无法获取 ChatModel，创建一个仅用字符估算的兜底计数器
        tokenCountingModel = new TokenCountingChatModel(Objects.requireNonNullElseGet(chatModel, NoOpChatModel::new));

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
        public @Nullable ChatResponse call(Prompt prompt) {
            return null;
        }

        @Override
        public @NonNull Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    @Override
    public StrategyExecuteResult execute(StrategyExecutionContext ctx) {
        try {
            AdvisorChainContext chainCtx = new AdvisorChainContext(
                ctx.conversationId(), ctx.request(), ctx.userId(),
                ctx.cagContext(), ctx.route());
            ModeChainResult result = buildAdvisorChain(chainCtx);

            ChatClient countingClient = null;
            if (result.tokenCountingModel() != null) {
                countingClient = ChatClient.builder(result.tokenCountingModel()).build();
            }
            ChatResponse springResponse = null;
            if (countingClient != null) {
                springResponse = countingClient.prompt()
                    .user(ctx.request().message())
                    .advisors(a -> a.advisors(result.chain())
                        .param(CONVERSATION_ID,
                            ctx.conversationId()))
                    .call()
                    .chatResponse();
            }

            String content = SimpleModeStrategy.extractContent(springResponse);
            Map<String, Object> agentMetadata = buildAgentMetadata(result);
            return StrategyExecuteResult.agent(springResponse, content, agentMetadata);

        } catch (Exception e) {
            if (degradationStrategy.shouldDegrade(e)) {
                log.warn("Agent degradation triggered, falling back to MULTI_TURN", e);
                return fallbackToMultiTurn(ctx);
            }
            throw e;
        }
    }

    private StrategyExecuteResult fallbackToMultiTurn(StrategyExecutionContext ctx) {
        MultiTurnModeStrategy multiTurnStrategy = multiTurnProvider.getIfAvailable();
        if (multiTurnStrategy == null) {
            throw new IllegalStateException("MultiTurnModeStrategy not available for agent fallback");
        }
        StrategyExecuteResult result = multiTurnStrategy.execute(ctx);
        Map<String, Object> degradedMeta = new LinkedHashMap<>();
        degradedMeta.put("agentDegraded", true);
        degradedMeta.put("degradedTo", "MULTI_TURN");
        return new StrategyExecuteResult(result.springAiResponse(), result.content(), degradedMeta);
    }

    @Override
    public Flux<String> executeStream(StrategyExecutionContext ctx) {
        throw new BusinessException(ErrorCode.UNSUPPORTED_OPERATION,
            "Agent mode does not support streaming in this version. Use blocking call instead.");
    }

    private static Map<String, Object> buildAgentMetadata(ModeChainResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.intentResult() != null) {
            metadata.put("intent", result.intentResult().intent().name());
        }
        if (result.intentResult() != null) {
            metadata.put("confidence", result.intentResult().confidence());
        }
        if (result.workspace() != null) {
            metadata.put("retrievalRounds", result.workspace().getRetrievalRound());
        }
        return metadata;
    }

}
