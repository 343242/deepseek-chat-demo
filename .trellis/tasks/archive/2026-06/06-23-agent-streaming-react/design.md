# Design — AGENT 模式流式支持（复用 Spring AI 流式 tool manager）

> v4：基于 **Poc6 实证**（真实 Spring AI 1.1.6 jar）重构。放弃 v3 的 strategy 层自研 ReAct，改为复用
> `ToolCallAdvisor.adviseStream` 驱动流式工具循环。Poc6 证明：自定义 `ChatModel.stream()` 轮末发"完整
> `toolCalls` + `finishReason=tool_calls`"汇总包 → `ToolCallAdvisor` 自动执行工具 + 再流一轮
> （`streamToolFired=true` / `streamCount=2` / `callCount=0` 两轮全流式 / 两轮文本按序流到消费方）。
> 真实缺口仅 `ChatModelAdapter.stream` 丢 tool_calls。v3 自研方案降级为 fallback（见 §9）。

## 0. 审查修正（Mimo review + Poc9 实证，2026-06-24）

> v4.2：Mimo 6 条 review + Poc9 实证。**1 条证伪级（#2）、2 条真实陷阱（#3/#5）、3 条文档清晰度（#1/#4/#6）**。
> 本节为权威修正，与下文章节冲突时**以本节为准**；下文 §4.3/§7 原句仅作历史保留 + 指针。

### #2（证伪级）per-round 硬上界的检查点**不是** `before()`
- **Poc9 实证**（`Poc9_AdvisorBeforePerRoundTest`，阻塞+流式双路径，均 GREEN）：模型跑 2 轮（`callCount=2` / `streamCount=2`）时，
  `BaseAdvisor.before()` / `after()` **只触发 1 次**（仅 round 1）。§4.3/§7 原假设"before 每轮检查"**证伪**。
- **根因（字节码确认）**：`BaseAdvisor.adviseStream/adviseCall` 把 `before()` 映射在单发 Mono 上（`lambda$adviseStream$0`）；
  `ToolCallAdvisor` 多轮循环由 `internalStream` / `handleToolCallRecursion` 内部驱动，续轮走 `chain.copy(this).nextStream()`
  只重入下游链拿 model stream，**不重经过上游 BaseAdvisor**。
- **连锁后果（比原 #2 更严重）**：阻塞态 `callCount=2` 时 `baseBefore=1` → 今天阻塞态 `AgentGuardrails.check()`
  （`totalIterations++` + 读 `getTotalTokens()`）也只在 round 1 跑一次 → `totalIterations` 恒为 1 →
  **`maxToolIterations` 迭代硬上界当前就是 no-op**（既有 latent bug，非流式独有；Poc5 未验护栏触发所以没暴露）。
- **修法方向**：per-round 检查点**不能是 `before()`**，改为 ① 子类化 `ToolCallAdvisor` override
  `doBeforeStream` / `doBeforeCall`（字节码显示 `internalStream` 每轮调 `doBeforeStream`），或 ② 边界层自治
  （`ChatModelAdapter.stream` 轮末汇总包处自检迭代/token 并抛异常）。**Poc10 已确认（2026-06-24）：`doBeforeStream`/`doBeforeCall` 每轮触发** → P4b 修法钉死为 ①（子类化 override，推荐）；② 边界层自治降为备选。

### #3（真实陷阱）ToolCallAccumulator 单点化
§3（GenericChatClient SSE 层）与 §4（ChatModelAdapter 边界层）各有一个 acc；§3 轮末已发完整汇总包则 §4 再累积是冗余且生命周期混乱。
**改为单 acc**：仅 `GenericChatClient` 内一个 `SseToolCallAccumulator`（单轮累积 + 轮末发汇总 `StreamChunk` + reset）；
**`ChatModelAdapter.stream` 不再累积**，直接把汇总 `StreamChunk` 的完整 toolCalls 投影成 `AssistantMessage.toolCalls`（§4 代码删掉 `ToolCallAccumulator acc`）。

### #5（真实陷阱）per-tool timeout 必须捕 `TimeoutException`
`CompletableFuture.orTimeout` 抛 `TimeoutException`，而 `ToolCallback.call()` 契约是返回字符串、不可抛——
否则 `DefaultToolCallingManager` 收到未捕获异常会中断整个 ReAct。P5 装饰器必须：
`future.orTimeout(30, SECONDS).exceptionally(ex -> "[Tool error: execution timed out after 30s]")`（§6 补）。

