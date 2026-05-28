# Step 2 设计方案：执行下沉

> **关联文档**：[chat-mode-strategy-refactor.md](chat-mode-strategy-refactor.md)（Step 1 Advisor 链组装下沉 + 完整 Codex Review 修正记录 R1-R5）
>
> 本文档为 Step 2 设计方案的独立文档，章节编号保留 §7.x 以与主文档的 Codex Review 修正记录交叉引用一致。

> 消除 `ChatServiceImpl` 中的 `isAgentMode()` 分支。
> Agent 流式作为独立 Phase C 探索性实现，不在本步骤主路径。

### 7.1 目标

| 目标 | 当前状态（Step 1） | Step 2 目标 |
|------|--------------------|-------------|
| 执行路径分流 | `doChat()` 内 `isAgentMode()` if-else | 策略 `execute()` 多态分发 |
| 流式分流 | `doStream()` 内 AGENT 拒绝 | 策略 `executeStream()` 多态分发 |
| flag 方法 | `isAgentMode()` / `isMemoryEnabled()` @Deprecated | 删除 |
| skipXxx 过渡 | `ModeChainResult` 桥接字段 | 删除 — 策略自己控制 spec 创建 |
| Agent 降级 | `ChatServiceImpl.doStandardFallbackChat()` | `AgentModeStrategy` 内部降级 |
| 流式持久化 | `isMemoryEnabled()` flag 控制 | 策略 `onStreamComplete()` 多态方法 |
| ChatServiceImpl | 编排 + 执行混合 | 纯管道：`prepare → strategy.execute → processResult` |

### 7.2 策略接口演进

```java
public interface ChatModeStrategy {

    ChatMode getMode();

    /**
     * 构建 Advisor 链（Step 1 已实现）。
     * Step 2 由 execute / executeStream 内部调用，不再作为公开 API。
     */
    ModeChainResult buildAdvisorChain(AdvisorChainContext ctx);

    /**
     * 阻塞式执行 — 策略负责链构建 + spec 创建 + 调用执行。
     * 返回 StrategyExecuteResult，由 ChatServiceImpl 统一后续处理。
     */
    StrategyExecuteResult execute(StrategyExecutionContext ctx);

    /**
     * 流式执行 — 策略负责链构建 + 流式调用 + 流式收尾。
     * 内部使用 .stream().chatResponse() 追踪 lastAiResponse，
     * doFinally 中完成消息持久化。
     */
    Flux<String> executeStream(StrategyExecutionContext ctx);
}
```

> **删除**：`isAgentMode()`、`isMemoryEnabled()` — 不再需要 flag 分支。

### 7.3 新增：StrategyExecuteResult

> **Codex Review R5 Issue-20 修正**：`execute()` 返回 Spring AI `ChatResponse` 时，`ChatServiceImpl` 无法完成 usage 记录、消息保存、business DTO 包装。新增 `StrategyExecuteResult` 携带完整执行结果。

策略 `execute()` 的统一返回类型。携带 Spring AI ChatResponse（含 usage）、提取后的 content、以及 Agent 模式特有的 metadata。`ChatServiceImpl.processResult()` 从此记录提取业务 DTO 所需的一切信息。

```java
/**
 * 策略 execute() 的统一返回类型。
 * ChatServiceImpl 根据 result 完成后续处理：usage 记录、消息保存、DTO 包装。
 */
public record StrategyExecuteResult(
    /** Spring AI 原始响应（含 usage metadata） */
    org.springframework.ai.chat.model.ChatResponse springAiResponse,
    /** 提取后的文本内容 */
    String content,
    /** Agent 元数据（仅 Agent 模式非 null） */
    @Nullable Map<String, Object> agentMetadata
) {
    /** 标准模式工厂 */
    public static StrategyExecuteResult standard(
            org.springframework.ai.chat.model.ChatResponse response, String content) {
        return new StrategyExecuteResult(response, content, null);
    }

    /** Agent 模式工厂 */
    public static StrategyExecuteResult agent(
            org.springframework.ai.chat.model.ChatResponse response,
            String content,
            Map<String, Object> agentMetadata) {
        return new StrategyExecuteResult(response, content, agentMetadata);
    }
}
```

### 7.4 新增：StrategyExecutionContext

与 `AdvisorChainContext` 的区别：
- `AdvisorChainContext` — 链构建时的轻量上下文（Step 1）
- `StrategyExecutionContext` — 执行时的完整上下文（Step 2），包含 ChatClient

> **Codex Review R6 Issue-25 修正**：`processResult()` 需要 `rawConversationId` 返回给客户端（非隔离 ID），以及 `route.toCompositeId()` 作为模型 ID。当前设计缺少这些字段。

