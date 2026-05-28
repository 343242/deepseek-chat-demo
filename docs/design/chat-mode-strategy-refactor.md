# 对话模式策略模式重构

> 将 ChatModeStrategy 从「特征查询」升级为「行为委托」，消除 ChatAdvisorChainFactory 中的硬编码 if-else 分支。
> Step 1 目标：Advisor 链组装分支下沉到策略，新增模式无需修改链构建逻辑。
> 执行路径分流（`isAgentMode()` 分支、流式拒绝）留到 Step 2 下沉到策略。

## 1. 问题分析

### 1.1 现状

当前策略接口 `ChatModeStrategy` 只提供 **flag 方法**：

```java
public interface ChatModeStrategy {
    ChatMode getMode();
    boolean isMemoryEnabled();
    boolean isContextEnabled();
    boolean isThinkingEnabled();
    default boolean isAgentMode() { return false; }
}
```

消费方根据 flag 做 if-else 分支，共 **7 处 flag 检查**：

| 位置 | flag | 用途 |
|------|------|------|
| `ChatServiceImpl:179` | `isAgentMode()` | 执行路径分流 → doAgentChat() |
| `ChatServiceImpl:316` | `isMemoryEnabled()` | 流式部分响应保存 |
| `ChatAdvisorChainFactory:141` | `isContextEnabled()` | buildChain() 注入上下文 |
| `ChatAdvisorChainFactory:159` | `isMemoryEnabled()` | buildChain() 注入记忆 |
| `ChatAdvisorChainFactory:196` | `isContextEnabled()` | buildAgentChain() 注入上下文 |
| `ChatAdvisorChainFactory:242` | `isMemoryEnabled()` | buildAgentChain() 注入记忆 |
| `ChatRequestSpecFactory` | `isThinkingEnabled()` | ChatOptions 思考输出 |

### 1.2 违反的设计原则

- **OCP**：新增模式需修改 ChatServiceImpl + ChatAdvisorChainFactory
- **SRP**：ChatAdvisorChainFactory 同时承担「标准链构建」和「Agent 链构建」两个职责
- **策略不完整**：策略只声明「我需要什么」，不声明「我怎么组装」

### 1.3 thinkingEnabled 的特殊性

> **Codex Review 修正**：`thinkingEnabled` 经核实，当前 `isThinkingEnabled()` 在策略上定义但**从未被任何消费方实际调用**。`ChatRequest.isThinkingEnabled()` 存在但未接入 ChatOptions。这是一个**未实现的功能**，不属于本次重构范围。

`thinkingEnabled` 是 **per-request** 级别的开关，不是策略级别的常量。它影响的是 ChatOptions（模型参数），不是 Advisor 链的组成。

Spring AI 通过 `ChatOptions` 接口统一管理模型参数，支持 startup 默认值 + per-request 覆盖：

```
ChatOptions (portable interface)
├── getModel() / getTemperature() / getMaxTokens() / ...
├── copy() — 便于 per-request 覆盖
│
├── OpenAiChatOptions   — logitBias, seed, user, ...
├── AnthropicChatOptions — thinking, ...
└── ...其他模型实现各自扩展
```

`Prompt` 携带 per-request `ChatOptions`，框架自动与 startup 默认值合并：

```java
// 通过 Prompt 的 ChatOptions per-request 覆盖
Prompt prompt = new Prompt(messages, chatOptions);
```

**决策**：`thinkingEnabled` 的实现属于独立功能，由 `ModelProvider.buildOptions()` 根据 `ChatRequest` 请求参数构建对应的 `ChatOptions`（portable 接口，不绑定具体模型实现）。不在本次策略模式重构范围内，重构完成后作为单独 feature 追加。

## 2. 设计方案

### 2.1 架构总览

```
                    ChatRequest (mode=AGENT)
                           │
                           ▼
                    ┌─ ModeRouter ─┐
                    │  (路由分发)   │
                    └──────┬───────┘
                           │ strategy
              ┌────────────┼────────────────┐
              ▼            ▼                ▼
     SimpleModeStrategy  MultiTurnModeStrategy  AgentModeStrategy
     (注入基础服务)      (注入基础+记忆)       (注入全套 Agent 服务)
              │            │                │
              └─────┬──────┘                │
                    ▼                       ▼
          AdvisorInfrastructure      Agent 专用依赖
          (门面：共享基础设施)       (IntentClassifier,
           全局 Advisor 缓存          ToolWorkspaceFactory, ...)
           ChatMemory 缓存
           RagAdvisorFactory
           ToolCallAdvisor
```

### 2.2 新增：ModeChainResult（统一返回类型 + 执行指示）

