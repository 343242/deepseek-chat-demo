# 执行计划 — 思考程度参数统一适配

> 对应 `prd.md` + `design.md`。本期进入 Phase 2 前需先经用户/复核确认。

## 执行顺序

按依赖拓扑自底向上：先建新类型（无依赖），再改 record（用末位字段 + 委托构造器消除破坏面），最后接线。

> **同名类注意**：项目存在 `infrastructure/llm/ChatRequest`（SPI，本任务改）与 `mode/ChatRequest`（web DTO，无关）。下列所有 `new ChatRequest(` 核查均指向前者。

### Step 1 — 新增 `ThinkingDialect`（无依赖）
- 新建 `infrastructure/llm/ThinkingDialect.java`，enum `EFFORT`/`BUDGET`。
- 验证：`./mvnw -q compile`。

### Step 2 — 新增 `ThinkingConfig`（无依赖）
- 新建 `infrastructure/llm/ThinkingConfig.java`（record + 三个静态工厂，见 design §2.1）。
- 验证：编译通过。

### Step 3 — 新增 `ThinkingBodyResolver`（依赖 Step1/2）
- 新建 `infrastructure/llm/ThinkingBodyResolver.java`（私有构造 + 三静态方法，见 design §2.3）。
- **同步新增 `ThinkingBodyResolverTest`**（AC1/AC2/AC3/AC4/AC9 + 键名敏感性）：覆盖 resolve(EFFORT/BUDGET/disabled)、extractDialect（已配/未配默认）、extractDefault（已配/未配返回 null）、**kebab-case 键名 vs camelCase 静默失效断言**（防止 design §2.3 YAML 键名契约被破坏）。

### Step 4 — `ChatRequest` 加 `thinking` 字段（末位第 9 参）
- record 末位加 `ThinkingConfig thinking`；compact 构造器加 `thinking = thinking != null ? thinking : null`。
- 更新 record 内三处 canonical 调用：`of:33`、`withSystem:38`、`Builder.build:86` —— 各补 `null`（不注入）。
- Builder 加 `private ThinkingConfig thinking;` + `.thinking(ThinkingConfig)`；`build()` 末位透传。
- **影响面核查**：`grep -rn "new ChatRequest(" src/main` → 4 处（of/withSystem/Builder.build 在 record 内 + `ChatModelAdapter.extractChatRequest:166`）。其中 extractChatRequest 见 Step 9。
- 验证：编译通过（此时 extractChatRequest 会因 arity 报错，Step 9 修复）。

### Step 5 — `LlmResponse` 加 `reasoningContent` 字段（末位第 6 参 + 委托构造器）
- record 末位加 `String reasoningContent`；compact 构造器加 `reasoningContent = reasoningContent != null ? reasoningContent : ""`。
- **新增向后兼容 5 参委托构造器**（design §5.2）：`this(content, truncated, tokenUsage, toolCalls, responseMetadata, "")`。
- **影响面核查**：`grep -rn "new LlmResponse(" src` → main 1 处（`GenericChatClient.parseResponse:305`，Step 8 改 6 参）+ test ~15 处（**保持 5 参不动**，委托构造器兼容）。
- 验证：`./mvnw -q test-compile` 通过（委托构造器保证 test 不 break）。

### Step 6 — `StreamChunk` 加 `reasoningContent` 字段（末位第 5 参 + 委托构造器）
- record 末位加 `@Nullable String reasoningContent`；加 `hasReasoning()` 便捷方法。
- **新增向后兼容 4 参委托构造器**（design §5.3）：`this(text, toolCalls, finishReason, usage, null)`。
- **影响面核查**：`grep -rn "new StreamChunk(" src` → main 2 处（`GenericChatClient.readSse:177`、`emitRoundEnd:212`，**保持 4 参不动**）+ test ~7 处（**保持 4 参不动**）。破坏面 = 0。
- 验证：编译通过。

### Step 7 — `GenericChatClient` 请求注入
- `buildRequestBody` 末尾、`return body` 前插入 resolve 逻辑（design §3.1）。

