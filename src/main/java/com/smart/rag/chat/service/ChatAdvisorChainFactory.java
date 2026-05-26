package com.smart.rag.chat.service;

import com.smart.rag.chat.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.context.ContextPromptInjector;
import com.smart.rag.chat.context.RequestContext;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.tool.ToolRegistry;
import com.smart.rag.rag.agent.advisor.AgentSystemPromptAdvisor;
import com.smart.rag.rag.agent.config.AgentRagProperties;
import com.smart.rag.rag.agent.intent.AgentIntent;
import com.smart.rag.rag.agent.intent.IntentClassifier;
import com.smart.rag.rag.agent.intent.IntentResult;
import com.smart.rag.rag.agent.tool.callback.AgentToolCallbackFactory;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.agent.workspace.ToolWorkspaceFactory;
import com.smart.rag.rag.config.RagAdvisorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Advisor 链构建工厂
 * <p>
 * 根据对话模式和请求参数，组装不同的 Advisor 链。
 * <ul>
 *   <li>SIMPLE: RateLimit → ContentFilter → [RAG] → [ToolCall]</li>
 *   <li>MULTI_TURN: ConversationContext → RateLimit → ContentFilter → [RAG] → [ToolCall] → Memory</li>
 *   <li>AGENT: ConversationContext → RateLimit → ContentFilter → [Intent] → [AgentToolCall] → AgentSystemPrompt → Memory</li>
 * </ul>
 */