> **Codex Review R1 HIGH-1 修正**：Agent 路径需要返回 `intentResult`、`workspace`、`tokenCountingModel` 等元数据供 `doAgentChat()` 构建 TokenCountingChatClient 和提取 Agent 元信息。`buildAdvisorChain()` 不能只返回 `List<Advisor>`。

> **Codex Review R3 Issue-3 修正**：当前已有 `AgentChainResult`（chain, intentResult, workspace, tokenCountingModel），与 `ModeChainResult` 字段完全重复。`ModeChainResult` 是 `AgentChainResult` 的泛化版本，迁移完成后删除 `AgentChainResult`。

> **Codex Review R3 Issue-2 修正**：`createSpec()` 无条件绑定 `.tools()`、DB system prompt、model options，但 Agent 模式需要跳过这三者（ChatModeStrategy 注释已明确）。`ModeChainResult` 新增执行指示字段，让策略控制 `createSpec()` 行为。

```java
/**
 * 策略构建 Advisor 链的统一返回类型 + 执行指示。
 * 替代现有 AgentChainResult — 迁移完成后删除 AgentChainResult。
 *
 * 标准模式：仅 chain 非空，其余字段为 null/false。
 * Agent 模式：chain + agent 元数据 + skipXxx 执行指示。
 */
public record ModeChainResult(
    List<Advisor> chain,

    // Agent 元数据（nullable）
    IntentResult intentResult,
    ToolWorkspace workspace,
    TokenCountingChatModel tokenCountingModel,

    // 执行指示 — 控制 ChatRequestSpecFactory.createSpec() 行为
    boolean skipGlobalTools,         // Agent: true — 有自建 ToolCallAdvisor
    boolean skipDbSystemPrompt,      // Agent: true — 有 AgentSystemPromptAdvisor
    boolean skipDbModelOptions       // Agent: true — 使用自有模型配置
) {
    /** 标准模式的便捷工厂 — 不跳过任何 createSpec 步骤 */
    public static ModeChainResult standard(List<Advisor> chain) {
        return new ModeChainResult(chain, null, null, null, false, false, false);
    }

    /** Agent 模式的完整工厂 — 跳过全局 tools / DB system prompt / DB model options */
    public static ModeChainResult agent(List<Advisor> chain,
                                         IntentResult intentResult,
                                         ToolWorkspace workspace,
                                         TokenCountingChatModel tokenCountingModel) {
        return new ModeChainResult(chain, intentResult, workspace, tokenCountingModel,
            true, true, true);
    }
}
```

### 2.3 新增：AdvisorInfrastructure（门面服务）

> **Codex Review MEDIUM-5 修正**：当前 `ChatAdvisorChainFactory` 使用 `ObjectProvider` + volatile DCL 延迟初始化，而非直接注入 final 字段。`AdvisorInfrastructure` 保留相同语义。

从 `ChatAdvisorChainFactory` 提取共享基础设施，提供带缓存的统一访问入口：

```java
@Component
public class AdvisorInfrastructure {

    // 保留原有 ObjectProvider 类型 — 与当前 ChatAdvisorChainFactory 一致
    private final ObjectProvider<List<Advisor>> globalAdvisorsProvider;
    private final ObjectProvider<ChatMemory> chatMemoryProvider;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;       // 非 ToolCallback[]
    private final ObjectProvider<ToolCallAdvisor> toolCallAdvisorProvider;
    private final RagAdvisorFactory ragAdvisorFactory;

    // volatile + DCL 缓存 — 保留原有延迟初始化语义
    private volatile List<Advisor> cachedGlobalAdvisors;
    private volatile ToolCallback[] cachedToolCallbacks;
    private volatile Boolean cachedHasTools;

    public List<Advisor> getGlobalAdvisors() {
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

    public boolean hasTools() {
        if (cachedHasTools == null) {
            synchronized (this) {
                if (cachedHasTools == null) {
                    ToolRegistry registry = toolRegistryProvider.getIfAvailable(ToolRegistry::empty);
                    cachedHasTools = registry.hasTools();
                    cachedToolCallbacks = registry.getToolCallbacks();
                }
            }
        }
        return cachedHasTools;
    }

    public ToolCallback[] getToolCallbacks() {
        hasTools(); // 触发缓存初始化
        return cachedToolCallbacks;
    }

    public ChatMemory getChatMemory() { return chatMemoryProvider.getObject(); }
    public RagAdvisorFactory getRagAdvisorFactory() { return ragAdvisorFactory; }
    public ToolCallAdvisor getToolCallAdvisor() { return toolCallAdvisorProvider.getObject(); }
}
```