### #1（文档清晰度）§5 缺 `TokenCountingChatModel` 包装 —— 且与 #2 耦合
§5 代码 `new ChatModelAdapter(...)` 应为 `new TokenCountingChatModel(new ChatModelAdapter(...))`（对齐阻塞 `execute()`）。
但**即使补上 wrapper + `stream()` 累计 usage，因 #2 的 before 一次语义，token 检查仍只在 round 1 读一次** →
wrapper **必要但不充分**，必须配合 #2 的 per-round hook 才能让指标 ② 生效。

### #4（文档清晰度）ProbeHandler 泛型化范围（grep 证实）
`Embedding/RerankCapabilityStrategy` grep `chatStream|Flux<` **零命中** → embedding/rerank **无 stream 路径**。
ProbeHandler 泛型化**只影响 chat 流式链**（`ResilientChatClient.chatStream`）；阻塞 `call()` 与 embedding/rerank 不受影响。implement P2 标注。

### #6（低风险）`streamToolCallResponses` + DeepSeek 中间轮 content
Poc7 验的是 stub。真模型 DeepSeek 若中间轮 content 非空（尤其 **`reasoning_content` 独立字段**，§3 SSE 解析未处理）需 P5 验是否泄漏到 `.content()`。P3 补"中间轮非空 content"单测锁定过滤行为。

### POC 清单（本任务新增）
- ✅ `Poc9_AdvisorBeforePerRoundTest`（done 2026-06-24）：before 一次语义已铁证（阻塞+流式）。
- ✅ `Poc10_ToolCallAdvisorDoBeforeStreamPerRound`（done 2026-06-24，GREEN）：子类化 `ToolCallAdvisor` override 全部 8 个 doXxx。**确认 `doBeforeStream`/`doAfterStream`/`doBeforeCall`/`doAfterCall` 每轮触发**（=rounds），`doInitializeLoop*`/`doFinalizeLoop*` 各 1 次 → **P4b 修法钉死：子类化 override `doBeforeStream`/`doBeforeCall` 挂 `AgentGuardrails.check()`**。

## 1. 架构决策：复用 Spring AI 流式 tool manager（取代 v3 自研）

**事实链**：
- 阻塞态 `AgentModeStrategy.execute()`（`:255-269`）已用 `ChatClient.call()` + 自建 `ToolCallAdvisor` + `DefaultToolCallingManager`（`:155-164`）跑通 ReAct；底层 `ChatModel` 是自定义 `ChatModelAdapter(capable)`。
- Spring AI 1.1.6 `ToolCallAdvisor` **同时实现 `CallAdvisor` + `StreamAdvisor`**；`adviseStream` 内含 `internalStream` / `streamWithToolCallResponses` / `handleToolCallRecursion`（javap 确认）——流式 ReAct 循环 Spring AI 已实现。
- 流式与阻塞同构：`.call()`→`.stream()`。**唯一缺口**是 `ChatModelAdapter.stream()`（`:60-66`）把 `Flux<String>` 映射成纯文本 `ChatResponse`，丢 tool_calls/finishReason/usage；根因在 `GenericChatClient.readSse`（`:150-181`）只取 `delta.content`。

**证据**：`Poc6_SpringAiStreamingToolManagerTest`——stub `ChatModel.stream()` 轮末发完整 toolCalls 汇总包 → `ToolCallAdvisor.adviseStream` 自动执行工具并多轮流式 ReAct，两轮文本按序流到消费方、TTFT 保住。

**推论**：自研 `reactLoop`/`ToolCallAccumulator`/acc 与订阅 1:1/`consecLoop`/`consecFail`/自建 ToolResponseMessage/depth/retry-on-stream 全部不需要——由 Spring AI + 已有阻塞 advisor 链接管。

## 2. SPI 改型：StreamChunk（保留，跨边界传 tool delta）

```java
public record StreamChunk(
    @Nullable String text,
    @Nullable ToolCallDelta toolDelta,
    @Nullable FinishReason finishReason,   // STOP | LENGTH | TOOL_CALLS | CONTENT_FILTER
    @Nullable TokenUsage usage             // 末包携带（M4 usage 记录需要）
) {
    public boolean hasText()     { return text != null && !text.isEmpty(); }
    public boolean hasToolCall() { return toolDelta != null; }
    public record ToolCallDelta(int index, @Nullable String id,
                                @Nullable String name, @Nullable String arguments) {}
    public enum FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER }
}
```