```java
/**
 * 策略执行上下文 — execute / executeStream 的入参。
 */
public record StrategyExecutionContext(
    ChatClient chatClient,
    ModelRouter.Route route,
    ChatRequest request,
    String conversationId,       // 隔离后的 ID — 内部使用（消息保存、usage 记录）
    String rawConversationId,    // 原始 ID — 返回给客户端的 DTO
    Long userId,
    RequestContext cagContext
) {}
```

### 7.5 精简：ModeChainResult

移除 Step 1 的 `skipXxx` 过渡字段 — 策略 `execute()` 内部直接控制 spec 创建：

```java
public record ModeChainResult(
    List<Advisor> chain,

    // Agent 元数据（nullable）
    @Nullable IntentResult intentResult,
    @Nullable ToolWorkspace workspace,
    @Nullable TokenCountingChatModel tokenCountingModel
) {
    public static ModeChainResult standard(List<Advisor> chain) {
        return new ModeChainResult(chain, null, null, null);
    }

    public static ModeChainResult agent(List<Advisor> chain,
                                         IntentResult intentResult,
                                         ToolWorkspace workspace,
                                         TokenCountingChatModel tokenCountingModel) {
        return new ModeChainResult(chain, intentResult, workspace, tokenCountingModel);
    }
}
```

### 7.6 标准模式执行

> **Codex Review R5 Issue-21 修正**：`executeStream()` 原设计使用 `.stream().content()`，丢失 `lastAiResponse` 追踪能力（usage 记录 + 消息持久化）。改为内部使用 `.stream().chatResponse()` + `doFinally` 完整保留。

`SimpleModeStrategy` 和 `MultiTurnModeStrategy` 的 `execute()` 返回 `StrategyExecuteResult`，供 `ChatServiceImpl.processResult()` 统一处理：

> **Codex Review R6 Issue-27/29 修正**：原设计遗漏 usage 记录（所有信号）和 ON_ERROR/CANCEL partial 保存；chunk 提取缺少 `getOutput()` 空值保护。

```java
// SimpleModeStrategy / MultiTurnModeStrategy
@Override
public StrategyExecuteResult execute(StrategyExecutionContext ctx) {
    AdvisorChainContext chainCtx = new AdvisorChainContext(
        ctx.conversationId(), ctx.request(), ctx.userId(),
        ctx.cagContext(), ctx.route());
    ModeChainResult result = buildAdvisorChain(chainCtx);

    // 标准模式：注入全局 tools + system prompt + model options
    org.springframework.ai.chat.model.ChatResponse springResponse =
        requestSpecFactory.createSpec(
            ctx.chatClient(), ctx.route(), ctx.request(),
            ctx.conversationId(), result.chain(), ctx.cagContext()
        ).call().chatResponse();

    String content = extractContent(springResponse);
    return StrategyExecuteResult.standard(springResponse, content);
}

@Override
public Flux<String> executeStream(StrategyExecutionContext ctx) {
    AdvisorChainContext chainCtx = new AdvisorChainContext(
        ctx.conversationId(), ctx.request(), ctx.userId(),
        ctx.cagContext(), ctx.route());
    ModeChainResult result = buildAdvisorChain(chainCtx);

    AtomicReference<org.springframework.ai.chat.model.ChatResponse> lastAiResponse =
        new AtomicReference<>();
    StringBuilder collectedContent = new StringBuilder();
    final int maxContentLength = 1 << 20;
    AtomicBoolean usageRecorded = new AtomicBoolean(false);

    return requestSpecFactory.createSpec(
        ctx.chatClient(), ctx.route(), ctx.request(),
        ctx.conversationId(), result.chain(), ctx.cagContext()
    )
    .stream()
    .chatResponse()
    .mapNotNull(aiResponse -> {
        lastAiResponse.set(aiResponse);
        Generation gen = aiResponse.getResult();
        if (gen == null || gen.getOutput() == null) {   // R6 #29: 空值保护
            return null;
        }
        String text = gen.getOutput().getText();
        if (text != null && collectedContent.length() < maxContentLength) {
            collectedContent.append(text);
        }
        return text;
    })
    .doFinally(signal -> {
        // 1. 消息持久化 — 由各自策略决定是否保存 + 如何保存
        //    SimpleModeStrategy: 不保存（空实现）
        //    MultiTurnModeStrategy: ON_COMPLETE 保存完整消息，ON_ERROR/CANCEL 保存 partial
        onStreamComplete(ctx, collectedContent.toString(),
            lastAiResponse.get(), signal);

        // 2. usage 记录 — 所有模式、所有信号都记录
        if (usageRecorded.compareAndSet(false, true)) {
            org.springframework.ai.chat.model.ChatResponse last = lastAiResponse.get();
            if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
                usageTracker.recordUsage(ctx.conversationId(), ctx.route().toCompositeId(),
                    last, ctx.elapsed());
            } else {
                usageTracker.recordUsage(ctx.conversationId(), ctx.route().toCompositeId(),
                    ctx.elapsed());
            }
        }
    });
}
```