### 2.4 新增：AdvisorChainContext（轻量请求上下文）

> **Codex Review HIGH-2 修正**：`ChatRequestSpecFactory.createSpec()` 当前无 userId 参数，链构建内部通过 `extractUserId()` 获取。`AdvisorChainContext` 统一携带 userId，消除隐式依赖。

```java
/**
 * 策略构建链时的请求上下文。
 * 策略通过此对象获取请求数据，通过构造注入的 AdvisorInfrastructure 获取共享服务。
 */
public record AdvisorChainContext(
    String conversationId,
    ChatRequest request,
    Long userId,
    RequestContext cagContext,
    ModelRouter.Route route          // 供 Agent guardrails/tokenCountingModel 基于本次请求模型构建
) {}
```

### 2.5 修改：ChatModeStrategy 接口

增加行为方法，逐步废弃 flag 方法：

```java
public interface ChatModeStrategy {

    ChatMode getMode();

    /**
     * 构建该模式的 Advisor 链及模式相关元数据。
     * 每个策略自己决定链的组装方式、需要哪些 Advisor。
     * Agent 模式还会返回 intentResult、workspace、tokenCountingModel 等元数据。
     */
    ModeChainResult buildAdvisorChain(AdvisorChainContext ctx);

    // === 以下 flag 方法标记 @Deprecated，Step 2 移除 ===
    // 保留是为了 ChatServiceImpl 中少量非链构建的 flag 依赖
    @Deprecated
    boolean isMemoryEnabled();

    @Deprecated
    default boolean isAgentMode() { return false; }

    // isThinkingEnabled() 移除 — 未实现的功能，不属于本次重构范围
    // isContextEnabled() 移除 — 由 buildAdvisorChain 内部决定
}
```

### 2.6 修改：三个策略实现

#### SimpleModeStrategy

> **Codex Review HIGH-3 修正**：`AdvisorAutoConfiguration` 中有 `@Bean` 注册 SimpleModeStrategy。改为 `@Component` 后需删除对应 `@Bean` 方法。

```java
@Component
public class SimpleModeStrategy implements ChatModeStrategy {

    private final AdvisorInfrastructure infra;

    @Override
    public ModeChainResult buildAdvisorChain(AdvisorChainContext ctx) {
        List<Advisor> chain = new ArrayList<>();
        // SIMPLE: 无上下文注入、无记忆
        chain.addAll(infra.getGlobalAdvisors());

        if (ctx.request().isRagEnabled()) {
            chain.add(infra.getRagAdvisorFactory()
                .create(ctx.userId(), ctx.request().teamId()));
        }

        if (infra.hasTools()) {
            chain.add(infra.getToolCallAdvisor());
        }

        return ModeChainResult.standard(chain);
    }

    @Override @Deprecated
    public boolean isMemoryEnabled() { return false; }
}
```

#### MultiTurnModeStrategy

```java
@Component
public class MultiTurnModeStrategy implements ChatModeStrategy {

    private final AdvisorInfrastructure infra;

    @Override
    public ModeChainResult buildAdvisorChain(AdvisorChainContext ctx) {
        List<Advisor> chain = new ArrayList<>();
        // MULTI_TURN: 上下文注入 + 记忆
        chain.add(new ConversationContextAdvisor(ctx.conversationId()));
        chain.addAll(infra.getGlobalAdvisors());

        if (ctx.request().isRagEnabled()) {
            chain.add(infra.getRagAdvisorFactory()
                .create(ctx.userId(), ctx.request().teamId()));
        }

        if (infra.hasTools()) {
            chain.add(infra.getToolCallAdvisor());
        }

        chain.add(MessageChatMemoryAdvisor.builder(infra.getChatMemory()).build());

        return ModeChainResult.standard(chain);
    }

    @Override @Deprecated
    public boolean isMemoryEnabled() { return true; }
}
```

#### AgentModeStrategy