### Step 8 — `GenericChatClient` 响应提取
- `parseResponse`：加 `reasoning_content` 提取（design §3.2 阻塞），改为 6 参 `new LlmResponse(..., reasoning)`。一次性提取完整字符串。
- `readSse`（design §3.2 流式）：新增 `StringBuilder reasoningBuf` 累积 reasoning delta（与 `ToolCallAccumulator` 同模式）。每个 delta 仍即时下发（保 TTFT），同时 `reasoningBuf.append()`。**两条收尾路径都必须传 reasoningBuf**：(a) finish_reason 触发 `emitRoundEnd(sink, acc, fr, usage, reasoningBuf.toString())`；(b) **`[DONE]` 兜底分支（现有 `:162-165` 的 `emitRoundEnd(sink, acc, null, null)` 必须补第 5 参 `reasoningBuf.toString()`）**——否则 `[DONE]` 收尾场景下累积 reasoning 丢失，R6 回传不完整。
- `emitRoundEnd`：签名扩展加 `String accumulatedReasoning` 参数，轮末汇总包改为 5 参 `new StreamChunk(null, toolCalls, fr, usage, accumulatedReasoning)`。**guard 修正**：现有 `:211` 的 early-return guard `if (toolCalls.isEmpty() && fr == null && usage == null) return;` 在 `[DONE]` 场景下三者皆 null 但 `accumulatedReasoning` 可能非空——guard 改为 `if (toolCalls.isEmpty() && fr == null && usage == null && (accumulatedReasoning == null || accumulatedReasoning.isEmpty())) return;`。
- `buildRequestBody`（design §3.4 断点 2）：assistant + tool_calls 分支（L247-249），在现有 `content` + `tool_calls` 之后追加 reasoning_content 回传：若 `msg.metadata().get("reasoning_content")` 为非空 String 则 `m.put("reasoning_content", s)`。

### Step 9 — `ChatModelAdapter` 边界暴露 + extractChatRequest 补参
- **extractChatRequest:166** 的 `new ChatRequest(..., tools)` 补末位 `null`（per-request thinking 不经 Spring AI Prompt 传递，走候选默认——design §3.1 per-request 限制）。**这是 Step 4 加字段后唯一的外部编译错误点，必须修复。**
- `wrapAsChatResponse`：构造带 `reasoning_content` metadata 的 AssistantMessage（design §3.3 阻塞路径）——阻塞路径一次性从 `LlmResponse.reasoningContent()` 取完整值放入 metadata。
- `extractHistory`（design §3.4 断点 1）：AssistantMessage + tool_calls 分支（L208-220），在现有 `tool_calls` metadata 组装之后追加 reasoning_content 提取：若 `am.getMetadata().get("reasoning_content")` 为非空 String 则写入 `MessageInformation.metadata`。**注意**：仅 tool_calls 分支需要回传——无 tool_calls 的 assistant 消息走 else 分支（L226），不注入 reasoning_content（与 DeepSeek 纯多轮忽略一致）。
- `stream()` 分派重构（design §3.3 完整伪代码）：现有 `:68-82` 二分 `if(hasToolCall) else` 改为三分派 `hasToolCall → reasoning-only(hasReasoning && !hasText) → else`。**分派顺序**：必须先判 `hasToolCall()`（轮末汇总包可能同时 hasReasoning），再判 reasoning-only，最后 else 兜底 text/STOP/LENGTH。toolCall 分支与 STOP 末包从 `chunk.reasoningContent()` 读取完整累积值放入 metadata；reasoning-only 分支构造 `ReasoningAssistantMessage("", Map.of("reasoning_content", ...))` 即时下发。`StreamChunk` 需新增 `hasReasoning()`（Step 6）。
- 扩展 `ToolCallAssistantMessage(content, metadata, toolCalls)`；新增 `ReasoningAssistantMessage(content, metadata)`。

