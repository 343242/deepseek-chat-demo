# Implement — AGENT 模式流式支持（复用 Spring AI 流式 tool manager）

> v4：基于 Poc6 实证。**砍掉 v3 P3 自研 `reactLoop`**；新增 `ChatModelAdapter.stream` 回灌 tool_calls +
> `AgentModeStrategy.executeStream` 接 `.stream()`。弹性经 L1 继承。**编辑任何符号前先
> `impact({target, direction:"upstream"})`，HIGH/CRITICAL 需先告知用户。**

## 阶段总览
P0a SPI 签名 → P0b GenericChatClient SSE 三态 → P1 SSE 单测 → P2 全迁移+回归(ProbeHandler Predicate)
→ P3 ChatModelAdapter.stream 回灌 → P4 AgentModeStrategy.executeStream → P4b 硬上界(STOP 抛异常关连接,阻塞+流式共享) → P5 超时/落库/真模型。
每阶段独立可验证、可回滚。**P3/P4 取代 v3 的自研 ReAct（Poc6 已证 Spring AI 接管）。**

> **审查修正前置（design §0，2026-06-24）**：Mimo 6 条 + Poc9 实证。最关键——**per-round 硬上界检查点不是 `before()`**
> （Poc9 铁证：模型跑 2 轮但 `BaseAdvisor.before()` 仅触发 1 次，阻塞/流式皆然）。P4b 改落 `ToolCallAdvisor.doBeforeStream`/`doBeforeCall`，
> **`Poc10` 已确认每轮触发**（done 2026-06-24，GREEN）。连锁：阻塞态既有 `maxToolIterations` 硬上界当前是 no-op，P4b 一并修。其余 #1/#3/#4/#5/#6 见 design §0。

---

## ✅ 完成状态（2026-06-25，全 push origin/agentic-rag-dev）

P0a–P4b 全部完成，流式 ReAct 真实模型验证可用。下方各阶段 checklist 保留为规划记录，实际进度以本块为准：

| 阶段 | commit | 内容 |
|---|---|---|
| design v4.2+Poc6-10 | ca81b33 | §0 审查修正 + Poc6-10 实证 |
| P0a | 545753b | StreamChunk SPI 单轨 Flux&lt;StreamChunk&gt; 迁移 |
| P0b | e63db05 | readSse 三态 + 轮末汇总包 + ToolCallAccumulator |
| P1 | 09c65cb | GenericChatClient SSE 三态单测（5 场景） |
| P2 | c91b8d9 | 弹性层泛型化透传（ProbeHandler.wrap/wrapWithProbe `<T>`；retryStream/executeStream 本已泛型） |
| P3 | 74c42b7 | ChatModelAdapter.stream 回灌 tool_calls/finishReason/usage |
| P4-1 | 0d074f4 | 提取 StreamCompletionHelper（Abstract+Agent 统一落库） |
| P4-2 | 3871831 | AgentModeStrategy.executeStream mirror execute `.call()`→`.stream()` |
| P4b | 8bc4d6f | GuardrailEnforcingToolCallAdvisor + GuardrailHardStopException + TokenCountingChatModel.stream accumulateStreamUsage |

**关键设计落地**：Agent 不继承 AbstractModeStrategy（`execute()` final 冲突）→ 落库走 StreamCompletionHelper static 工具类；ToolCallAdvisor 子类化用 protected `super(ToolCallingManager, int)`；check(null) doBefore 未知工具名。全量回归绿贯穿 P2–P4b。

**P5（可选增强，未做）**：流式 ReAct 已真实模型验证可用（2026-06-25）。剩余可选：per-tool timeout（CompletableFuture.orTimeout 装饰 ToolCallback, §6 CRIT-2）/ reasoning_content 字段处理（§0 #6）/ 三层 timeout 实测。

---

## P0a — StreamChunk SPI 签名变更（纯委托类）
- [ ] 新建 `infrastructure/llm/StreamChunk.java`（record + `ToolCallDelta` + `FinishReason` enum + `TokenUsage usage` 字段 + hasText/hasToolCall）
- [ ] `ChatCapable.chatStream` 返回类型 `Flux<String>` → `Flux<StreamChunk>`
- [ ] `AbstractChatClient`(abstract) + 纯委托类（`ResilientChatClient`/`ResilientToolCallingChatClient`）签名跟随，占位 `.map(s -> new StreamChunk(s, null, null, null))`
- [ ] `GenericChatClient` 留 `// TODO P0b`，P0a 不含
- [ ] 验证：`./mvnw test-compile`（除 GenericChatClient 外通过）
- **回滚点**：SPI 签名落地，语义未变。