```java
@Component
public class AgentModeStrategy implements ChatModeStrategy {

    // 门面（共享基础设施）
    private final AdvisorInfrastructure infra;

    // Agent 专用依赖
    private final IntentClassifier intentClassifier;
    private final ToolWorkspaceFactory workspaceFactory;
    private final AgentToolCallbackFactory agentToolCallbackFactory;
    private final AgentRagProperties agentProperties;
    private final ContextPromptInjector contextPromptInjector;
    private final ChatClientRegistry chatClientRegistry;

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

        // Step 4: 创建请求级 Workspace
        ToolWorkspace workspace = workspaceFactory.create(ctx.userId(), ctx.request().teamId());

        // Step 5: 根据意图创建 Tool 回调
        ToolCallback[] toolCallbacks = agentToolCallbackFactory
            .createToolCallbacks(intentResult.intent(), workspace);

        // Step 6: 自建独立 ToolCallAdvisor
        if (toolCallbacks.length > 0) {
            DefaultToolCallingManager mgr = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(toolCallbacks)))
                .build();
            chain.add(ToolCallAdvisor.builder()
                .toolCallingManager(mgr)
                .disableMemory()
                .advisorOrder(2)
                .build());
        }

        // Step 7: AgentSystemPromptAdvisor
        String mergedPrompt = resolveAgentPrompt(intentResult.intent(), ctx.cagContext());
        AgentGuardrails guardrails = createGuardrails(ctx.route());
        chain.add(new AgentSystemPromptAdvisor(
            intentResult.intent(), mergedPrompt, workspace, guardrails));

        // Step 8: 对话记忆
        chain.add(MessageChatMemoryAdvisor.builder(infra.getChatMemory()).build());

        // tokenCountingModel 必须来自 guardrails — 同一个实例用于护栏检查和 ChatClient 包装
        // 如果 guardrails 持有模型 A，而 ChatClient 使用模型 B，token 消耗永远不会进入护栏
        return ModeChainResult.agent(chain, intentResult, workspace,
            guardrails.getTokenCountingModel());
    }

    /**
     * 基于 ctx.route() 指向的模型构建 guardrails。
     * 旧逻辑从 ChatClientRegistry 遍历取第一个可用模型（可能与本次请求模型不一致），
     * 现在通过 AdvisorChainContext.route 显式传入，确保 tokenCountingModel 包装的是正确的 ChatModel。
     */
    private AgentGuardrails createGuardrails(ModelRouter.Route route) {
        ChatModel chatModel = chatClientRegistry.getChatModel(route.toCompositeId());
        // ... 其余逻辑不变：chatModel != null → TokenCountingChatModel(chatModel)
        //                                   else → TokenCountingChatModel(new NoOpChatModel())
    }

    @Override @Deprecated
    public boolean isMemoryEnabled() { return true; }

    @Override @Deprecated
    public boolean isAgentMode() { return true; }
}
```

### 2.7 简化：ModeRouter

> **Codex Review MEDIUM-6 修正**：去掉手动 new 后，需在构造时做 fail-fast 校验，确保必需模式已注册且无重复。

```java
@Component
public class ModeRouter {
    private final Map<ChatMode, ChatModeStrategy> strategyMap;

    public ModeRouter(List<ChatModeStrategy> strategies) {
        this.strategyMap = new EnumMap<>(ChatMode.class);
        Set<ChatMode> seen = EnumSet.noneOf(ChatMode.class);
        for (ChatModeStrategy s : strategies) {
            ChatMode mode = s.getMode();
            if (!seen.add(mode)) {
                throw new IllegalStateException(
                    "Duplicate ChatModeStrategy for mode: " + mode);
            }
            strategyMap.put(mode, s);
        }
        // fail-fast: 所有 ChatMode 必须有对应策略注册
        for (ChatMode required : ChatMode.values()) {
            if (!strategyMap.containsKey(required)) {
                throw new IllegalStateException(
                    "No ChatModeStrategy registered for mode: " + required
                    + ". Required modes: " + Arrays.toString(ChatMode.values()));
            }
        }
    }

    public ChatModeStrategy route(String mode) {
        ChatMode chatMode = ChatMode.fromString(mode);
        ChatModeStrategy strategy = strategyMap.get(chatMode);
        if (strategy == null) {
            return strategyMap.get(ChatMode.SIMPLE);
        }
        return strategy;
    }
}
```

### 2.8 简化：ChatAdvisorChainFactory

Step 1 保留此类作为门面入口（向后兼容），内部委托给策略：

```java
@Component
public class ChatAdvisorChainFactory {

    // 删除所有缓存字段 — 已搬到 AdvisorInfrastructure
    // 删除 buildChain() 和 buildAgentChain() — 已搬到各策略

    private final AdvisorInfrastructure infra;

    /**
     * 构建 Advisor 链 — 委托给策略，返回 ModeChainResult
     */
    public ModeChainResult buildChain(String conversationId,
                                      ChatRequest request,
                                      ChatModeStrategy modeStrategy,
                                      RequestContext cagContext,
                                      Long userId,
                                      ModelRouter.Route route) {
        AdvisorChainContext ctx = new AdvisorChainContext(
            conversationId, request, userId, cagContext, route);
        return modeStrategy.buildAdvisorChain(ctx);
    }

    // 保留 hasTools() / getToolCallbacks() — 委托给 AdvisorInfrastructure
    public boolean hasTools() { return infra.hasTools(); }
    public ToolCallback[] getToolCallbacks() { return infra.getToolCallbacks(); }
}
```