> **关键变更**（R6 #27）：
> - usage 记录从 `onStreamComplete()` 分离出来，所有模式都执行（包括 SimpleModeStrategy）
> - chunk 提取使用 `mapNotNull` + `gen == null || gen.getOutput() == null` 空值保护（R6 #29）
> - `onStreamComplete()` 仅负责策略级消息持久化差异（ON_COMPLETE 完整保存 vs ON_ERROR/CANCEL partial）

### 7.7 Agent 模式执行

`AgentModeStrategy.execute()` 内部完成链构建 + TokenCountingChatClient 包装 + 执行 + 降级处理：

```java
// AgentModeStrategy
@Override
public StrategyExecuteResult execute(StrategyExecutionContext ctx) {
    try {
        // 1. 构建链（复用 buildAdvisorChain）
        AdvisorChainContext chainCtx = new AdvisorChainContext(
            ctx.conversationId(), ctx.request(), ctx.userId(),
            ctx.cagContext(), ctx.route());
        ModeChainResult result = buildAdvisorChain(chainCtx);

        // 2. TokenCountingChatClient 包装
        ChatClient countingClient = ChatClient.builder(result.tokenCountingModel())
            .build();

        // 3. 执行 — 不注入全局 tools / DB system prompt / DB model options
        org.springframework.ai.chat.model.ChatResponse springResponse =
            countingClient.prompt()
                .user(ctx.request().message())
                .advisors(a -> a.advisors(result.chain())
                    .param(CONVERSATION_ID, ctx.conversationId()))
                .call()
                .chatResponse();

        // 4. 提取结果
        String content = extractContent(springResponse);
        Map<String, Object> agentMetadata = buildAgentMetadata(result);
        return StrategyExecuteResult.agent(springResponse, content, agentMetadata);

    } catch (Exception e) {
        // 5. Agent 降级 — 迁移自 ChatServiceImpl.doStandardFallbackChat()
        if (degradationStrategy.shouldDegrade(e)) {
            log.warn("Agent degradation triggered, falling back to MULTI_TURN", e);
            return fallbackToMultiTurn(ctx);
        }
        throw e;
    }
}
```

> Agent 模式不使用 `ChatRequestSpecFactory` — 直接构建 spec，跳过全局 tools / DB system prompt / DB model options。

### 7.8 Agent 降级逻辑迁移

> **Codex Review R5 Issue-22 修正**：当前降级逻辑在 `ChatServiceImpl.doStandardFallbackChat()`（lines 269-288），Step 2 需明确迁移归属。

当前降级逻辑通过 `modeRouter.route("MULTI_TURN")` 获取 MULTI_TURN 策略，创建标准 spec，调用，保存消息。Step 2 将降级逻辑迁入 `AgentModeStrategy`。

> **Codex Review R6 Issue-26 修正**：`AgentModeStrategy` 不能直接注入 `ModeRouter`，因为 `ModeRouter` 构造器需要 `List<ChatModeStrategy>`（包含 `AgentModeStrategy`），会形成循环依赖。改用 `ObjectProvider<MultiTurnModeStrategy>` 延迟获取。

```java
@Component
public class AgentModeStrategy implements ChatModeStrategy {
    private final AgentDegradationStrategy degradationStrategy;
    private final ObjectProvider<MultiTurnModeStrategy> multiTurnProvider;  // 延迟注入，避免循环依赖
    // ... 其他依赖

    /**
     * Agent 降级 — 使用 ObjectProvider 延迟获取 MULTI_TURN 策略执行。
     * 迁移自 ChatServiceImpl.doStandardFallbackChat()。
     */
    private StrategyExecuteResult fallbackToMultiTurn(StrategyExecutionContext ctx) {
        MultiTurnModeStrategy multiTurnStrategy = multiTurnProvider.getIfAvailable();
        if (multiTurnStrategy == null) {
            throw new IllegalStateException("MultiTurnModeStrategy not available for agent fallback");
        }
        return multiTurnStrategy.execute(ctx);
    }
}
```

> **为什么用 `ObjectProvider` 而非直接注入 `ModeRouter`**：
> `ModeRouter` 构造器参数为 `List<ChatModeStrategy>`，其中包含 `AgentModeStrategy`，
> 直接注入会触发 Spring 循环依赖检测。`ObjectProvider<MultiTurnModeStrategy>` 在首次调用时解析，
> 此时 Bean 容器已完全初始化。
>
> **为什么降级在策略内而非 ChatServiceImpl**：
> `executeWithFallback()` 是模型级 fallback（A 模型不可用换 B 模型），
> 而 Agent 降级是模式级（Agent 失败退回 MULTI_TURN），由策略自己判断和执行更合适。