## P0b — GenericChatClient SSE 三态解析 + 单轮累积
- [ ] chatStream create block：`FluxSink<String>` → `FluxSink<StreamChunk>`；解析 content / tool_calls / finish_reason / usage 四路
- [ ] `ToolCallAccumulator`：按 index 合并 name/arguments（arguments 在 finishReason 前不解析）
- [ ] **轮末（finishReason 到达 / `[DONE]`）发携带完整 toolCalls + finishReason + usage 的汇总 StreamChunk**（Poc6 防御式）
- [ ] 验证 `finish_reason` chunk 后 Flux 立即 complete
- [ ] 验证：`./mvnw test-compile` 全通过
- **回滚点**：SSE 解析就绪。

## P1 — SSE 解析单测 + 边界
- [ ] 单测：文本流 / tool_call 首片+arguments 分片 / finishReason / 多工具并行 index / 纯工具无文本 / 乱序 index / usage 末包 / finish_reason 后 complete / **轮末汇总包带完整 toolCalls**
- [ ] 验证：GenericChatClient chatStream 单测全绿
- **回滚点**：SSE 解析正确性锁定。

## P2 — chatStream 全迁移 + 回归（含 ProbeHandler Predicate）
- [ ] `ProbeHandler` 泛型化 `<T>` + **`firstPacketPredicate` 参数**（HIGH-2）：Agent `chunk->true`，SIMPLE/MULTI_TURN `StreamChunk::hasText`。**范围（design §0 #4，grep 证实）：仅影响 chat 流式链（`ResilientChatClient.chatStream`）；embedding/rerank 无 stream 路径、阻塞 `call()` 不受影响**
- [ ] `RetryPolicy.retryStream`：emitted = 发过 hasText chunk
- [ ] `ResilientChatClient.chatStream` 适配 `Flux<StreamChunk>`（L1 重试/探测/熔断保持）
- [ ] `ChatModelAdapter.stream`：P0a 期间暂留占位（text-only），**P3 重写**——P2 仅保证签名编译
- [ ] `AbstractModeStrategy.executeStream` / `SseStreamBridge` / `ChatService`：外层投影（消费 hasText）
- [ ] 全量 chatStream 单测迁移
- [ ] 验证：`./mvnw test` —— SIMPLE/MULTI_TURN 流式 / classifyStream / 阻塞 execute / ProbeHandler(embedding+rerank) 全不回归
- **回滚点**：全链路 `Flux<StreamChunk>`，语义等价（Agent 流式尚未启用）。

## P3 — ChatModelAdapter.stream 回灌 tool_calls（新增核心，取代 v3 自研 reactLoop）
- [ ] `stream(Prompt)` 重写（`ChatModelAdapter:60-66`）：`Flux<StreamChunk>` → `Flux<ChatResponse>`
  - text chunk → `Generation(AssistantMessage(text))`（透传，保 TTFT）
  - ~~单轮累积 StreamChunk tool delta~~ **（design §0 #3：累积职责归 `GenericChatClient`，适配器不再累积）**。此处仅投影：收到轮末汇总 `StreamChunk`（已含完整 toolCalls）→ `Generation(AssistantMessage(toolCalls) + finishReason + usage)`
  - 复用阻塞 `toSpringToolCalls`（`:88-99`）+ `AssistantMessage.builder().toolCalls(...)`（公开 builder，无需 protected 子类）
- [ ] 单测：StreamChunk 序列（text + tool delta 跨片 + finishReason=TOOL_CALLS）→ 聚合 `ChatResponse` 含完整 `AssistantMessage.toolCalls` + `finishReason="tool_calls"` + usage（**Poc6 契约**）
- [ ] 单测：纯文本流（无 tool）→ 透传文本、无 toolCalls、finishReason=STOP
- [ ] 验证：`Poc6_SpringAiStreamingToolManagerTest` 改用真实 `ChatModelAdapter`（mock `ChatCapable.chatStream` 返回 StreamChunk 序列）仍 `streamToolFired=true / streamCount=2` —— **端到端契约回归**。（`streamToolCallResponses` 不配置——Poc7 证对 `.content()` 是 no-op，见 design §4.1）
- [ ] 验证：`./mvnw test` 全绿（阻塞 execute 不回归）
- **回滚点**：Spring AI 流式 tool manager 经适配器跑通（mock 级）。
- **编辑前 impact**：`ChatModelAdapter.stream`、`ChatModelAdapter.wrapAsChatResponse`（共用 `toSpringToolCalls`）。