### 2.10 微调：ChatRequestSpecFactory

> **Codex Review R2 HIGH-1 修正**：`createSpec()` 当前签名无 userId，内部调用 `buildChain()` 时依赖隐式的 `extractUserId()`。重构后 `buildChain()` 需要显式 userId，`createSpec()` 签名必须同步更新。

> **Codex Review R3 Issue-2 修正**：当前 `createSpec()` 无条件执行 `.tools()`、DB system prompt、model options 绑定。Agent 模式需要跳过这三者。通过 `ModeChainResult` 的执行指示字段控制。

```java
// 修改后 — 增加 userId 参数，尊重 ModeChainResult 执行指示
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

    // Tool Calling — Agent 模式跳过（有自建 ToolCallAdvisor）
    if (!result.skipGlobalTools() && advisorChainFactory.hasTools()) {
        spec = spec.tools((Object) advisorChainFactory.getToolCallbacks());
    }

    // System Prompt（CAG 增强）— Agent 模式跳过（有 AgentSystemPromptAdvisor）
    if (!result.skipDbSystemPrompt()) {
        String systemPrompt = resolveSystemPrompt(route);
        systemPrompt = contextPromptInjector.inject(systemPrompt, cagContext);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = spec.system(systemPrompt);
        }
    }

    // 模型参数 — Agent 模式跳过（使用自有模型配置）
    if (!result.skipDbModelOptions()) {
        ChatOptions options = resolveChatOptions(route);
        if (options != null) {
            spec = spec.options(options);
        }
    }

    return spec;
}
```

`ChatServiceImpl` 中 `doChat()` 和 `doStandardFallbackChat()` 两个调用点同步传入 `ctx.userId`。

### 2.11 微调：ChatServiceImpl

Step 1 **暂保留** `isAgentMode()` 分支（执行路径分流），Step 2 再下沉：

```java
private ChatResponse doChat(ChatRequest request, FallbackMeta fallback) {
    ChatContext ctx = prepareContext(request);
    conversationHelper.ensureConversationExists(ctx.userId, ctx.conversationId, request.model());
    RequestContext cagCtx = buildCagContext(ctx, request);

    if (ctx.modeStrategy.isAgentMode()) {
        return doAgentChat(ctx, request, cagCtx);
    }
    // ... 标准路径不变
}
```

`doAgentChat` 内部调用链简化为：

```java
private ChatResponse doAgentChat(ChatContext ctx, ChatRequest request, RequestContext cagCtx) {
    // 1. 构建链 — 现在统一走策略，返回 ModeChainResult
    AdvisorChainContext chainCtx = new AdvisorChainContext(
        ctx.conversationId, request, ctx.userId, cagCtx, ctx.route);
    ModeChainResult result = ctx.modeStrategy.buildAdvisorChain(chainCtx);

    // 2. 从 ModeChainResult 提取 Agent 元数据
    IntentResult intentResult = result.intentResult();
    ToolWorkspace workspace = result.workspace();
    TokenCountingChatModel tokenCountingModel = result.tokenCountingModel();

    // 3. 构建 ChatClient 请求（使用 tokenCountingModel）
    // ...（TokenCountingChatClient 包装等逻辑不变）

    // 4. 执行 + 结果提取
    // ...（不变）
}
```

标准路径也使用统一的 `buildChain`：

```java
// 标准路径 — ChatRequestSpecFactory 内部调用 advisorChainFactory.buildChain()
// buildChain() 现在返回 ModeChainResult，取 .chain() 即可
List<Advisor> chain = advisorChainFactory.buildChain(
    conversationId, request, modeStrategy, cagContext, userId, route).chain();
```

#### 流式路径：doStream() 对 AGENT 的处理

> **Codex Review R3 Issue-1 修正**：当前 `doStream()` 无 `isAgentMode()` 分流，AGENT 请求会走标准 `createSpec()`，混入全局 tools/DB system prompt/model options — 代码已有此缺陷，文档必须明确 Step 1 处理策略。

Agent 模式的 ReAct 循环（意图分类 → 工具调用 → 护栏检查 → 多轮迭代）在流式场景下远比标准模式复杂。Step 1 采用 **显式拒绝** 策略：