### 7.9 Agent 流式支持（Phase C — 探索性实现）

> **Codex Review R5 Issue-23 修正**：Agent 流式与 execute 下沉不在同一 Phase。Spring AI 的 `ToolCallAdvisor`、`AgentSystemPromptAdvisor`、`TokenCountingChatModel` 的流式兼容性需要独立验证。以下为设计预研，实施前必须通过前置验证。

**前置验证清单**（Phase C 启动条件）：

| 检查项 | 验证方法 | 通过标准 |
|--------|----------|----------|
| `ToolCallAdvisor` 流式工具调用 | 单元测试：Flux 流中触发 tool call | 流式返回中包含工具调用结果 |
| `AgentSystemPromptAdvisor` 流式兼容 | 单元测试：流式场景 System Prompt 注入 | prompt 正确注入且不影响流式输出 |
| `TokenCountingChatModel` 流式计数 | 单元测试：流式场景 token 累积 | 累积计数与阻塞式结果一致（±5%） |
| `AgentGuardrails` 流式适配 | 单元测试：流式结束后护栏检查 | 护栏在流结束后正确触发 |

**设计方案**（前置验证通过后实施）：

```java
// AgentModeStrategy
@Override
public Flux<String> executeStream(StrategyExecutionContext ctx) {
    // 1. 构建链（含阻塞式意图分类 — 流式开始前完成）
    AdvisorChainContext chainCtx = new AdvisorChainContext(
        ctx.conversationId(), ctx.request(), ctx.userId(),
        ctx.cagContext(), ctx.route());
    ModeChainResult result = buildAdvisorChain(chainCtx);

    // 2. TokenCountingChatClient 包装
    ChatClient countingClient = ChatClient.builder(result.tokenCountingModel())
        .build();

    AtomicReference<org.springframework.ai.chat.model.ChatResponse> lastAiResponse =
        new AtomicReference<>();
    StringBuilder collectedContent = new StringBuilder();

    // 3. 流式执行 — ToolCallAdvisor 自动处理流式工具调用
    return countingClient.prompt()
        .user(ctx.request().message())
        .advisors(a -> a.advisors(result.chain())
            .param(CONVERSATION_ID, ctx.conversationId()))
        .stream()
        .chatResponse()
        .mapNotNull(aiResponse -> {
            lastAiResponse.set(aiResponse);
            Generation gen = aiResponse.getResult();
            if (gen == null || gen.getOutput() == null) {   // R6 #29: 空值保护
                return null;
            }
            String text = gen.getOutput().getText();
            if (text != null && collectedContent.length() < maxContentLength) {
                collectedContent.append(text);
            }
            return text;
        })
        .doFinally(signal -> {
            // Agent 流式收尾：保存消息、记录 usage
            onAgentStreamComplete(ctx, collectedContent.toString(),
                lastAiResponse.get(), signal, result);
        });
}
```

### 7.10 ChatServiceImpl 简化

```java
@Service
public class ChatServiceImpl implements ChatService {

    @Override
    public ChatResponse doChat(ChatRequest request, FallbackMeta fallback) {
        ChatContext ctx = prepareContext(request);
        conversationHelper.ensureConversationExists(
            ctx.userId, ctx.conversationId, request.model());
        RequestContext cagCtx = buildCagContext(ctx, request);

        // 策略多态 — 无 isAgentMode() 分支
        StrategyExecutionContext execCtx = new StrategyExecutionContext(
            ctx.chatClient, ctx.route, request,
            ctx.conversationId, ctx.rawConversationId, ctx.userId, cagCtx);

        return executeWithFallback(execCtx, ctx.modeStrategy, fallback);
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        ChatContext ctx = prepareContext(request);
        conversationHelper.ensureConversationExists(
            ctx.userId, ctx.conversationId, request.model());
        RequestContext cagCtx = buildCagContext(ctx, request);

        StrategyExecutionContext execCtx = new StrategyExecutionContext(
            ctx.chatClient, ctx.route, request,
            ctx.conversationId, ctx.rawConversationId, ctx.userId, cagCtx);

        // 策略多态 — 无 isAgentMode() 分支
        return ctx.modeStrategy.executeStream(execCtx);
    }

    /**
     * 统一后处理 — 从 StrategyExecuteResult 完成后续操作。
     * 迁移自当前 doChat() 中分散的 usage 记录、消息保存、DTO 包装逻辑。
     *
     * R6 #25: rawConversationId → DTO 返回客户端；conversationId → 消息保存、usage 记录。
     * route.toCompositeId() → 模型 ID（含 provider 维度）。
     */
    private ChatResponse processResult(StrategyExecuteResult result,
                                        StrategyExecutionContext ctx,
                                        FallbackMeta fallback) {
        String compositeModelId = ctx.route().toCompositeId();

        // 1. usage 记录 — 使用隔离 conversationId + compositeModelId
        recordUsage(ctx.conversationId(), compositeModelId, result.springAiResponse());

        // 2. 消息保存 — 使用隔离 conversationId
        saveMessages(ctx.conversationId(), ctx.request().message(),
            result.content(), compositeModelId, result.springAiResponse());

        // 3. 构建 business DTO — rawConversationId 返回客户端
        if (result.agentMetadata() != null) {
            return new ChatResponse(compositeModelId, result.content(),
                ctx.rawConversationId(), null, result.agentMetadata());
        }
        return new ChatResponse(compositeModelId, result.content(),
            ctx.rawConversationId(), fallback);
    }

    private ChatResponse executeWithFallback(StrategyExecutionContext execCtx,
                                              ChatModeStrategy strategy,
                                              FallbackMeta fallback) {
        try {
            StrategyExecuteResult result = strategy.execute(execCtx);
            return processResult(result, execCtx, fallback);
        } catch (ModelException e) {
            // 模型级 fallback（A 模型不可用换 B 模型）
            return handleModelFallback(e, execCtx, fallback);
        }
    }
}
```