## P4 — AgentModeStrategy.executeStream（新增，取代 `:298-301` 抛异常）
- [ ] `executeStream` 镜像 `execute()`：`buildAdvisorChain` → `ChatClient` from `new ChatModelAdapter(llmRegistry.get(candidateId, ChatCapable.class))` → spec(chain + `ToolCallingChatOptions.toolCallbacks`) → **`.stream().content()`**
- [ ] **`buildAdvisorChain` 中自建 `ToolCallAdvisor` 加 `.disableMemory()`**（design §4.2，**Poc8 已验证安全**：阻塞/流式 ReAct 不回归）：对齐全局 bean，ToolCallAdvisor 不再写 tool 消息（filter 本就会剥离），持久化记忆由 `MessageChatMemoryAdvisor`（filtered）专责。**不配置 `streamToolCallResponses`**（Poc7 证对 `.content()` no-op）
- [ ] 落库复用 `AbstractModeStrategy.onStreamComplete`（doFinally）；截断保护同 `AbstractModeStrategy:137-142`
- [ ] references 取自 `result.workspace().getRetrievedDocs()`
- [ ] **`TokenCountingChatModel.stream` 计数（§7，修指标②流式缺口）**：`stream()` 加 `doOnNext` 累计每轮末包 usage（依赖 P3 汇总包带 usage）；若包装层干扰流式 tool_calls，流式 ChatClient 直接建于 `ChatModelAdapter`
- [ ] 单测（mock chatStream 经适配器）：单轮无工具流式 / 多轮 tool_call→执行 mock 工具→次轮正文 / TTFT（首 text chunk 在工具执行前）
- [ ] 验证：`Poc5`（阻塞）不破；AgentModeStrategy 流式单测全绿
- **回滚/兜底**：Spring AI 流式 tool manager 不可控 → fallback v3 自研（见 design §9）。
- **编辑前 impact**：`AgentModeStrategy.executeStream`（当前抛异常，调用方 = 流式调度入口）。

## P4b — 硬上界：AgentGuardrails STOP → 抛异常关连接（阻塞+流式共享，design §4.3）

> **范围扩展**：此阶段改 `AgentSystemPromptAdvisor`/`AgentGuardrails` 的 STOP 消费，**影响阻塞态**（今天阻塞 STOP 也是软注入）。编辑前先 `impact`，HIGH/CRITICAL 需先告知用户。
>
> **⚠️ Poc9 修正（design §0 #2，证伪级）**：`before()` 经实测仅 round 1 触发（阻塞 `callCount=2`/流式 `streamCount=2` 时 `baseBefore=1`）→
> STOP 检查**不能挂在 `before()`/`checkGuardrails()`**。改落 `ToolCallAdvisor.doBeforeStream`/`doBeforeCall`（子类化 override）或边界层自治。
> **`Poc10` 已确认（2026-06-24）：`doBeforeStream`/`doBeforeCall` 每轮触发，本阶段可直接按此实现（子类化 override）。** 连锁：阻塞态既有 `maxToolIterations` 迭代硬上界当前是 no-op（`totalIterations` 恒 1），本阶段一并修。

- [ ] 新建 `GuardrailHardStopException`（携 reason/message）
- [ ] `AgentSystemPromptAdvisor.checkGuardrails()`：STOP 分支从"返回注入字符串"改为"**抛 `GuardrailHardStopException`**"（WARN 分支保留软注入）；~~`before()` 让异常上抛~~ **❌ Poc9 证伪（before 仅 round 1）**。改为子类化 `ToolCallAdvisor`，在 `doBeforeStream`/`doBeforeCall`（每轮触发）调 `AgentGuardrails.check()`，STOP 抛 `GuardrailHardStopException`（WARN 仍软注入）；或边界层 `ChatModelAdapter.stream` 轮末汇总包自检。**Poc10 已确认 hook 落点（doBeforeStream/doBeforeCall 每轮触发）**
- [ ] **流式**：异常 → Flux error → `sink.onDispose(call::cancel)`（`GenericChatClient:122-123` 已接）→ OkHttp 关连接；`onStreamComplete(ON_ERROR)` 落 partial
- [ ] **阻塞**：异常 → `AgentModeStrategy.execute()` catch（`:276-282`）→ `fallbackToMultiTurn` 降级
- [ ] **`TokenCountingChatModel.stream` 计数**（§7，P4 已列）：补 `doOnNext` 累计 usage，否则流式 token 门槛(②)失效、硬 abort 少一维
- [ ] **docstring 校准**：`AgentGuardrails.java:18`"跳出 ReAct 循环，用已有结果生成回答" → "抛 `GuardrailHardStopException` 关连接强制终止（流式落 partial / 阻塞降级）"
- [ ] Poc：超 `maxToolIterations` 场景 → 断言阻塞降级到 multiTurn + 流式连接 cancel（OkHttp call cancelled）+ partial 落库
- [ ] **阻塞回归**：`Poc5` 扩展（超阈值 → 抛异常 → 降级）；阻塞 execute 正常路径不破
- [ ] 验证：`./mvnw test` 全绿
- **编辑前 impact**：`AgentSystemPromptAdvisor.checkGuardrails` / `AgentGuardrails`（阻塞+流式共享） / `TokenCountingChatModel.stream`