```java
private Flux<String> doStream(String modelId, ChatRequest request) {
    ChatContext ctx = prepareContext(request);
    conversationHelper.ensureConversationExists(ctx.userId, ctx.conversationId, request.model());
    RequestContext cagCtx = buildCagContext(ctx, request);

    // Step 1: Agent 模式暂不支持流式 — ReAct 循环的多轮工具调用在流式中需特殊处理
    // 使用 BusinessException 而非 UnsupportedOperationException，
    // 后者不在 FallbackEligibility 不可降级列表中，会被流式 fallback 误判为可降级并重试
    if (ctx.modeStrategy.isAgentMode()) {
        throw new BusinessException(ErrorCode.UNSUPPORTED_OPERATION,
            "Agent mode does not support streaming in this version. Use blocking call instead.");
    }

    ChatClient.ChatClientRequestSpec requestSpec = requestSpecFactory.createSpec(
            ctx.chatClient, ctx.route, request, ctx.conversationId,
            ctx.modeStrategy, cagCtx, ctx.userId);
    // ... 其余流式逻辑不变
}
```

Step 2 预告：新增 `doAgentStream()` 实现完整 Agent 流式支持（ReAct 循环 + 流式工具调用 + 护栏检查），届时 `isAgentMode()` 分支从 `doStream()` 和 `doChat()` 一起下沉到策略。

### 2.12 修改：AdvisorAutoConfiguration

> **Codex Review HIGH-3 修正**：删除手动 `@Bean` 注册，策略改用 `@Component` 自动注册。

```java
@Configuration
public class AdvisorAutoConfiguration {

    // ❌ 删除以下 @Bean 方法 — 策略已改为 @Component 自动注册
    // @Bean
    // public SimpleModeStrategy simpleModeStrategy() { ... }
    //
    // @Bean
    // public MultiTurnModeStrategy multiTurnModeStrategy() { ... }

    // ModeRouter 也改为 @Component，不再需要手动 @Bean
    // 保留其他与 Advisor 链相关的 @Bean 定义（ToolCallAdvisor 等）
}
```

## 3. 改动文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `chat/mode/ChatModeStrategy.java` | 修改 | 增加 `buildAdvisorChain()` → 返回 `ModeChainResult`，标记旧方法 @Deprecated |
| `chat/mode/SimpleModeStrategy.java` | 修改 | 改 @Component，实现 buildAdvisorChain |
| `chat/mode/MultiTurnModeStrategy.java` | 修改 | 改 @Component，实现 buildAdvisorChain |
| `chat/mode/ModeRouter.java` | 修改 | 改 @Component，加 fail-fast 校验全部 ChatMode |
| `chat/service/ModeChainResult.java` | **新增** | 统一返回类型 + 执行指示 record，替代 AgentChainResult |
| `chat/service/AgentChainResult.java` | **删除** | 被 ModeChainResult 替代，迁移完成后删除 |
| `chat/service/AdvisorInfrastructure.java` | **新增** | 门面服务，ObjectProvider\<ToolRegistry\> + volatile DCL 缓存 |
| `chat/service/AdvisorChainContext.java` | **新增** | 轻量请求上下文 record，携带 userId + route |
| `chat/service/ChatAdvisorChainFactory.java` | 简化 | 委托给策略，返回 ModeChainResult，删除双 build 方法 |
| `chat/service/ChatRequestSpecFactory.java` | 修改 | createSpec 签名增加 userId，buildChain 传入 route |
| `chat/service/impl/ChatServiceImpl.java` | 微调 | doAgentChat 使用 ModeChainResult，createSpec 传入 userId |
| `rag/agent/mode/AgentModeStrategy.java` | 修改 | 实现 buildAdvisorChain，createGuardrails(ctx.route()) 基于请求路由构建 |
| `config/AdvisorAutoConfiguration.java` | 修改 | 删除策略 @Bean 方法 |

## 4. Codex Review 修正记录

### R1（第一轮）

| # | 等级 | 问题 | 修正方案 | 文档章节 |
|---|------|------|----------|----------|
| 1 | HIGH | `buildAdvisorChain()` 返回 `List<Advisor>` 丢失 Agent 元数据 | 引入 `ModeChainResult` 统一返回类型 | 2.2, 2.5, 2.6, 2.8, 2.11 |
| 2 | HIGH | `ChatRequestSpecFactory.createSpec()` 无 userId 参数 | `AdvisorChainContext` 携带 userId，消除隐式依赖 | 2.4, 2.8 |
| 3 | HIGH | `@Component` 与 `@Bean` 冲突导致重复 Bean | 删除 `AdvisorAutoConfiguration` 中的策略 @Bean 方法 | 2.6, 2.12 |
| 4 | MEDIUM | `thinkingEnabled` 描述不准确（实际未被调用） | 从重构范围移除，记录为独立 feature | 1.3 |
| 5 | MEDIUM | `AdvisorInfrastructure` 缓存语义与实际不符 | 保留 ObjectProvider + volatile DCL 模式 | 2.3 |
| 6 | MEDIUM | ModeRouter 去掉 fallback 后缺少校验 | 构造时 fail-fast 校验必需模式 + 重复检测 | 2.7 |