- SPI 内单轨 `Flux<StreamChunk>`；边界由 `ChatModelAdapter.stream` 投影成带 tool_calls 的 `Flux<ChatResponse>`（喂给 Spring AI）。
- **`ProbeHandler` 泛型化带 `Predicate<T> firstPacketPredicate`（HIGH-2 保留）**：Agent 传 `chunk -> true`，SIMPLE/MULTI_TURN 传 `StreamChunk::hasText`。
- `RetryPolicy.retryStream`：emitted = 发过 hasText chunk。
- 迁移清单：`ChatCapable` / `AbstractChatClient` / `ResilientChatClient` / `ResilientToolCallingChatClient` / `GenericChatClient`(SSE 三态) / `ChatModelAdapter.stream` / `ProbeHandler` / 调用方。
- `StreamChunk` 加 `usage` 字段（v3 缺）——流式 usage 在 SSE 末包，落 `TokenUsage` 供 `onStreamComplete` usage 记录（修 v3 的 usage 不可达缺口）。

## 3. GenericChatClient SSE 三态解析（保留 + 单轮累积）

`readSse` 解析四路：`delta.content`→text；`delta.tool_calls[i]`→ToolCallDelta（按 index 累积）；`finish_reason`→FinishReason；末包 `usage`→TokenUsage。

**关键**：单轮内用 `ToolCallAccumulator`（按 index 合并 name/arguments，arguments 流式 JSON 在 finishReason 前不解析）。**轮末（finishReason 到达 / `[DONE]`）发一个携带完整 toolCalls + finishReason + usage 的汇总 `StreamChunk`**——这是 Poc6 验证的防御式形态，确保 Spring AI 从聚合末包检测工具调用，不依赖其 delta 合并行为。

## 4. ChatModelAdapter.stream 重写（新增核心，取代 v3 自研 reactLoop）

```java
@Override
public Flux<ChatResponse> stream(Prompt prompt) {
    ChatRequest request = extractChatRequest(prompt);          // 复用（含 extractTools）
    ToolCallAccumulator acc = new ToolCallAccumulator();        // 单轮累积（仅边界层用，不驱动 ReAct）
    return delegate.chatStream(request)
        .map(chunk -> {                                         // StreamChunk → ChatResponse
            if (chunk.hasToolCall()) acc.mergeToolCall(chunk.toolDelta());
            if (chunk.finishReason() == TOOL_CALLS) {           // 轮末：发完整 toolCalls 汇总包
                return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder()
                        .content(acc.text())
                        .toolCalls(toSpringToolCalls(acc.toolCalls()))   // 复用阻塞 toSpringToolCalls
                        .build(),
                    ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
            }
            // 文本 chunk：透传（保 TTFT）+ 携带 usage（末包）
            AssistantMessage msg = chunk.hasText()
                ? new AssistantMessage(chunk.text())
                : AssistantMessage.builder().content("").build();
            return new ChatResponse(List.of(new Generation(msg, finishMeta(chunk))));
        });
}
```

- 与阻塞 `wrapAsChatResponse`（`:68-86`）同源：`toSpringToolCalls`（`:88-99`）+ `ToolCallAssistantMessage`/`AssistantMessage.builder().toolCalls(...)` 复用。
- 文本 chunk 透传 → Spring AI 流给消费方（TTFT）。
- 轮末汇总包带完整 tool_calls → Spring AI `ToolCallAdvisor.adviseStream` 检测 → 执行工具（`DefaultToolCallingManager`）→ 喂回 → 再调 `stream()` 下一轮。
- **ReAct 循环、工具执行、喂回、loop bounds 全部由 Spring AI 接管**（Poc6 验证 `callCount=0`，两轮都走 stream，无阻塞回退）。

### 4.1 `streamToolCallResponses` 不配置（Poc7 证对 `.content()` 是 no-op）

`ToolCallAdvisor.Builder` 暴露 `streamToolCallResponses(boolean)`，文档暗示 `false` 可"仅流最终答案"。