## P5 — 超时 / 落库 / 真模型 / 收尾
- [ ] **三层 timeout 对接**：外层 `spec.stream().content().timeout(600s)` + `doFinally`（落 partial + cancel）；SseEmitter 610s 由 `SseStreamBridge` 继承；连接级首包 30s 由 L1 `ProbeHandler` 继承
- [ ] **per-tool timeout（CRIT-2）**：agent `ToolCallback` 包 timeout 装饰器（`CompletableFuture.orTimeout(30s)`，专用池，复用 [[async-executor-threadpool-preference]]）；超时返回 error 结果喂回。**必须 `.exceptionally(ex -> "[Tool error: execution timed out after 30s]")` 捕 `TimeoutException` 转 error 字符串（design §0 #5），否则 `ToolCallback.call()` 抛异常会中断整个 ReAct**
- [ ] **loop bounds 验证**：Spring AI 无迭代上限（design §7）；硬保证 = `AgentGuardrails` 抛异常关连接（P4b）+ 时间 timeout（per-tool 30s / Flux 600s）；DeepSeek 真模型验阈值合理（maxToolIterations=10）
- [ ] **DeepSeek 真模型流式 tool calling 端到端**：真实触发 hybridSearch，多轮 ReAct，最终答案流式；**重点验是否增量流（不缓冲整轮）**
- [ ] TTFT 对比阻塞态（AC：TTFT < 阻塞 P95 × 0.5）；若不达标且因 Spring AI 缓冲整轮 → 触发 fallback（design §9）
- [ ] 记忆/落库真实验证
- [ ] `detect_changes({scope:"compare", base_ref:"main"})` 范围确认
- [ ] 提交 + 推送
- **编辑前 impact**：外层流式调度、`SseStreamBridge`、agent 工具回调包装。

## 验证命令
编译 `./mvnw test-compile` / 单测 `./mvnw test -Dtest=<Class>` / 全量 `./mvnw test` / 契约 `./mvnw test -Dtest=Poc6_SpringAiStreamingToolManagerTest` / 提交前 `detect_changes`。

## 阈值定稿（design §9，不拖实现）
per-tool timeout=30s / Flux 超时=600s / SseEmitter 超时=610s / token 预算=80% 窗口 / **loop bounds = `AgentGuardrails` 迭代(maxToolIterations=10)+token 双阈值抛异常关连接（P4b）+ 时间 timeout 兜底**（Spring AI 无迭代上限）。

## 风险触发回滚
- P0b SSE 解析出错 → 回 P0a 占位。
- P3 适配器回灌后 Poc6 契约不过 → bisect StreamChunk→ChatResponse 映射（finishReason / toolCalls 完整性）。
- P5 真模型不增量流影响 TTFT → 切 v3 自研 fallback（design §9）。
- P2 迁移回归 → 逐文件 bisect（ProbeHandler / IntentClassifier / ResilientChatClient）。

## v3 → v4 删除清单（不再实现）
自研 `reactLoop` / `ToolCallAccumulator` 作为 ReAct 驱动 / acc 与订阅 1:1 / `consecLoop` / `consecFail` / 自建 `ToolResponseMessage` / depth 计数 / Agent 层 `.retryWhen`（retry-on-stream）/ 末轮文本捕获——全部由 Spring AI `ToolCallAdvisor.adviseStream` + L1 弹性 + `onStreamComplete` 接管。
