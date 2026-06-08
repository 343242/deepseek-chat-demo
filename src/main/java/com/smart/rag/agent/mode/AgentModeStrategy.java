package com.smart.rag.agent.mode;

import com.smart.rag.infrastructure.advisor.ConversationContextAdvisor;
import com.smart.rag.infrastructure.client.ChatClientRegistry;
import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.chat.mode.ChatMode;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.MultiTurnModeStrategy;
import com.smart.rag.chat.mode.AbstractModeStrategy;
import com.smart.rag.infrastructure.provider.ModelRouter;
import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.agent.advisor.AgentSystemPromptAdvisor;
import com.smart.rag.agent.config.AgentRagProperties;
import com.smart.rag.agent.guardrail.AgentDegradationStrategy;
import com.smart.rag.agent.guardrail.AgentGuardrails;
import com.smart.rag.agent.guardrail.TokenCountingChatModel;
import com.smart.rag.agent.intent.AgentIntent;
import com.smart.rag.agent.intent.IntentClassifier;
import com.smart.rag.agent.intent.IntentResult;
import com.smart.rag.agent.tool.callback.AgentToolCallbackFactory;
import com.smart.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.agent.workspace.ToolWorkspaceFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        // 过滤 Redis 历史中残留的 tool 消息，避免 DeepSeek "role 'tool' without preceding tool_calls" 报错
        chain.add(MessageChatMemoryAdvisor.builder(createFilteredMemory()).build());

        // tokenCountingModel 必须来自 guardrails -- 同一个实例用于护栏检查和 ChatClient 包装
        return ModeChainResult.agent(chain, intentResult, workspace,
            guardrails.getTokenCountingModel(), toolCallbacks);
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

            ChatClient countingClient = ChatClient.builder(result.tokenCountingModel()).build();
            ChatClient.ChatClientRequestSpec spec = countingClient.prompt()
                .user(ctx.request().message())
                .advisors(a -> a.advisors(result.chain())
                    .param(CONVERSATION_ID,
                        ctx.conversationId()));

            if (result.toolCallbacks() != null && result.toolCallbacks().length > 0) {
                spec.options(ToolCallingChatOptions.builder()
                    .toolCallbacks(result.toolCallbacks())
                    .build());
            }

            ChatResponse springResponse = spec.call()
                .chatResponse();

            String content = AbstractModeStrategy.extractContent(springResponse);
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
        throw new ClientException(ClientErrorCode.UNSUPPORTED_OPERATION,
            "Agent mode does not support streaming in this version. Use blocking call instead.");
    }

    private static Map<String, Object> buildAgentMetadata(ModeChainResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intent", result.intentResult().intent().name());
        metadata.put("confidence", result.intentResult().confidence());
        metadata.put("retrievalRounds", result.workspace().getRetrievalRound());
        return metadata;
    }

    /**
     * 创建过滤 tool 消息的 ChatMemory。
     * <p>
     * 核心问题：MessageWindowChatMemory 从 Redis 加载窗口时，可能包含之前 agent ReAct 循环
     * 产生的 ToolResponseMessage 和带 tool_calls 的 AssistantMessage。当窗口截断（trim）旧消息时，
     * 可能删掉 assistant+tool_calls 但保留对应的 ToolResponseMessage，导致孤立的 tool 消息。
     * DeepSeek/OpenAI API 要求 tool 消息必须紧跟在带 tool_calls 的 assistant 消息之后，
     * 孤立的 tool 消息会触发 400 错误。
     * <p>
     * 解决方案：在 ChatMemoryRepository 层面过滤，使 MessageWindowChatMemory 永远看不到
     * tool 相关消息。这样窗口截断不会产生孤立消息，仓库中也不会积累 tool 消息。
     */
    private ChatMemory createFilteredMemory() {
        ChatMemoryRepository delegate = infra.getChatMemoryRepository();
        ChatMemoryRepository filteredRepo = new ChatMemoryRepository() {
            @Override
            public @NonNull List<String> findConversationIds() {
                return delegate.findConversationIds();
            }

            @Override
            public @NonNull List<Message> findByConversationId(@NonNull String conversationId) {
                List<Message> raw = delegate.findByConversationId(conversationId);
                List<Message> filtered = raw.stream()
                    .filter(AgentModeStrategy::isAllowedInHistory)
                    .toList();
                List<Message> validated = validateMessageChain(filtered);
                if (raw.size() != validated.size()) {
                    log.warn("Filtered memory: raw={}, afterFilter={}, afterValidate={}, removed={} tool/orphan messages",
                        raw.size(), filtered.size(), validated.size(), raw.size() - validated.size());
                }
                return validated;
            }

            @Override
            public void saveAll(@NonNull String conversationId, @NonNull List<Message> messages) {
                List<Message> filtered = messages.stream()
                    .filter(AgentModeStrategy::isAllowedInHistory)
                    .toList();
                if (messages.size() != filtered.size()) {
                    log.info("Filtered memory save: input={}, saved={}, dropped={} tool messages",
                        messages.size(), filtered.size(), messages.size() - filtered.size());
                }
                delegate.saveAll(conversationId, filtered);
            }


            @Override
            public void deleteByConversationId(@NonNull String conversationId) {
                delegate.deleteByConversationId(conversationId);
            }
        };

        // 复用原始 maxMessages 配置，创建独立的 MessageWindowChatMemory
        // 每次请求创建新实例（请求级生命周期），窗口状态通过 filteredRepo 读写 Redis
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(filteredRepo)
            .maxMessages(20)
            .build();
    }

    /** 只保留 user 消息和无 tool_calls 的 assistant 消息，过滤掉所有 tool 相关消息 */
    private static boolean isAllowedInHistory(Message message) {
        if (message instanceof UserMessage) return true;
        if (message instanceof AssistantMessage am) {
            return am.getToolCalls() == null || am.getToolCalls().isEmpty();
        }
        return false;
    }

    /**
     * 验证消息链完整性：过滤掉因窗口截断而孤立的 ToolResponseMessage。
     * <p>
     * 正常的 tool 调用链：assistant(tool_calls) → tool_response → assistant(final)。
     * 如果 MessageWindowChatMemory 截断了 assistant(tool_calls)，其后的 tool_response 成为孤立消息。
     * 此方法扫描消息链，移除没有前置 assistant+tool_calls 的 tool 消息。
     */
    private static List<Message> validateMessageChain(List<Message> messages) {
        boolean hasPrecedingToolCalls = false;
        List<Message> validated = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am) {
                hasPrecedingToolCalls = am.getToolCalls() != null && !am.getToolCalls().isEmpty();
                validated.add(msg);
            } else if (msg instanceof org.springframework.ai.chat.messages.ToolResponseMessage) {
                if (hasPrecedingToolCalls) {
                    validated.add(msg);
                }
                // 孤立的 tool 消息：丢弃
                hasPrecedingToolCalls = false;
            } else {
                hasPrecedingToolCalls = false;
                validated.add(msg);
            }
        }
        return validated;
    }


}
