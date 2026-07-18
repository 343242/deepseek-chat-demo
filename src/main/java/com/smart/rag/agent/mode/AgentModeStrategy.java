package com.smart.rag.agent.mode;

import com.smart.rag.infrastructure.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.mode.ChatMode;
import com.smart.rag.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.MultiTurnModeStrategy;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.adapter.ChatModelAdapter;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.mode.AdvisorChainContext;
import com.smart.rag.chat.service.AdvisorInfrastructure;
import com.smart.rag.chat.service.ChatConversationHelper;
import com.smart.rag.chat.service.ChatMessagePublisher;
import com.smart.rag.mode.ModeChainResult;
import com.smart.rag.chat.service.StreamCompletionHelper;
import com.smart.rag.chat.service.PromptLoaderService;
import com.smart.rag.mode.StreamResult;
import com.smart.rag.mode.StrategyExecuteResult;
import com.smart.rag.mode.StrategyExecutionContext;
import com.smart.rag.agent.advisor.AgentSystemPromptAdvisor;
import com.smart.rag.agent.event.AgentEventStore;
import com.smart.rag.agent.event.payload.GuardrailTriggeredPayload;
import com.smart.rag.agent.event.payload.IntentClassifiedPayload;
import com.smart.rag.agent.config.AgentRagProperties;
import com.smart.rag.agent.guardrail.AgentDegradationStrategy;
import com.smart.rag.agent.guardrail.AgentGuardrails;
import com.smart.rag.agent.guardrail.GuardrailEnforcingToolCallAdvisor;
import com.smart.rag.agent.guardrail.GuardrailHardStopException;
import com.smart.rag.agent.guardrail.TokenCountingChatModel;
import com.smart.rag.mode.AgentIntent;
import com.smart.rag.mode.ModeSupport;
import com.smart.rag.agent.intent.IntentClassifier;
import com.smart.rag.mode.IntentResult;
import com.smart.rag.agent.tool.callback.AgentToolCallbackFactory;
import com.smart.rag.rag.retrieval.RetrievedDocument;
import com.smart.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.agent.workspace.ToolWorkspaceFactory;
import com.smart.rag.mode.Reference;
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
    private final PromptLoaderService promptLoaderService;
    private final LlmClientRegistry llmRegistry;
    private final AgentDegradationStrategy degradationStrategy;
    private final ObjectProvider<MultiTurnModeStrategy> multiTurnProvider;
    private final ChatMessagePublisher chatMessagePublisher;
    private final ChatConversationHelper conversationHelper;
    private final AgentEventStore eventStore;

    public AgentModeStrategy(AdvisorInfrastructure infra,
                              IntentClassifier intentClassifier,
                              ToolWorkspaceFactory workspaceFactory,
                              AgentToolCallbackFactory agentToolCallbackFactory,
                              AgentRagProperties agentProperties,
                              ContextPromptInjector contextPromptInjector,
                              PromptLoaderService promptLoaderService,
                              LlmClientRegistry llmRegistry,
                              AgentDegradationStrategy degradationStrategy,
                              ObjectProvider<MultiTurnModeStrategy> multiTurnProvider,
                              ChatMessagePublisher chatMessagePublisher,
                              ChatConversationHelper conversationHelper,
                              AgentEventStore eventStore) {
        this.infra = infra;
        this.intentClassifier = intentClassifier;
        this.workspaceFactory = workspaceFactory;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
        this.agentProperties = agentProperties;
        this.contextPromptInjector = contextPromptInjector;
        this.promptLoaderService = promptLoaderService;
        this.llmRegistry = llmRegistry;
        this.degradationStrategy = degradationStrategy;
        this.multiTurnProvider = multiTurnProvider;
        this.chatMessagePublisher = chatMessagePublisher;
        this.conversationHelper = conversationHelper;
        this.eventStore = eventStore;
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
        // 记录意图分类事件（供 AgentEventLookupTool 查询历史 + 会话恢复快照）
        eventStore.recordIntentClassified(ctx.conversationId(), ctx.userId(),
            new IntentClassifiedPayload(intentResult.intent().name(), intentResult.confidence(),
                hashQuery(ctx.request().message())));

        // Step 4: 创建请求级 Workspace（传 conversationId 作为 sessionId，供 RAG 链路追踪关联）
        ToolWorkspace workspace = workspaceFactory.create(ctx.userId(), ctx.request().teamId(), ctx.conversationId());

        // Step 5: 根据意图创建 Tool 回调（闭包捕获 workspace）
        ToolCallback[] toolCallbacks = agentToolCallbackFactory.createToolCallbacks(
            intentResult.intent(), workspace);

        // 提前创建 guardrails（Step 6 GuardrailEnforcingToolCallAdvisor 每轮 check 需要；Step 7 SystemPrompt 也用）
        AgentGuardrails guardrails = createGuardrails(ctx.candidateId());

        // Step 6: 自建护栏强制的 ToolCallAdvisor（每轮 doBeforeStream/doBeforeCall 调 guardrails.check，
        // 到 STOP 抛 GuardrailHardStopException；不复用全局单例）。P4b 修复阻塞+流式 check no-op。
        if (toolCallbacks.length > 0) {
            DefaultToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(toolCallbacks)))
                .build();

            ToolCallAdvisor agentToolCallAdvisor = new GuardrailEnforcingToolCallAdvisor(
                toolCallingManager, 2, guardrails);

            chain.add(agentToolCallAdvisor);
            log.debug("Agent ToolCallAdvisor built: {} tools for intent {}",
                toolCallbacks.length, intentResult.intent());
        }

        // Step 7: AgentSystemPromptAdvisor -- 静态基座+意图（首位）+ 动态尾 CAG/中间答案/护栏（末尾）
        String staticPrompt = resolveAgentPrompt(intentResult.intent());
        String cagSegment = contextPromptInjector.cagSegment(ctx.cagContext());
        chain.add(new AgentSystemPromptAdvisor(intentResult.intent(), staticPrompt, cagSegment, workspace, guardrails));

        // Step 8: 对话记忆
        // 过滤 Redis 历史中残留的 tool 消息，避免 DeepSeek "role 'tool' without preceding tool_calls" 报错
        chain.add(MessageChatMemoryAdvisor.builder(createFilteredMemory()).build());

        // tokenCountingModel 必须来自 guardrails -- 同一个实例用于护栏检查和 ChatClient 包装
        return ModeChainResult.agent(chain, intentResult, workspace,
            guardrails.getTokenCountingModel(), toolCallbacks);
    }

    /**
     * 基于 candidateId 指向的模型构建 guardrails。
     */
    private AgentGuardrails createGuardrails(String candidateId) {
        ChatModel chatModel = null;
        ChatCapable chatCapable = llmRegistry.get(candidateId, ChatCapable.class);
        if (chatCapable != null) {
            chatModel = new ChatModelAdapter(chatCapable);
        }

        TokenCountingChatModel tokenCountingModel;
        tokenCountingModel = new TokenCountingChatModel(Objects.requireNonNullElseGet(chatModel, NoOpChatModel::new));

        long tokenLimit = (long) (128_000 * agentProperties.contextWindowRatio());

        return new AgentGuardrails(agentProperties, tokenCountingModel, tokenLimit);
    }

    /**
     * 根据意图选择 System Prompt 模板，叠加 default.xml 基座（覆盖全部 4 意图，design §2.11）。
     * <p>
     * v5：只返回静态（基座 + 意图）；CAG 上下文改由 {@link AgentSystemPromptAdvisor} 注入动态尾
     * （CAG 每请求变化，进静态会破坏前缀缓存）。
     */
    private String resolveAgentPrompt(AgentIntent intent) {
        String template = switch (intent) {
            case DIRECT_ANSWER -> agentProperties.directAnswerPrompt();
            case RETRIEVAL -> agentProperties.retrievalPrompt();
            case DEEP_RETRIEVAL -> agentProperties.deepRetrievalPrompt();
            case GENERAL_TOOL -> agentProperties.generalToolPrompt();
        };

        if (template == null || template.isBlank()) {
            template = "你是一个 AI 助手。请根据用户的问题提供准确的回答。";
        }

        String base = promptLoaderService.getDefaultPrompt();
        if (base != null && !base.isBlank()) {
            template = base + "\n\n" + template;
        }
        return template;
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
                ctx.cagContext(), ctx.candidateId());
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

            String content = ModeSupport.extractContent(springResponse);
            Map<String, Object> agentMetadata = buildAgentMetadata(result);
            List<Reference> references = buildReferences(result.workspace().getRetrievedDocs());
            return StrategyExecuteResult.agent(springResponse, content, agentMetadata, references);

        } catch (Exception e) {
            // 护栏硬中断（迭代/token 超限）：记录事件后走降级或重新抛出
            if (e instanceof GuardrailHardStopException gre) {
                eventStore.recordGuardrailTriggered(ctx.conversationId(), ctx.userId(),
                    new GuardrailTriggeredPayload(gre.getReason(), gre.getMessage(), "stop"));
            }
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
        return StrategyExecuteResult.agent(result.springAiResponse(), result.content(), degradedMeta, result.references());
    }

    @Override
    public StreamResult executeStream(StrategyExecutionContext ctx) {
        AdvisorChainContext chainCtx = new AdvisorChainContext(
            ctx.conversationId(), ctx.request(), ctx.userId(),
            ctx.cagContext(), ctx.candidateId());
        ModeChainResult result = buildAdvisorChain(chainCtx);

        // 同 execute：tokenCountingModel（design §0 #1），不用 ctx.chatClient()
        ChatClient countingClient = ChatClient.builder(result.tokenCountingModel()).build();
        ChatClient.ChatClientRequestSpec spec = countingClient.prompt()
            .user(ctx.request().message())
            .advisors(a -> a.advisors(result.chain())
                .param(CONVERSATION_ID, ctx.conversationId()));

        if (result.toolCallbacks() != null && result.toolCallbacks().length > 0) {
            spec.options(ToolCallingChatOptions.builder()
                .toolCallbacks(result.toolCallbacks())
                .build());
        }

        StringBuilder collectedContent = new StringBuilder();
        final int maxContentLength = 1 << 20;
        // .call()→.stream()：ToolCallAdvisor.adviseStream 驱动流式 ReAct（Poc6 验证 streamCount=2）。
        // 截断保护 + doFinally 落库（StreamCompletionHelper，与 SIMPLE/MULTI_TURN 逐字同语义）。
        Flux<String> content = spec.stream()
            .content()
            .doOnNext(text -> {
                if (text != null && collectedContent.length() < maxContentLength) {
                    int remaining = maxContentLength - collectedContent.length();
                    collectedContent.append(text, 0, Math.min(text.length(), remaining));
                }
            })
            .doOnError(e -> {
                // 流式护栏硬中断：记录事件（与 execute 的 catch 对称）
                if (e instanceof GuardrailHardStopException gre) {
                    eventStore.recordGuardrailTriggered(ctx.conversationId(), ctx.userId(),
                        new GuardrailTriggeredPayload(gre.getReason(), gre.getMessage(), "stop"));
                }
            })
            .doFinally(signal -> StreamCompletionHelper.onComplete(
                ctx, collectedContent.toString(), signal, chatMessagePublisher, conversationHelper));

        return new StreamResult(content, buildReferences(result.workspace().getRetrievedDocs()));
    }

    /** 从 workspace 检索文档构造引用映射（#n → chunkId/documentId/fileName/page），无检索时返回 null */
    private static List<Reference> buildReferences(List<RetrievedDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        List<Reference> refs = new ArrayList<>(docs.size());
        for (RetrievedDocument doc : docs) {
            refs.add(new Reference(doc.refNumber(), doc.chunkId(), doc.documentId(),
                doc.fileName(), doc.page()));
        }
        return refs;
    }

    /**
     * 对用户查询做 SHA-256 哈希并截断（脱敏），用于 IntentClassifiedPayload.rawQueryHash。
     * <p>
     * 不存原始查询文本，仅存哈希用于事件去重与关联，避免在事件表中沉淀敏感输入。
     */
    private static String hashQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16); // 64-bit 前缀，足够区分
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
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