**Poc7 实证**（`Poc7_StreamToolCallResponsesFlagTest`，同 stub 跑 true/false）：两者 `.content()` 输出**逐字相同**——`false` 下轮 1 thought 文本照样到达消费方（`round1Thought=true`）。原因：本系统消费 `.content()`（只取 `AssistantMessage` 文本），而该 flag 控制的是"工具响应包（ToolResponseMessage）是否进完整 `Flux<ChatResponse>`"；ToolResponseMessage 非 AssistantMessage，`.content()` 本就不发它。叠加 DeepSeek 工具中间轮 content 通常为空 → flag 对本路径**零影响**。

**决策：不配置 `streamToolCallResponses`（用默认 `true`）**。若将来需要过滤前端输出，正确层是 `SseStreamBridge`（后端拥有 SSE 协议），不是此 flag。

### 4.2 `.disableMemory()` 已验证安全（Poc8）

自建 `ToolCallAdvisor` 加 `.disableMemory()`（对齐全局 bean，避免 ToolCallAdvisor 与 `MessageChatMemoryAdvisor` 双重管理历史）。**Poc8 已验证**（`Poc8_DisableMemoryReactIntactTest`）：阻塞 `.call()` + 流式 `.stream()` 两条 ReAct 在 `.disableMemory()` 下均完整——工具执行、两轮（`callCount=2`/`streamCount=2`）、最终答案正确。测试链未挂 `MessageChatMemoryAdvisor` 仍工作 → 证明工具结果反馈由 `ToolCallingManager` 重建 Prompt 驱动，**不依赖** ToolCallAdvisor 的 memory。故加到共享 `buildAdvisorChain` 对阻塞 `execute()`（当前 `:159-164` 未设置）无回归。

**实现**：`buildAdvisorChain` 自建 `ToolCallAdvisor` 时加 `.disableMemory()`；持久化记忆仍由 `MessageChatMemoryAdvisor`（filtered）负责，职责分离更清晰（ToolCallAdvisor 不再写会被 filter 剥离的 tool 消息）。

### 4.3 硬上界 = 抛异常关连接（迭代/token 双阈值，阻塞+流式共享）

**背景**：Spring AI `ToolCallAdvisor` **无迭代硬上界**（已证：`ToolCallingChatOptions` 无 `maxIterations`、`ToolCallAdvisor` 无迭代字段/计数，字节码无引用）。v3 遗留的 `AgentGuardrails` STOP 仅作"软 prompt 注入"（注入"[系统指令] 停止调用工具"靠 LLM 遵从）——**不构成硬上界**。阻塞态今天同样只有软注入、无代码级硬保证。

**决策**：STOP（指标 ① 迭代 / ② token）从"软注入"升级为"**抛 `GuardrailHardStopException` → 关连接 abort**"——不信任模型任何后续行为，到阈值无条件终止：

| 路径 | abort 机制 | 用户结果 |
|---|---|---|
| 流式 | `throw → Flux error → sink.onDispose → call::cancel`（`GenericChatClient:122-123` 已接）→ OkHttp 连接关闭 | 已流出 partial + 流结束；`onStreamComplete(ON_ERROR)` 落 partial |
| 阻塞 | `throw → execute() catch(:276-282) → fallbackToMultiTurn 降级`（轮间检查，上一轮调用已返回，无在飞需中断） | 降级到 MULTI_TURN 结果 |

- ~~护栏检查在轮间（`AgentSystemPromptAdvisor.before()`，下一轮 model 调用前）~~ **⚠️ 已被 §0 #2 Poc9 证伪**：`before()` 仅 round 1 触发，多轮不生效。per-round 检查改落 `ToolCallAdvisor.doBeforeStream`/`doBeforeCall`（子类化 override）或边界层自治；到阈值抛 `GuardrailHardStopException`（P4b + Poc10）。
- **指标 ③（同工具连续，WARN）保持软注入**（本就是 advisory）；仅 ①② 改硬 abort。
- **指标 ② token 流式生效**依赖 `TokenCountingChatModel.stream` 累计 usage（§7），否则流式 `getTotalTokens()≡0` → token 门槛失效。
- 硬上界 + 时间 timeout（per-tool 30s / Flux 600s）= 同一 abort 原语的两个触发维度；**总耗时上界 = 阈值轮数 × 单轮超时**，严格有界。
- **"用已有结果综合作答"不属硬上界**：若需此 UX，另设 best-effort 层（abort 前用独立短超时做一次无工具调用），但不计入硬保证。
- **影响阻塞态**（今天阻塞 STOP 也是软注入）→ 本任务范围扩到"流式 + 阻塞 guardrail 硬化"；需 `impact(AgentSystemPromptAdvisor / AgentGuardrails)` + 阻塞回归 Poc。