@Component
public class ChatAdvisorChainFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatAdvisorChainFactory.class);

    private final ChatMemory chatMemory;
    private final ObjectProvider<List<Advisor>> globalAdvisorsProvider;
    private final ObjectProvider<ToolCallAdvisor> toolCallAdvisorProvider;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final RagAdvisorFactory ragAdvisorFactory;

    // Agent-specific dependencies
    private final IntentClassifier intentClassifier;
    private final ToolWorkspaceFactory workspaceFactory;
    private final AgentToolCallbackFactory agentToolCallbackFactory;
    private final AgentRagProperties agentProperties;
    private final ContextPromptInjector contextPromptInjector;

    /** 缓存的全局 Advisor 列表（不可变，初始化后不再变化） */
    private volatile List<Advisor> cachedGlobalAdvisors;
    /** 缓存的工具可用状态 */
    private volatile Boolean cachedHasTools;
    /** 缓存的工具回调数组（不可变引用） */
    private volatile ToolCallback[] cachedToolCallbacks;

    public ChatAdvisorChainFactory(ChatMemory chatMemory,
                                   ObjectProvider<List<Advisor>> globalAdvisors,
                                   ObjectProvider<ToolCallAdvisor> toolCallAdvisor,
                                   ObjectProvider<ToolRegistry> toolRegistry,
                                   RagAdvisorFactory ragAdvisorFactory,
                                   IntentClassifier intentClassifier,
                                   ToolWorkspaceFactory workspaceFactory,
                                   AgentToolCallbackFactory agentToolCallbackFactory,
                                   AgentRagProperties agentProperties,
                                   ContextPromptInjector contextPromptInjector) {
        this.chatMemory = chatMemory;
        this.globalAdvisorsProvider = globalAdvisors;
        this.toolCallAdvisorProvider = toolCallAdvisor;
        this.toolRegistryProvider = toolRegistry;
        this.ragAdvisorFactory = ragAdvisorFactory;
        this.intentClassifier = intentClassifier;
        this.workspaceFactory = workspaceFactory;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
        this.agentProperties = agentProperties;
        this.contextPromptInjector = contextPromptInjector;
    }

    public boolean hasTools() {
        if (cachedHasTools == null) {
            synchronized (this) {
                if (cachedHasTools == null) {
                    cachedHasTools = toolRegistryProvider.getIfAvailable(ToolRegistry::empty).hasTools();
                }
            }
        }
        return cachedHasTools;
    }

    public ToolCallback[] getToolCallbacks() {
        if (cachedToolCallbacks == null) {
            synchronized (this) {
                if (cachedToolCallbacks == null) {
                    cachedToolCallbacks = toolRegistryProvider.getIfAvailable(ToolRegistry::empty).getToolCallbacks();
                }
            }
        }
        return cachedToolCallbacks;
    }

    private List<Advisor> getGlobalAdvisors() {
        if (cachedGlobalAdvisors == null) {
            synchronized (this) {
                if (cachedGlobalAdvisors == null) {
                    cachedGlobalAdvisors = List.copyOf(
                            globalAdvisorsProvider.getIfAvailable(Collections::emptyList));
                }
            }
        }
        return cachedGlobalAdvisors;
    }

    /**
     * 构建 SIMPLE/MULTI_TURN 模式的 Advisor 链
     */
    public List<Advisor> buildChain(String conversationId,
                                    ChatRequest request,
                                    ChatModeStrategy modeStrategy) {
        List<Advisor> chain = new ArrayList<>();

        if (modeStrategy.isContextEnabled()) {
            chain.add(new ConversationContextAdvisor(conversationId));
        }

        List<Advisor> globals = getGlobalAdvisors();
        chain.addAll(globals);

        if (request.isRagEnabled()) {
            Long userId = extractUserId();
            Long teamId = request.teamId();
            RetrievalAugmentationAdvisor ragAdvisor = ragAdvisorFactory.create(userId, teamId);
            chain.add(ragAdvisor);
        }

        if (hasTools()) {
            chain.add(toolCallAdvisorProvider.getObject());
        }

        if (modeStrategy.isMemoryEnabled()) {
            chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }

        return chain;
    }

    /**
     * 构建 AGENT 模式的 Advisor 链
     * <p>
     * 8 步组装：
     * <ol>
     *   <li>ConversationContextAdvisor — 上下文注入</li>
     *   <li>全局 Advisor（排除全局 ToolCallAdvisor）— RateLimit、ContentFilter</li>
     *   <li>意图分类 — IntentClassifier.classify()（阻塞式 LLM 调用）</li>
     *   <li>创建 Workspace — 请求级局部变量</li>
     *   <li>创建 Tool 回调 — 闭包捕获 Workspace</li>
     *   <li>自建 ToolCallAdvisor — 不复用全局单例</li>
     *   <li>AgentSystemPromptAdvisor — 动态 System Prompt + 每轮中间答案</li>
     *   <li>MessageChatMemoryAdvisor — 对话记忆</li>
     * </ol>
     *
     * @param conversationId 对话 ID
     * @param request        聊天请求
     * @param modeStrategy   对话模式策略
     * @param cagContext     CAG 请求上下文（可 null）
     * @param userId         用户 ID
     * @return Agent 链构建结果（含 chain、intentResult、workspace）
     */
    public AgentChainResult buildAgentChain(String conversationId,
                                            ChatRequest request,
                                            ChatModeStrategy modeStrategy,
                                            RequestContext cagContext,
                                            Long userId) {
        List<Advisor> chain = new ArrayList<>();

        // Step 1: ConversationContextAdvisor
        if (modeStrategy.isContextEnabled()) {
            chain.add(new ConversationContextAdvisor(conversationId));
        }

        // Step 2: 全局 Advisor（排除 ToolCallAdvisor，避免双重 Tool 调用处理）
        for (Advisor advisor : getGlobalAdvisors()) {
            if (!(advisor instanceof ToolCallAdvisor)) {
                chain.add(advisor);
            }
        }

        // Step 3: 意图分类（阻塞式 LLM 调用）
        IntentResult intentResult = intentClassifier.classify(request.message());
        log.info("Agent intent classified: intent={}, confidence={}, query='{}'",
            intentResult.intent(), intentResult.confidence(), request.message());

        // Step 4: 创建请求级 Workspace
        ToolWorkspace workspace = workspaceFactory.create(userId, request.teamId());

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

        // Step 7: AgentSystemPromptAdvisor — 动态 System Prompt + 每轮中间答案注入
        String mergedPrompt = resolveAgentPrompt(intentResult.intent(), cagContext);
        chain.add(new AgentSystemPromptAdvisor(intentResult.intent(), mergedPrompt, workspace));

        // Step 8: 对话记忆
        if (modeStrategy.isMemoryEnabled()) {
            chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }

        return new AgentChainResult(chain, intentResult, workspace);
    }

    /**
     * 根据意图选择 System Prompt 模板，并合并 CAG 上下文
     */
    private String resolveAgentPrompt(AgentIntent intent, RequestContext cagContext) {
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

    private Long extractUserId() {
        return com.smart.rag.security.util.SecurityUtils.getCurrentUserId();
    }
}