> **删除方法**：`doAgentChat()`、`doStandardFallbackChat()` — 逻辑下沉到策略。
>
> **删除分支**：`isAgentMode()` if-else — 多态分发替代。
>
> **新增方法**：`processResult()` — 统一后处理，替代当前 `doChat()` 中分散的
> usage 记录、content 提取、消息保存、DTO 包装。

### 7.11 ChatRequestSpecFactory 演进

Step 1 的 `createSpec()` 接收 `ChatModeStrategy` + 检查 `skipXxx`。Step 2 简化为纯工具方法：

```java
// Step 2: 签名简化，接收 chain 而非 strategy
public ChatClient.ChatClientRequestSpec createSpec(
    ChatClient chatClient,
    ModelRouter.Route route,
    ChatRequest request,
    String conversationId,
    List<Advisor> chain,          // 直接传 chain，不再通过 strategy 间接获取
    RequestContext cagContext
) {
    ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
        .user(request.message())
        .advisors(a -> a.advisors(chain)
            .param(CONVERSATION_ID, conversationId));

    if (hasTools()) {
        spec = spec.tools((Object) getToolCallbacks());
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
```

> Agent 模式不调用此方法 — 直接构建 spec，因此无需 `skipXxx` 条件判断。

### 7.12 流式持久化策略

> **Codex Review R5 Issue-24 修正**：删除 `isMemoryEnabled()` 后，SIMPLE/MULTI_TURN/AGENT 的流式持久化语义需要替代机制。每个策略通过 `executeStream()` 内部 `doFinally` 自行决定持久化行为。

| 策略 | 流式消息持久化 | 说明 |
|------|---------------|------|
| SimpleModeStrategy | 不保存 | 单轮对话，无历史 |
| MultiTurnModeStrategy | 保存用户消息 + AI 消息 | 多轮对话需历史 |
| AgentModeStrategy | 保存用户消息 + AI 消息 + Agent 事件 | Agent 需完整对话链 |

```java
// SimpleModeStrategy — 空实现（不持久化消息，但 usage 由 executeStream() 的 doFinally 统一记录）
protected void onStreamComplete(StrategyExecutionContext ctx, String content,
                                 ChatResponse lastResp, SignalType signal) {
    // 不持久化消息
}

// MultiTurnModeStrategy — 保存消息（ON_COMPLETE 完整保存，ON_ERROR/CANCEL partial 保存）
protected void onStreamComplete(StrategyExecutionContext ctx, String content,
                                 ChatResponse lastResp, SignalType signal) {
    switch (signal) {
        case ON_COMPLETE -> {
            if (lastResp != null) {
                messageService.save(ctx.conversationId(),
                    ctx.request().message(), content,
                    ctx.route().toCompositeId(), lastResp);
            }
        }
        case ON_ERROR, CANCEL -> {
            log.warn("Stream {} for conversation {}: collected {} chars",
                signal, ctx.conversationId(), content.length());
            messageService.savePartialResponse(ctx.conversationId(), content);
        }
        default -> {}
    }
}
```

> **为什么不用 flag**：每个策略对流式收尾的处理逻辑可能不同（如 Agent 还需保存 tool 调用事件），
> 用多态方法比 boolean flag 更灵活且类型安全。
>
> **R6 #27 注意**：`onStreamComplete()` 仅负责策略级消息持久化差异；
> usage 记录在 `executeStream()` 的 `doFinally` 中统一处理（见 §7.6），所有模式都执行，不经过此方法。