### Step 10 — 测试（design §4 Payload 为依据）
- **Step 3 已含** `ThinkingBodyResolverTest`（请求体 + 键名契约）。
- 扩展 `GenericChatClientSseTest`：
  - 新增 `reasoningContentStreamed()`（AC6）：喂 fake SSE 含 `reasoning_content` delta，断言先产出 reasoning chunk（仅 reasoningContent 非空）再产出 text chunk。
  - 新增 reasoning 累积测试（AC6 扩展）：喂多帧 fake SSE，每帧含不同 reasoning_content delta 片段 + 末帧含 finish_reason=tool_calls，断言轮末汇总包的 `reasoningContent` 是所有片段的完整拼接（非最后一个片段），同时中间 reasoning chunk 各自即时下发。
  - 新增 `[DONE]` 收尾路径测试（design §3.2 BLOCKER 修复）：喂多帧 fake SSE 含 reasoning_content delta，末帧**无 finish_reason** 而以 `[DONE]` 结束，断言轮末汇总包的 `reasoningContent` 仍是所有片段的完整拼接（验证 `[DONE]` 分支也传了 reasoningBuf 且 guard 未跳过）。
  - 新增 reasoning 与 content 同帧场景（复核 missing_coverage）：同一 delta 同时含 reasoning_content 与 content 时两者都被提取。
  - 现有用例不回归（AC8）。
- **新增 `parseResponse` 阻塞测试（AC5）**（复核 missing_coverage）：构造含 `reasoning_content` 的 fake 阻塞 JSON，断言 `LlmResponse.reasoningContent()` 返回该文本；无该字段时返回空串。`parseResponse` 当前为 `private`，**改为 package-private**（去掉 `private` 修饰符，与已有 `static void readSse` 的 package-private 可见性一致），新建 `GenericChatClientTest`（同包）直接测试，避免反射脆弱性。
- **扩展 `ChatModelAdapterTest`（AC7）**（复核 missing_coverage）：mock delegate 返回带 `reasoningContent` 的 `LlmResponse`，断言 `adapter.call(prompt)` 的 `getResult().getOutput().getMetadata().get("reasoning_content")` 非空且等于 mock 值。
- **新增 reasoning_content 回传测试（AC10）**：`GenericChatClientTest`（同 Step 10 新建）中构造含 tool_calls + reasoning_content 历史的 `ChatRequest`，断言 `buildRequestBody` 输出的 assistant message 含 `reasoning_content` 字段；`ChatModelAdapterTest` 中构造带 reasoning_content metadata 的 `AssistantMessage` 历史，断言 `extractHistory` 提取后 `MessageInformation.metadata` 含 `reasoning_content`，且经 `buildRequestBody` 回传。

### Step 11 — YAML 配置示例（文档性，按 OQ1 决策 A）
- `application-stable.yml` 给 `qwen3-max` 补 `params.thinking`（BUDGET）示例以启用思考；其余候选按需。
- **非流式思考已确认无风险**（design §4.2）：百炼官方文档 FAQ 确认商业版模型（含 `qwen3-max`）支持非流式同步输出（`stream:false` + `enable_thinking:true`），`parameter.enable_thinking only support stream call` 错误仅限开源版模型。本项目候选均为商业版，阻塞路径 + BUDGET 方言完全可用，无需前置实测。
- 仅作示例，不强制（AC3 保证未配即零行为变更）。

## 验证命令

```bash
# 编译（每个 Step 后）
./mvnw -q compile

# 测试编译（验证委托构造器是否消除 test 破坏面）
./mvnw -q test-compile

# 思考参数相关单测
./mvnw -q test -Dtest='ThinkingBodyResolverTest,GenericChatClientSseTest,ChatModelAdapterTest'

# 全量编译 + 全量测试（防回归，确认 record 加字段无遗漏调用点）
./mvnw -q test-compile && ./mvnw -q verify
```

## 风险点与回滚