## 5. AgentModeStrategy.executeStream（新增，镜像 execute，取代 `:298-301` 抛异常）

```java
@Override
public StreamResult executeStream(StrategyExecutionContext ctx) {
    AdvisorChainContext chainCtx = ...;
    ModeChainResult result = buildAdvisorChain(chainCtx);        // 同阻塞：intent→workspace→tools→ToolCallAdvisor→SystemPrompt→filtered memory（flag 不配置见 §4.1；.disableMemory 见 §4.2）

    ChatModel chatModel = new ChatModelAdapter(
        llmRegistry.get(ctx.candidateId(), ChatCapable.class));  // 同 createGuardrails 的 capable 来源
    ChatClient client = ChatClient.builder(chatModel).build();

    ChatClient.ChatClientRequestSpec spec = client.prompt()
        .user(ctx.request().message())
        .advisors(a -> a.advisors(result.chain())
            .param(CONVERSATION_ID, ctx.conversationId()));
    if (result.toolCallbacks() != null && result.toolCallbacks().length > 0) {
        spec.options(ToolCallingChatOptions.builder()
            .toolCallbacks(result.toolCallbacks()).build());     // 同阻塞 :262-266
    }

    StringBuilder collected = new StringBuilder();
    Flux<String> content = spec.stream().content()               // ← 唯一与 execute 的差别：.call()→.stream()
        .doOnNext(text -> { /* 截断保护，同 AbstractModeStrategy:137-142 */ })
        .doFinally(signal -> onStreamComplete(ctx, collected.toString(), signal));  // 复用 AbstractModeStrategy 落库
    return new StreamResult(content, buildReferences(result.workspace().getRetrievedDocs()));
}
```

- advisor 链与阻塞 `execute()` 完全一致（`buildAdvisorChain` 已共享）：含自建 `ToolCallAdvisor`（intent 选的工具，`.disableMemory()` 见 §4.2；`streamToolCallResponses` 不配置见 §4.1）、`AgentSystemPromptAdvisor`、filtered `MessageChatMemoryAdvisor`。
- ReAct 由链内 `ToolCallAdvisor.adviseStream` 驱动；中间轮 chunk 被 Spring AI 过滤，仅最终答案流到消费方。
- 落库复用 `AbstractModeStrategy.onStreamComplete`（落 user + finalAssistant 无 tool_calls；filtered memory 已过滤中间轮 tool 消息）。

## 6. 弹性 / 超时（瘦身：多数继承 L1）

- **L1 `ResilientChatClient.chatStream` 免费提供**：`retryPolicy.retryStream` + `ProbeHandler` 首包探测 + 熔断器（与 SIMPLE/MULTI_TURN 流式今天的路径一致，已被生产验证）。**Agent 不另挂 `.retryWhen`**（消除 v3 的 retry-on-emitted-stream 重发/acc 竞态）。
- **R4（用户策略）**：Agent 流式**不走跨模型降级**；重试/探测/熔断经 L1，耗尽/熔断 OPEN → Spring AI 流 error → `onStreamComplete(ON_ERROR)` 落 partial。
- **三层 timeout**：连接级首包 30s（L1 `ProbeHandler`，继承）/ Flux 600s（外层 `.timeout` + `doFinally`）/ SseEmitter 610s（`SseStreamBridge`，继承）。
- **per-tool timeout（CRIT-2，机制变更）**：Spring AI `DefaultToolCallingManager` 同步执行工具，无 `Mono.timeout`。改为**把 agent 的 `ToolCallback` 包一层 timeout 装饰器**（`CompletableFuture.supplyAsync(...).orTimeout(30s)`，复用 [[async-executor-threadpool-preference]] 专用池）后注册；超时返回 error 结果，Spring AI 以 error ToolResponse 喂回续轮（对齐阻塞态行为，优于 v3 的直接中止）。
- **客户端断连（M1）**：Flux cancel 传播至 Spring AI 订阅 cancel + `GenericChatClient` 的 `sink.onCancel(call::cancel)`（`:122`，已存在）→ OkHttp call cancel。继承 SIMPLE/MULTI_TURN 路径。

## 7. token 预算 / loop bounds（硬上界见 §4.3）