### R2（第二轮）

| # | 等级 | 问题 | 修正方案 | 文档章节 |
|---|------|------|----------|----------|
| 7 | HIGH | `ChatRequestSpecFactory.createSpec()` 签名无 userId，调用链断裂 | 新增 2.10 节，签名增加 userId，两个调用点同步更新 | 2.10, 2.11 |
| 8 | HIGH | `wrapTokenCounting()` 与 `createGuardrails()` 分离导致双模型实例 | `tokenCountingModel` 必须来自 `guardrails.getTokenCountingModel()`，不单独创建 | 2.6 |
| 9 | HIGH | `ObjectProvider<ToolCallback[]>` Bean 不存在，项目用 `ToolRegistry` | 改为 `ObjectProvider<ToolRegistry>` + `ObjectProvider<ToolCallAdvisor>` | 2.3 |
| 10 | MEDIUM | `getGlobalAdvisors()` 缺少 `List.copyOf()` 不可变保护 | 保留 `List.copyOf(globalAdvisorsProvider.getIfAvailable(Collections::emptyList))` | 2.3 |
| 11 | MEDIUM | ModeRouter 只校验 SIMPLE，缺少 AGENT/MULTI_TURN 静默降级风险 | 构造时遍历 `ChatMode.values()` 全部校验 | 2.7 |

### R3（第三轮）

| # | 等级 | 问题 | 修正方案 | 文档章节 |
|---|------|------|----------|----------|
| 12 | HIGH | `doStream()` 无 AGENT 分流，AGENT 流式请求混入标准路径（全局 tools/DB system prompt） | Step 1 拒绝 AGENT 流式（抛 `BusinessException`，避免被 fallback 重试），Step 2 新增 `doAgentStream()` | 2.11 |
| 13 | HIGH | `createSpec()` 无条件绑定 tools/system-prompt/model-options，Agent 模式需跳过 | `ModeChainResult` 新增 `skipGlobalTools`/`skipDbSystemPrompt`/`skipDbModelOptions` 执行指示 | 2.2, 2.10 |
| 14 | MEDIUM | `AgentChainResult` 与 `ModeChainResult` 字段完全重复，并存造成语义混淆 | `ModeChainResult` 替代 `AgentChainResult`，迁移完成后删除 | 2.2, 文件清单 |

### R4（第四轮）

| # | 等级 | 问题 | 修正方案 | 文档章节 |
|---|------|------|----------|----------|
| 15 | HIGH | `createGuardrails()` 遍历 `ChatClientRegistry` 取第一个模型，可能与本次请求 `ctx.route` 不一致；Agent 跳过 DB model options 后"使用哪个模型"完全隐式 | `AdvisorChainContext` 增加 `route` 字段，`createGuardrails(ctx.route())` 基于请求路由获取指定 ChatModel | 2.4, 2.6 |
| 16 | HIGH | 迁移阶段冲突：Phase 1 写"对外接口不变"但 2.8 注释说删除 `buildChain()`/`buildAgentChain()`；`ChatServiceImpl` 仍直接调用 `buildAgentChain()` | 明确三阶段：Phase 1 只抽基础设施不改接口 → Phase 2 新增新接口并迁移调用点 → Phase 3 删旧方法 | 5 |
| 17 | MEDIUM | 开头承诺"零改动 Service 层"但 Step 1 保留 `isAgentMode()` 分支 + 新增 AGENT stream 拒绝分支 | 目标降级为"Advisor 链组装分支下沉到策略，执行路径分流留到 Step 2" | 1 |
| 18 | MEDIUM | `skipXxx` 是过渡设计，风险评估表未标注 | 风险评估表新增条目，明确标注为 Step 1 最小改动桥接方案 | 6 |
| 19 | MEDIUM | 流式 AGENT 拒绝抛 `UnsupportedOperationException`，不在 `FallbackEligibility` 不可降级列表中，会被 fallback 重试 | 改抛 `BusinessException(ErrorCode.UNSUPPORTED_OPERATION, ...)` | 2.11 |

## 5. 迁移步骤

### Phase 1 — 基础设施提取（无行为变更）

> 对外接口完全不变 — `ChatAdvisorChainFactory` 保留现有 `buildChain()` / `buildAgentChain()` 签名。