| 风险 | 缓解 |
|---|---|
| record 加字段遗漏 canonical 调用点致编译失败 | Step 4/5/6 各自先 `grep` 核查；编译器强制暴露遗漏；委托构造器消除 test 破坏面 |
| `StreamChunk` 字段顺序变化影响 SSE 测试断言 | 测试用 accessor（`chunk.text()` 等）而非位置，顺序变化不影响；委托构造器保证旧签名兼容 |
| 流式 reasoning chunk 改变 chunk 序列语义 | `readSse` 内 reasoning 与 content 都即时下发，不进入 `ToolCallAccumulator`，互不干扰 |
| reasoning-only chunk 空 content 影响 TokenCountingChatModel | design §3.3 已分析：reason chunk 不携 usage，不触发 token 误计；实现后核查 |
| 工具调用多轮 reasoning_content 未回传致 DeepSeek 400 | design §3.4 已纳入：extractHistory + buildRequestBody 两处断点修复（AC10）；Step 9/10 覆盖 |
| `[DONE]` 收尾路径丢失累积 reasoning | design §3.2 已修复：`[DONE]` 分支补 5 参 emitRoundEnd + guard 加 accumulatedReasoning 检查；Step 10 新增 `[DONE]` 路径测试 |
| 回滚 | 纯增量、无 schema/迁移；`git revert` 单提交即可恢复全部签名 |

## 审查门（task.py start 前）

- [x] OQ1 已决策（采纳方案 A，见 prd.md）
- [x] prd.md / design.md / implement.md 经复核子智能体审阅，3 个 MAJOR 问题已修订（调用点全清单、extractChatRequest 补参、DeepSeek 文档引用）
- [x] 二次审查（官方文档核查 + 源码验证）：修正 effort 取值归属（GLM 全档 vs DeepSeek 四档）、百炼方言举例（GLM 跨平台而非百炼多方言）、AC1 reasoning_effort 缺失场景、parseResponse 可见性决策、stream 分支优先级、MiniMax-M3 方言 out-of-scope 标注
- [x] 工具调用 reasoning_content 回传从"本期不做"改为"本期纳入"（R6/AC10/design §3.4/§6.3）——前提核查：qwen3-max 作为 default-model 同时 supports-thinking，Agent 工具调用 + 思考模式会同时发生
- [x] Spring AI 1.1.6 源码核实（ToolCallAdvisor + MessageAggregator + DefaultToolCallingManager）：修正 design §3.2/§3.3/§3.4 + prd R6 中的事实性错误——原述"ToolCallAdvisor 不累积自定义 metadata"不成立；实际是 `MessageAggregator.putAll` 对同 key 做 last-writer-wins（非拼接），`DefaultToolCallingManager` 经 `.properties()` 完整保留 metadata。readSse 累积方案仍然正确，但原因从"metadata 不被累积"修正为"putAll 非拼接语义"
- [x] `implement.jsonl` / `check.jsonl` 含真实 spec 条目（`task.py validate` 通过）
- [x] 三次审查（BLOCKER + MAJOR + MINOR 修复）：`[DONE]` 兜底路径补 reasoningBuf 传参 + emitRoundEnd guard 加 reasoning 检查（design §3.2 + implement Step 8）、stream() 完整三分派伪代码（design §3.3 + Step 9）、ThinkingConfig 长期重构路径标注（§2.1）、百炼非流式思考风险标注（§4.2 + Step 11）、百炼工具调用 reasoning_content 回传标注 [INFERENCE]（§6.3 + prd R6）、DeepSeek effort 映射因模型而异修正（prd L29）、DeepSeek 思考模式 temperature/top_p 静默失效标注（§4.1）、extraParams 技术债标注（§5.4）
- [x] 四次审查（用户复核修正）：temperature/top_p 在思考模式下**全厂商失效**（非仅 DeepSeek，GLM 示例中的 temperature 为兼容性保留不生效）——修正 design §4.1；百炼非流式思考**确认无风险**（商业版模型含 qwen3-max 支持 `stream:false` + `enable_thinking:true`，错误码仅限开源版）——修正 design §4.2 + implement Step 11 + 风险表移除该行