### 7.13 改动文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `chat/mode/ChatModeStrategy.java` | 修改 | 新增 `execute()` / `executeStream()`，删除 flag 方法 |
| `chat/mode/SimpleModeStrategy.java` | 修改 | 实现 execute / executeStream，空 onStreamComplete |
| `chat/mode/MultiTurnModeStrategy.java` | 修改 | 实现 execute / executeStream，持久化 onStreamComplete |
| `rag/agent/mode/AgentModeStrategy.java` | 修改 | 实现 execute（含降级），Phase C 实现 executeStream |
| `chat/service/ModeChainResult.java` | 精简 | 移除 skipXxx 字段 |
| `chat/service/StrategyExecuteResult.java` | **新增** | execute() 返回类型 record |
| `chat/service/StrategyExecutionContext.java` | **新增** | 执行上下文 record |
| `chat/service/ChatRequestSpecFactory.java` | 简化 | 签名移除 strategy 参数，移除 skipXxx 检查 |
| `chat/service/impl/ChatServiceImpl.java` | 简化 | 删除 doAgentChat / doStandardFallbackChat，新增 processResult |
| `chat/service/ChatAdvisorChainFactory.java` | 评估 | 如果职责完全被策略替代则删除 |

### 7.14 迁移步骤

#### Phase A — execute() 下沉 + StrategyExecuteResult

1. 创建 `StrategyExecutionContext` record
2. 创建 `StrategyExecuteResult` record
3. `ChatModeStrategy` 新增 `execute()` / `executeStream()` default 方法（默认抛 `BusinessException(UNSUPPORTED_OPERATION)`）
4. `ChatRequestSpecFactory.createSpec()` 简化签名（移除 strategy 参数，接收 `List<Advisor> chain`）
5. `SimpleModeStrategy` 实现 execute — 委托 ChatRequestSpecFactory，返回 StrategyExecuteResult
6. `MultiTurnModeStrategy` 实现 execute — 同上
7. `AgentModeStrategy` 实现 execute — 搬入 `doAgentChat()` 逻辑 + 降级处理（注入 ModeRouter）
8. `ChatServiceImpl.doChat()` 改为 `strategy.execute()` + `processResult()`
9. `ModeChainResult` 移除 skipXxx 字段，更新所有引用点
10. 编译 + 功能验证

#### Phase B — 标准流式下沉

1. `SimpleModeStrategy` 实现 executeStream — 标准流式 + 空 onStreamComplete
2. `MultiTurnModeStrategy` 实现 executeStream — 标准流式 + 持久化 onStreamComplete
3. `ChatServiceImpl.chatStream()` 改为 `strategy.executeStream()`（标准模式分支）
4. 编译 + 标准模式流式验证

#### Phase C — Agent 流式探索（前置验证通过后）

1. 验证 `ToolCallAdvisor` 流式工具调用兼容性
2. 验证 `AgentSystemPromptAdvisor` 流式场景 System Prompt 注入
3. 验证 `TokenCountingChatModel` 流式 token 计数
4. 验证 `AgentGuardrails` 流式护栏检查
5. 通过后实施 `AgentModeStrategy.executeStream()`
6. `ChatServiceImpl.chatStream()` 移除 AGENT 拒绝分支
7. 编译 + Agent 流式功能验证

#### Phase D — 清理

1. 删除 `isAgentMode()` / `isMemoryEnabled()` flag 方法
2. 删除 `doAgentChat()` / `doStandardFallbackChat()`
3. 评估 `ChatAdvisorChainFactory` 是否保留
4. 编译 + 全量回归

### 7.15 风险评估