**已证**：Spring AI `ToolCallAdvisor` **无迭代硬上界**——`ToolCallingChatOptions` 无 `maxIterations`、`ToolCallAdvisor` 无迭代字段/计数（字节码无引用）。循环只在"模型不再返回 `finishReason=tool_calls`"时自然结束，无代码级迭代保证。

- **硬上界（§4.3）**：~~`AgentGuardrails` 经 `AgentSystemPromptAdvisor` 每轮 `before()` 检查~~ **⚠️ 已被 §0 #2 Poc9 证伪**（before 仅 round 1）。per-round 检查改落 `ToolCallAdvisor.doBeforeStream`/`doBeforeCall`（子类化 override）或边界层自治（`ChatModelAdapter.stream` 轮末自检），到阈值 **抛 `GuardrailHardStopException` 关连接**（流式 `call::cancel` / 阻塞降级）。**注意：阻塞态既有迭代硬上界当前也是 no-op（totalIterations 恒 1），P4b 一并修**。指标 ②（token）依赖 `TokenCountingChatModel.stream` 累计 usage **且** per-round hook（#1+#2 耦合，缺一不可）。
- **指标 ② token 流式计数缺口**：`TokenCountingChatModel.stream`（`:53-57`）当前是透传桩、不调 `accumulateUsage` → 流式 `getTotalTokens()≡0` → token 门槛失效。**P4b 修**：`stream()` 加 `doOnNext` 累计每轮末包 usage（依赖改动 1 汇总包带 usage）。若 token-counting 包装层干扰流式 tool_calls，流式 ChatClient 直接建于 `ChatModelAdapter`。
- **时间维度兜底**：per-tool 30s / Flux 600s（同一 abort 原语）。
- WARN（指标 ③ 同工具连续）保持软 prompt 注入（advisory）。

## 8. 风险表（v4 重写）

| 风险 | 处理 |
|---|---|
| R1 delta 累积 | `GenericChatClient` 单轮内按 index 累积，**轮末发完整 toolCalls 汇总包**（Poc6 证 Spring AI 从末包检测）；单测跨片/乱序/多工具 |
| R2 ReAct 循环 | Spring AI `ToolCallAdvisor.adviseStream` 接管（Poc6 证 `streamCount=2`/`callCount=0`） |
| R3 记忆/system prompt | 首轮 advisor 组装；`onStreamComplete` 落库；filtered memory 过滤 tool 消息（对齐阻塞 `createFilteredMemory`） |
| R4 模型熔断（用户策略） | L1 继承（重试+首包探测+熔断），不走跨模型降级；Agent 不另挂重试 |
| R5 loop / 死循环 | Spring AI 无迭代硬上界（已证）；硬保证 = `AgentGuardrails` 抛 `GuardrailHardStopException` 关连接（迭代/token 阈值，§4.3）+ 时间 timeout（per-tool 30s / Flux 600s）；WARN(③) 软注入 |
| R6 工具执行/超时（CRIT-2） | `ToolCallback` timeout 装饰器（30s，专用池）；超时 error 喂回复用 Spring AI 续轮 |
| R7 usage 可达 | `StreamChunk.usage`（v3 缺，v4 补）→ 末包携带 → `onStreamComplete` 记录 |
| R8 中间轮泄漏 / 双重历史 | `streamToolCallResponses` 不配置（Poc7 证 `.content()` no-op，§4.1）；`.disableMemory()` 对齐全局 bean 避免双重消息管理（§4.2，Poc8 验证） |

## 9. 回退 / 阈值定稿

- **主方案**：复用 Spring AI 流式 tool manager（本设计）。
- **回退（fallback = v3 自研）**：仅当 P5 真模型（DeepSeek）验证发现 Spring AI 流式 tool manager 在多轮工具时不增量流（缓冲整轮）且影响 TTFT AC 时，切回 v3 strategy 层自研 `reactLoop`（v3 design 保留在 git 历史 `design.md` 旧版）。
- **阈值定稿**：per-tool timeout=30s / Flux 超时=600s / SseEmitter 超时=610s / token 预算=模型窗口 80% / **loop bounds = `AgentGuardrails` 迭代(maxToolIterations=10) + token(80% 窗口) 双阈值抛异常关连接（§4.3）+ 时间 timeout 兜底**（Spring AI 无迭代上限，已证）。