1. 创建 `ModeChainResult` record
2. 创建 `AdvisorInfrastructure` — 从 `ChatAdvisorChainFactory` 搬入 ObjectProvider + DCL 缓存逻辑
3. 创建 `AdvisorChainContext` record（含 route 字段）
4. `ChatAdvisorChainFactory` 内部改为委托 `AdvisorInfrastructure`，**保留** `buildChain()` / `buildAgentChain()` 对外签名不变
5. 编译验证

### Phase 2 — 策略升级（核心变更）

> 新增策略行为方法 + 迁移调用点。旧 `buildChain()` / `buildAgentChain()` 标记 @Deprecated 但仍可用。

1. `ChatModeStrategy` 增加 `buildAdvisorChain()` → 返回 `ModeChainResult`（含执行指示），旧方法标记 @Deprecated
2. `SimpleModeStrategy` → @Component，实现 buildAdvisorChain
3. `MultiTurnModeStrategy` → @Component，实现 buildAdvisorChain
4. `AgentModeStrategy` 实现 buildAdvisorChain（从 ChatAdvisorChainFactory.buildAgentChain 搬入，`createGuardrails(ctx.route())` 基于请求路由构建，返回完整 ModeChainResult，tokenCountingModel 来自 guardrails）
5. `ModeRouter` → @Component，加 fail-fast 校验全部 ChatMode
6. `AdvisorAutoConfiguration` 删除策略 @Bean 方法
7. `ChatRequestSpecFactory.createSpec()` 签名增加 userId + route，尊重 ModeChainResult 执行指示
8. `ChatServiceImpl.doAgentChat()` 改用 `modeStrategy.buildAdvisorChain()` + ModeChainResult
9. `ChatServiceImpl.doStream()` 增加 AGENT 模式拒绝（抛 `BusinessException`，避免被 fallback 重试）
10. `ModeChainResult` 替代 `AgentChainResult`，迁移所有引用点后删除后者
11. 编译 + 功能验证

### Phase 3 — 清理（删除旧方法）

> 确认 Phase 2 所有调用点已迁移到 `modeStrategy.buildAdvisorChain()` 后执行。

1. `ChatAdvisorChainFactory` 删除旧 `buildChain()` / `buildAgentChain()` 方法
2. `AgentChainResult.java` 确认已删除
3. 移除策略接口中 @Deprecated 的 flag 方法（如果 ChatServiceImpl 中不再需要）

### 独立 Feature — thinking 支持

重构完成后作为独立功能追加：

1. `ModelProvider.buildOptions()` 层面根据 `ChatRequest.isThinkingEnabled()` 构建对应 `ChatOptions`（通过 Spring AI portable `ChatOptions` 接口）
2. 各模型实现自行决定是否支持 thinking（如 Anthropic 扩展 thinking 参数，其他模型可忽略或降级）
3. per-request `ChatOptions` 通过 `Prompt` 传入，与 startup 默认值自动合并
4. `thinkingEnabled` 不再属于策略接口，纯请求级别 + ModelProvider 层面

## 6. 风险评估

| 风险 | 等级 | 缓解 |
|------|------|------|
| Agent 链构建逻辑搬迁出错 | 中 | buildAdvisorChain 逻辑 1:1 搬入 AgentModeStrategy，不改逻辑只搬家 |
| 缓存逻辑提取导致重复初始化 | 低 | AdvisorInfrastructure 保留相同的 ObjectProvider + volatile DCL 模式 |
| @Bean 删除后 Bean 不可用 | 低 | @Component 注解确保 Spring 自动发现，ModeRouter fail-fast 校验 |
| ModeChainResult 标准/Agent 分支 | 低 | record + 工厂方法明确语义，nullable 字段有文档 |
| `skipXxx` 执行指示是过渡设计 | 低 | Step 2 将 `createSpec()` 职责下沉到策略后可移除；当前作为 Step 1 最小改动的桥接方案，不引入新的策略级 flag |
| thinking 功能遗漏 | 无 | 已明确记录为独立 feature，本次重构不涉及 |

## 7. 后续（Step 2 预告）

Step 1 完成后，如需进一步：
- 将 `doChat()` / `doAgentChat()` / `doAgentStream()` 的执行逻辑也下沉到策略（`execute()` / `executeStream()`）
- 新增 `doAgentStream()` — Agent ReAct 循环的流式支持（多轮工具调用 + 流式输出 + 护栏检查）
- 移除 `isAgentMode()` / `isMemoryEnabled()` 等 flag 方法
- `ChatServiceImpl` 变成纯管道：`prepare → strategy.execute → postProcess`
- `ModeChainResult` 的 `skipXxx` 执行指示随 `createSpec()` 职责下沉到策略后可移除