| 风险 | 等级 | 缓解 |
|------|------|------|
| Agent execute 逻辑搬迁出错 | 中 | `doAgentChat` 逻辑 1:1 搬入 `AgentModeStrategy`，不改逻辑只搬家 |
| Agent 降级逻辑迁移遗漏 | 中 | 降级路径有独立测试覆盖，迁移后保留原有测试 |
| Agent 流式 ToolCallAdvisor 兼容性 | 中 | Phase C 前置验证清单通过后才实施，不通过则维持 AGENT 拒绝流式 |
| AgentSystemPromptAdvisor 流式兼容 | 低 | Phase C 验证项之一 |
| TokenCountingChatModel 流式计数 | 低 | Phase C 验证项之一 |
| fallback 机制与 execute() 集成 | 低 | `ChatServiceImpl` 保持 fallback 逻辑不变，只改调用入口 |
| ChatAdvisorChainFactory 废弃判断 | 低 | Phase D 根据实际引用情况决定保留或删除 |
| `StrategyExecutionContext` 后处理上下文不足 | 高 | 扩展执行上下文，显式携带 `rawConversationId`、`actualModelId`/`route.toCompositeId()`、`startTimeMs` 或 `durationMs`，避免 `processResult()` 暴露隔离后的 conversationId 或使用错误模型 ID |
| Agent 降级注入 `ModeRouter` 形成 Spring 循环依赖 | 高 | 不在 `AgentModeStrategy` 构造器直接注入 `ModeRouter`；改用 `ObjectProvider<MultiTurnModeStrategy>`、专用 `AgentFallbackExecutor`，或保留模式级降级在 `ChatServiceImpl` |
| 流式下沉遗漏 usage 记录和 partial 保存 | 高 | 将当前 `doStream()` 的 usage 记录、`ON_ERROR`/`CANCEL` partial 保存、`ON_COMPLETE` 完整保存抽成共享生命周期组件或基类，策略只决定持久化差异 |
| `executeWithFallback()` 示例与现有 fallback 语义不一致 | 中 | 明确保留当前 `chat()`/`chatStream()` 外层 fallback loop 和 `FallbackEligibility`，`doChat()` 内部只替换为 `strategy.execute()` |
| 流式 chunk 提取缺少 `getOutput()` 空值保护 | 中 | 复用 null-safe `extractContent()`/`extractChunk()` helper，保持当前 `gen == null || gen.getOutput() == null` 保护 |
| `ChatRequestSpecFactory` 简化后工具依赖来源不明确 | 中 | 若 Phase D 删除 `ChatAdvisorChainFactory`，则 `ChatRequestSpecFactory` 显式注入 `AdvisorInfrastructure` 获取 tools |

### 7.16 Codex R5 审查修正记录

| # | 等级 | 问题 | 修正 |
|---|------|------|------|
| 20 | HIGH | `execute()` 返回 Spring AI `ChatResponse`，ChatServiceImpl 无法完成 usage 记录、消息保存、DTO 包装 | 新增 `StrategyExecuteResult` record（§7.3），携带 springAiResponse + content + agentMetadata。ChatServiceImpl 新增 `processResult()` 统一后续处理（§7.10） |
| 21 | HIGH | `executeStream()` 返回 `Flux<String>` 使用 `.stream().content()`，丢失 `lastAiResponse` 追踪 | 策略内部使用 `.stream().chatResponse()` + `doFinally` + `AtomicReference<ChatResponse>`，完整保留 usage 和消息保存能力（§7.6） |
| 22 | HIGH | Agent 降级逻辑 `doStandardFallbackChat()` 无明确迁移归属 | 迁入 `AgentModeStrategy`，注入 `ModeRouter` 实现 MULTI_TURN 降级路由（§7.8） |
| 23 | MEDIUM | Agent 流式兼容性未验证就与 execute 下沉同 Phase | 拆分为独立 Phase C，设置前置验证清单，验证通过后才实施（§7.9, §7.14） |
| 24 | MEDIUM | 删除 `isMemoryEnabled()` 后 SIMPLE/MULTI_TURN 流式持久化语义混合 | 每个策略通过 `onStreamComplete()` 多态方法自行决定持久化行为（§7.12） |

### 7.17 Codex R6 复核发现（独立文档落地风险）

> 复核对象：本独立 Step 2 文档。以下问题经核对当前代码后全部确认有效，已逐一修正。

| # | 等级 | 问题 | 证据 | 修正状态 |
|---|------|------|------|----------|
| 25 | HIGH | `StrategyExecutionContext` 缺少后处理所需上下文 | 当前 `ChatServiceImpl` 返回 `ctx.rawConversationId` 给客户端，并使用 `ctx.route.toCompositeId()` 记录/返回模型；原文档 `processResult()` 使用 `conversationId` 和 `route.modelId()`，会暴露隔离后的 conversationId 且丢失 provider 维度模型 ID | **已修正** §7.4 增加 `rawConversationId`；§7.10 `processResult()` 改用 `ctx.rawConversationId()` 构建 DTO，`ctx.route().toCompositeId()` 作为模型 ID |
| 26 | HIGH | `AgentModeStrategy` 构造注入 `ModeRouter` 会形成 Spring 循环依赖 | `ModeRouter` 构造器需要 `List<ChatModeStrategy>`，其中包含 `AgentModeStrategy`；`AgentModeStrategy` 再注入 `ModeRouter` 会形成构造环 | **已修正** §7.8 改用 `ObjectProvider<MultiTurnModeStrategy>` 延迟注入，避免构造器循环 |
| 27 | HIGH | 流式下沉仍未完整保留当前收尾语义 | 当前 `doStream()` 在 `ON_ERROR`/`CANCEL` 保存 partial response，并在所有流式请求结束时记录 usage；原文档 `onStreamComplete()` 示例只保存完整消息，`SimpleModeStrategy` 空实现会丢失 usage | **已修正** §7.6 executeStream() 分离 usage 记录（所有模式所有信号）与 onStreamComplete（仅消息持久化）；§7.12 MultiTurnModeStrategy 增加 ON_ERROR/CANCEL partial 保存 |
| 28 | MEDIUM | `executeWithFallback()` 示例会误导实现改变模型 fallback 语义 | 当前 `chat()` 外层通过 fallback chain 遍历候选模型，并用 `FallbackEligibility` 判断异常；原文档示例只 catch `ModelException` 且未展示如何重建候选模型的 route/client/request | **已确认** §7.10 `executeWithFallback()` 是简化示意；实际实现保留当前外层 fallback loop 和 `FallbackEligibility`，仅内部调用从 `requestSpec.call()` 替换为 `strategy.execute(execCtx)` |
| 29 | MEDIUM | 流式 chunk 提取缺少 `getOutput()` 空值保护 | 当前实现检查 `gen == null || gen.getOutput() == null`；原文档 Agent 流式示例只检查 `resp.getResult() != null` | **已修正** §7.6 标准模式 + §7.9 Agent 流式均改为 `mapNotNull` + `gen == null || gen.getOutput() == null` 空值保护 |
| 30 | MEDIUM | `ChatRequestSpecFactory` 简化后工具依赖来源不明确 | 当前 `hasTools()`/`getToolCallbacks()` 来自 `ChatAdvisorChainFactory`；本文档 Phase D 又评估删除该 factory | **已确认** §7.11 保留当前依赖来源；若 Phase D 删除 `ChatAdvisorChainFactory`，`ChatRequestSpecFactory` 改为注入 `AdvisorInfrastructure`（见 §7.15 风险表） |

### 7.18 仍待落地的问题

> 以下问题是当前文档里仍会卡真实落地的实现缺口，不是概念上的残留。

| # | 等级 | 问题 | 说明 | 建议 |
|---|------|------|------|------|
| 31 | HIGH | `StrategyExecutionContext` 没有 `elapsed()` / `startTimeMs` | 文档里 `StrategyExecutionContext` 只有数据字段，但后面 `executeStream()` / `processResult()` 仍在调用 `ctx.elapsed()`；按当前形状实现会直接编译失败 | 给 `StrategyExecutionContext` 增加 `long startTimeMs` 和 `elapsed()`，或直接携带 `durationMs`，避免依赖外部隐式计时 |
| 32 | HIGH | `processResult()` 的 `recordUsage(...)` / `saveMessages(...)` 调用不匹配当前项目 API | 当前项目的 `ChatUsageTracker.recordUsage` 和 `ChatConversationHelper.saveMessagesAndNotify` 都需要 `durationMs`；如果照文档落地，容易漏参或写出不存在的方法名 | 把文档里的伪代码改成真实签名：`usageTracker.recordUsage(..., aiResponse, ctx.elapsed())` 和 `conversationHelper.saveMessagesAndNotify(..., aiResponse, ctx.elapsed())` |
| 33 | HIGH | `onStreamComplete()` 使用 `messageService.save(...)` / `savePartialResponse(...)`，但当前项目没有这个策略层依赖形状 | 现有可复用的实现是 `ChatConversationHelper.saveMessagesAndNotify()` 和 `savePartialResponse()`；如果策略自己做流式收尾，应直接注入 `ChatConversationHelper` | 把流式收尾依赖改成 `ChatConversationHelper`，不要引入文档中不存在的 `messageService` 形状 |
| 34 | MEDIUM | 文档内部仍有 `AgentModeStrategy` 注入 `ModeRouter` 的旧表达 | 你已经把实现路径改成 `ObjectProvider<MultiTurnModeStrategy>`，但正文或风险表里仍可能残留旧说法，容易让实现者重新写出循环依赖 | 全文统一成一种说法，只保留 `ObjectProvider<MultiTurnModeStrategy>` 方案，并同步删除旧的 `ModeRouter` 表述 |
| 35 | MEDIUM | `executeWithFallback()` 还是伪代码，仍可能误导实现者收窄 fallback 语义 | 真实项目里模型 fallback 依赖外层 `chat()` / `chatStream()` 的候选链和 `FallbackEligibility`，不是这里的 `catch (ModelException)` 示意 | 把这段改成纯说明性文字，明确“外层 fallback loop 不变，`doChat()` 内只替换为 `strategy.execute(execCtx)`” |
| 36 | LOW | `Agent` 流式章节仍属于 Phase C 预研 | 这部分不是 Step 2 主路径，但如果标题和说明不够明确，容易被误当成可直接实施的主体设计 | 在章节开头再次强调“仅预研，不纳入 Step 2 落地范围” |
