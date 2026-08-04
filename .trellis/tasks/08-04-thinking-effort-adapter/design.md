# 技术设计 — 思考程度参数统一适配

> 对应 `prd.md`。本文件只讲技术设计；执行清单见 `implement.md`。

## 1. 架构边界

改动全部落在 `com.smart.rag.infrastructure.llm` 包内，**不触碰 chat/rag/agent 业务层**。符合 `llm-spi.md` §2：SPI 层保持纯净，Spring AI 桥接集中在 `adapter/`。

```
infrastructure/llm/
├── ThinkingConfig.java          ← 新增：归一化思考意图（厂商无关）
├── ThinkingDialect.java         ← 新增：方言枚举 (EFFORT / BUDGET)
├── ThinkingBodyResolver.java    ← 新增：Config + Dialect → 请求体字段
├── ChatRequest.java             ← 改：+thinking 字段（末位，向后兼容）
├── LlmResponse.java             ← 改：+reasoningContent 字段（末位 + 委托构造器）
├── StreamChunk.java             ← 改：+reasoningContent 字段（末位 + 委托构造器）
├── client/generic/
│   └── GenericChatClient.java   ← 改：buildRequestBody 注入 + parse/readSse 提取
└── adapter/
    └── ChatModelAdapter.java    ← 改：reasoning_content → AssistantMessage.metadata + extractChatRequest 补参
```

## 2. 新增类型契约

### 2.1 `ThinkingConfig`（record，厂商无关）

```java
public record ThinkingConfig(
    boolean enabled,
    String reasoningEffort,   // max|xhigh|high|medium|low|minimal|none；BUDGET 方言忽略
    Integer budgetTokens      // token 上限；EFFORT 方言忽略
) {
    public static ThinkingConfig disabled()          { return new ThinkingConfig(false, null, null); }
    public static ThinkingConfig effort(String e)    { return new ThinkingConfig(true, e, null); }
    public static ThinkingConfig budgeted(int tokens){ return new ThinkingConfig(true, null, tokens); }
}
```

**为什么是 record 而非 POJO**：不可变值对象，无 YAML 绑定需求，与同包 `LlmResponse`/`StreamChunk` 风格一致。

> **长期重构路径**：当前 `ThinkingConfig` 用联合 record 承载 EFFORT + BUDGET 两种方言的参数，靠注释"另一方言忽略"约束。2 方言可用；但若方言超过 3 个（如 MiniMax-M3 的 `thinking:"adaptive"` 字符串值方言）或参数形状差异增大（如百炼 Qwen 3.7+ 的 `preserve_thinking` 布尔参数），应重构为 sealed interface + 各方言独立 config record（如 `EffortThinkingConfig` / `BudgetThinkingConfig` / `AdaptiveThinkingConfig`），由 `ThinkingDialect` 决定实例化哪个 record。本期不重构——当前 2 方言 + 3 字段的复杂度尚不足以触发此重构。

### 2.2 `ThinkingDialect`（enum）

```java
public enum ThinkingDialect {
    EFFORT,   // thinking.type + reasoning_effort
    BUDGET    // enable_thinking + thinking_budget
}
```

**为什么按 dialect 而非 providerId/model 判断**：同一模型在不同平台方言不同——GLM-5.2 在智谱自有 API（`open.bigmodel.cn`）用 EFFORT 方言（`thinking.type` + `reasoning_effort`），在百炼平台（`dashscope.aliyuncs.com`）用 BUDGET 方言（`enable_thinking` + `thinking_budget`）。因此既不能按 provider 也不能按 model 名推断方言，只能靠显式 `dialect` 配置，避免 `if(provider.equals("bailian"))` 这种脆弱推断。MiniMax-M3 用第三种方言 `thinking: "adaptive"/"disabled"`（字符串值，非对象），本期未纳入 `ThinkingDialect` 枚举——未来添加需扩展 enum + resolver。

### 2.3 `ThinkingBodyResolver`（纯函数工具类）

```java
public final class ThinkingBodyResolver {
    private ThinkingBodyResolver() {}

    /** Config + Dialect → 请求体字段（有序，key 稳定） */
    public static Map<String, Object> resolve(ThinkingConfig cfg, ThinkingDialect dialect) {
        var fields = new LinkedHashMap<String, Object>();
        if (dialect == ThinkingDialect.EFFORT) {
            fields.put("thinking", Map.of("type", cfg.enabled() ? "enabled" : "disabled"));
            if (cfg.enabled() && cfg.reasoningEffort() != null)
                fields.put("reasoning_effort", cfg.reasoningEffort());
        } else { // BUDGET
            fields.put("enable_thinking", cfg.enabled());
            if (cfg.enabled() && cfg.budgetTokens() != null)
                fields.put("thinking_budget", cfg.budgetTokens());
        }
        return fields;
    }

    /** 从 candidate.params 提取方言；未配默认 EFFORT */
    public static ThinkingDialect extractDialect(Map<String, Object> params) {
        Object t = params == null ? null : params.get("thinking");
        if (t instanceof Map<?,?> m && m.get("dialect") instanceof String d)
            return "budget".equalsIgnoreCase(d) ? ThinkingDialect.BUDGET : ThinkingDialect.EFFORT;
        return ThinkingDialect.EFFORT;
    }

    /** 从 candidate.params 提取默认 ThinkingConfig；未配返回 null（= 不注入） */
    public static ThinkingConfig extractDefault(Map<String, Object> params) {
        Object t = params == null ? null : params.get("thinking");
        if (!(t instanceof Map<?,?> m)) return null;
        ThinkingDialect d = extractDialect(params);
        boolean enabled = !(m.get("enabled") instanceof Boolean b) || b; // 默认 true
        if (d == ThinkingDialect.EFFORT) {
            Object e = m.get("reasoning-effort");
            return new ThinkingConfig(enabled, e instanceof String s ? s : null, null);
        }
        Object b = m.get("thinking-budget");
        Integer budget = b instanceof Number n ? n.intValue() : null;
        return new ThinkingConfig(enabled, null, budget);
    }
}
```

**YAML 键名契约（强约束）**：`extractDefault` 用 `m.get("reasoning-effort")` / `m.get("thinking-budget")`（kebab-case），YAML **必须**用同名键。Spring Boot `@ConfigurationProperties` 绑定 `params: Map<String,Object>` 时保留 YAML 原始键名，故 camelCase（`reasoningEffort`）会被静默忽略。implement.md Step 3 需对 `ThinkingBodyResolver` 增加单测覆盖键名大小写敏感性，防止配置静默失效。

> **BUDGET 方言字段层级假设（强约束）**：`resolve()` 返回的 `enable_thinking`/`thinking_budget` 等字段为**顶层 key**，经 `buildRequestBody` 的 `body.putAll(...)` 直接放到请求体顶层。这依赖百炼使用 **OpenAI 兼容端点**（`dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`，当前 `application-stable.yml:99` 配置）——该端点下 `enable_thinking`/`thinking_budget`/`preserve_thinking` 均为顶层参数。
>
> 百炼另有 **DashScope 原生接口**（`/api/v1/services/aigc/text-generation/generation`），其请求体结构不同——参数需嵌套到 `"parameters": {...}` 对象内（`messages` 也需嵌套到 `"input"` 对象），DeepSeek/GLM 无此结构。`GenericChatClient` 统一走 OpenAI 兼容格式，不使用原生接口，故当前无影响。但若未来某候选切换到 DashScope 原生端点，`ThinkingBodyResolver.resolve` 的顶层映射不再适用——需要按接口类型决定字段包装方式（原生接口需嵌套到 `parameters` 对象）。本期不处理此场景（无候选使用原生端点），仅作假设记录。

## 3. 数据流

### 3.1 请求注入（`buildRequestBody` 末尾，return 前）

```java
// 解析优先级：per-request > candidate.params > 不注入
ThinkingConfig cfg = request.thinking() != null
    ? request.thinking()
    : ThinkingBodyResolver.extractDefault(candidate.params());
if (cfg != null) {
    ThinkingDialect dialect = ThinkingBodyResolver.extractDialect(candidate.params());
    body.putAll(ThinkingBodyResolver.resolve(cfg, dialect));
}
return body;
```

注意：**不**以 `candidate.supportsThinking()` 作为注入门槛——支持思考是能力声明，注入与否完全由 `params.thinking` / per-request 决定（满足 AC3、AC4、R5）。

**per-request 传递限制（明确约束）**：`ChatRequest.thinking`（AC4 的非空分支）仅服务于**直接构造 `ChatRequest` 的调用方**（如未来 RAG 按查询复杂度动态决策）。经 Spring AI `ChatClient` API 走的路径，`ChatModelAdapter.extractChatRequest` 从 `Prompt` 无法取到 per-request thinking（Spring AI Prompt 不携带该字段），故该路径只能走候选 `params.thinking` 默认。本期不扩展 Spring AI 边界传递 per-request thinking（scope 控制）。

### 3.2 响应提取

**阻塞** `parseResponse`：
```java
String reasoning = message.path("reasoning_content").asText("");
return new LlmResponse(content, truncated, tokenUsage, toolCalls, Map.of(), reasoning); // 末位新参
```

**流式** `readSse`，在 content 处理之前（思考先于回答，保 TTFT）。

reasoning_content 在 SSE 中是分片 delta，但工具调用多轮回传需要**完整字符串**（DeepSeek 要求"完整回传"，否则 400）。经 Spring AI 1.1.6 源码核查：`MessageAggregator`（`spring-ai-model` L98-100）确实合并 metadata，但用 `messageMetadataMapRef.get().putAll(metadata)`——**同 key 是 last-writer-wins（最后写入覆盖），不是字符串拼接**（对比 content 用 `StringBuilder.append`、tool_calls 用 `addAll`）。因此若每个 reasoning 片段各自放入 `metadata={"reasoning_content": "片段"}`，聚合后只剩最后一个片段。`DefaultToolCallingManager.buildConversationHistoryBeforeToolExecution`（L170-176）经 `.properties(assistantMessage.getMetadata())` **完整保留** metadata——回传链路本身没有断点，断在聚合阶段的拼接语义。

解决：在 `readSse` 层累积 reasoning 片段（与 `ToolCallAccumulator` 累积 tool_call delta 同模式），轮末汇总包携带完整累积值。两个职责分离——即时下发片段（保 TTFT，给前端展示），轮末汇总包携带完整值（供回传）：
```java
ToolCallAccumulator acc = new ToolCallAccumulator();
StringBuilder reasoningBuf = new StringBuilder();  // 累积 reasoning 片段

// 1) reasoning delta → 即时下发 + 累积
JsonNode reasoningNode = delta.path("reasoning_content");
if (reasoningNode.isTextual() && !reasoningNode.asText().isEmpty()) {
    reasoningBuf.append(reasoningNode.asText());
    sink.next(new StreamChunk(null, null, null, null, reasoningNode.asText())); // 片段即时下发
}

// 2) content delta → 即时下发（不变）
...

// 3a) finish_reason → 轮末汇总包（携带完整累积 reasoning）
emitRoundEnd(sink, acc, frNode.asText(), usage, reasoningBuf.toString());
```

> **`[DONE]` 兜底路径同样必须传 reasoningBuf**（`readSse` 现有 `:162-165` 的 `[DONE]` 分支当前为 4 参 `emitRoundEnd(sink, acc, null, null)`——若不补第 5 参，累积的 reasoning 在 `[DONE]` 收尾场景下丢失，R6 回传不完整 → DeepSeek 400）。实现时 `[DONE]` 分支改为 5 参：
```java
// 3b) [DONE] 兜底 → 同样携带完整累积 reasoning
if ("[DONE]".equals(data)) {
    emitRoundEnd(sink, acc, null, null, reasoningBuf.toString()); // ← 5 参，勿漏
    return;
}
```

`emitRoundEnd` 签名扩展 + **guard 修正**。现有 `emitRoundEnd:211` 的 early-return guard 为 `if (toolCalls.isEmpty() && fr == null && usage == null) return;`——在 `[DONE]` 场景下三者皆 null，即使 `accumulatedReasoning` 非空也会跳过发送。guard 必须额外检查 reasoning：
```java
private static void emitRoundEnd(FluxSink<StreamChunk> sink, ToolCallAccumulator acc,
                                 String finishReason, TokenUsage usage,
                                 String accumulatedReasoning) {
    java.util.List<StreamChunk.ToolCallDelta> toolCalls = acc.drain();
    StreamChunk.FinishReason fr = mapFinishReason(finishReason);
    boolean noReasoning = accumulatedReasoning == null || accumulatedReasoning.isEmpty();
    if (toolCalls.isEmpty() && fr == null && usage == null && noReasoning) return; // ← guard 含 reasoning
    sink.next(new StreamChunk(null, toolCalls.isEmpty() ? null : toolCalls, fr, usage, accumulatedReasoning)); // 5 参
}
```

### 3.3 Spring AI 边界（`ChatModelAdapter`）

阻塞 `wrapAsChatResponse`：把 `reasoning_content` 放进 `AssistantMessage.metadata`：
```java
Map<String, Object> msgMeta = llmResp.reasoningContent() != null && !llmResp.reasoningContent().isEmpty()
    ? Map.of("reasoning_content", llmResp.reasoningContent())
    : Map.of();
AssistantMessage assistantMsg = springToolCalls.isEmpty()
    ? new ReasoningAssistantMessage(content, msgMeta)
    : new ToolCallAssistantMessage(content, msgMeta, springToolCalls);
```

`ToolCallAssistantMessage` 构造器扩展为 `(content, metadata, toolCalls)`，内部 `super(content, metadata, toolCalls, List.of())`。新增 `ReasoningAssistantMessage(content, metadata)` 调 `super(content, metadata, List.of(), List.of())`。Spring AI 1.1.6 `AssistantMessage` 4 参签名经源码核实：`protected AssistantMessage(String content, Map<String,Object> properties, List<ToolCall> toolCalls, List<Media> media)`，第 2 参 properties 经 `AbstractMessage` 映射为 metadata（复核已确认）。

流式 `stream()` 分支（顺序：reasoning → toolCall → text）：
现有 `stream():68-82` 是二分 `if(hasToolCall) else`。新设计需处理 4 种 chunk 类型，完整分派伪代码（顺序 toolCall → reasoning-only → text/STOP）：
```java
return delegate.chatStream(request)
    .map(chunk -> {
        // 1) tool_call 轮末汇总包（完整 toolCalls + 可能携带完整 reasoning）
        if (chunk.hasToolCall()) {
            Map<String, Object> meta = chunk.hasReasoning()
                ? Map.of("reasoning_content", chunk.reasoningContent())
                : Map.of();
            AssistantMessage msg = new ToolCallAssistantMessage(content, meta,
                toSpringToolCallsFromDeltas(chunk.toolCalls()));
            Generation gen = new Generation(msg, ChatGenerationMetadata.builder()
                .finishReason("tool_calls").build());
            return new ChatResponse(List.of(gen), buildResponseMetadata(chunk));
        }
        // 2) reasoning-only chunk（无 text、无 tool、有 reasoning）→ 即时片段，仅 metadata
        if (chunk.hasReasoning() && !chunk.hasText()) {
            AssistantMessage msg = new ReasoningAssistantMessage("",
                Map.of("reasoning_content", chunk.reasoningContent()));
            return new ChatResponse(List.of(new Generation(msg)),
                buildResponseMetadata(chunk)); // finishReason=null, 无 usage
        }
        // 3) text chunk 或 STOP/LENGTH 末包 → 现有逻辑 + 若有 reasoning 则补 metadata
        String content = chunk.hasText() ? chunk.text() : "";
        Map<String, Object> meta = chunk.hasReasoning()
            ? Map.of("reasoning_content", chunk.reasoningContent())
            : Map.of();
        AssistantMessage msg = new ReasoningAssistantMessage(content, meta);
        Generation gen = new Generation(msg, buildGenerationMetadata(chunk.finishReason()));
        return new ChatResponse(List.of(gen), buildResponseMetadata(chunk));
    });
```

分派顺序约束：**必须**先判 `hasToolCall()`，再判 reasoning-only（`hasReasoning() && !hasText()`），最后落 else。理由：tool_call 轮末汇总包可能同时 `hasReasoning()` 为 true（携带完整累积值），若 reasoning-only 分支在前会错误截获它。`StreamChunk` 需新增 `hasReasoning()` 便捷方法（implement Step 6）。
- `chunk.hasReasoning()` 且无 text/tool → 构造 `AssistantMessage(content="", metadata={"reasoning_content":...})`，`finishReason` 不设。此为即时片段，仅供前端展示。注意：这些中间片段的 metadata 经 `MessageAggregator.putAll` 会被后续片段覆盖（§3.2），但最终由轮末汇总包的完整值取代——**无害**（last-writer-wins 保证轮末完整值是最终值）。
- **tool_call 汇总包**（轮末）→ 从 `chunk.reasoningContent()` 读取**完整累积值**（由 `readSse` 累积，§3.2），放入 metadata：
```java
if (chunk.hasToolCall()) {
    Map<String, Object> meta = chunk.hasReasoning()
        ? Map.of("reasoning_content", chunk.reasoningContent())
        : Map.of();
    AssistantMessage msg = new ToolCallAssistantMessage(content, meta,
        toSpringToolCallsFromDeltas(chunk.toolCalls()));
    Generation gen = new Generation(msg, ChatGenerationMetadata.builder()
        .finishReason("tool_calls").build());
    return new ChatResponse(List.of(gen), buildResponseMetadata(chunk));
}
```
  **关键**：轮末汇总包携带的 reasoning_content 是 `readSse` 累积的完整字符串。经 Spring AI 1.1.6 源码核实：`MessageAggregator` 对 metadata 做 `putAll`（last-writer-wins），轮末汇总包是最后一个含 `reasoning_content` 的 chunk，其完整值覆盖所有中间片段——聚合后的 `AssistantMessage.metadata` 中 `reasoning_content` 是完整的。`DefaultToolCallingManager.buildConversationHistoryBeforeToolExecution`（L174）经 `.properties(assistantMessage.getMetadata())` 保留完整 metadata，`extractHistory`（§3.4 断点1）可完整提取。
- 纯 text chunk → 保持现有 `new AssistantMessage(content)`（保 TTFT，不因元数据拖慢）。
- STOP/LENGTH 末包（finishReason + usage）→ 现有逻辑不变；若携带完整 reasoning（§3.2 累积），同样放入 metadata。

**注意**：reasoning-only chunk 的 content 为空串。需核查下游 `TokenCountingChatModel`（P4b）对空 content chunk 的累计行为——若按字符数累计则空串无影响；若按 chunk 计数则会产生零计费 chunk。本期 reason-only chunk 不携带 usage（usage 仍在 STOP 末包），故不触发 token 误计。

**为什么不放进 `ChatResponse.metadata` 而放 message metadata**：思考内容是"这一轮回答的"属性，语义归属消息本身；`ChatResponseMetadata` 已被 usage 占用语义。message metadata 是 Spring AI 为附加数据设计的标准槽位。

### 3.4 工具调用 reasoning_content 回传（`extractHistory` + `buildRequestBody`）

工具调用多轮（ReAct）中，上一轮的 reasoning_content 必须回传到下一轮请求——DeepSeek 官方文档明确："携带了 tools 参数的请求，在后续所有请求中，必须完整回传 reasoning_content，否则 API 返回 400"。回传经 2 处现有方法增量修复（`MessageInformation.metadata` 已存在，无需新增类型）。

**断点 1 — `ChatModelAdapter.extractHistory`**，AssistantMessage + tool_calls 分支（现有 L208-220）：
```java
Map<String, Object> meta = new LinkedHashMap<>();
meta.put("tool_calls", tcs);
Object rc = am.getMetadata() != null ? am.getMetadata().get("reasoning_content") : null;
if (rc instanceof String s && !s.isEmpty()) meta.put("reasoning_content", rc);
builder.add(MessageInformation.assistant(am.getText() != null ? am.getText() : "", meta));
```

**断点 2 — `GenericChatClient.buildRequestBody`**，assistant + tool_calls 分支（现有 L247-249）：
```java
m.put("content", msg.content() != null ? msg.content() : "");
m.put("tool_calls", msg.metadata().get("tool_calls"));
Object rc = msg.metadata().get("reasoning_content");
if (rc instanceof String s && !s.isEmpty()) m.put("reasoning_content", s);
```

回传闭合链路（阻塞 + 流式统一）：

**阻塞路径**：Round N `parseResponse`（§3.2，一次性提取完整 reasoning）→ `wrapAsChatResponse` 放入 `AssistantMessage.metadata` → `ToolCallingManager.buildConversationHistoryBeforeToolExecution`（`.properties(metadata)` 保留）→ Round N+1 `extractHistory` 提取 → `buildRequestBody` 输出 → 厂商收到完整 `reasoning_content`。

**流式路径**：Round N `readSse`（§3.2，累积 reasoningBuf）→ `emitRoundEnd` 携带完整累积值到轮末汇总包 → `stream()` toolCall 分支放入 `AssistantMessage.metadata`（§3.3）→ `MessageAggregator` 聚合所有 chunk 的 metadata（`putAll`，轮末汇总包的完整值作为 last-writer 胜出）→ `ToolCallingManager.buildConversationHistoryBeforeToolExecution`（`.properties(metadata)` 保留）→ Round N+1 `extractHistory` 提取 → `buildRequestBody` 输出 → 厂商收到完整 `reasoning_content`。

两条路径的断点 1（extractHistory）和断点 2（buildRequestBody）代码完全相同——差异仅在 reasoning_content 如何到达 `AssistantMessage.metadata`：阻塞经 `parseResponse` 一次到位，流式经 `readSse` 累积 → `emitRoundEnd` 汇总 → `MessageAggregator.putAll` 保留 last-winner。两条路径的 `DefaultToolCallingManager` 都经 `.properties()` 保留完整 metadata。

**仅工具调用场景回传**：DeepSeek 官方文档确认纯多轮对话（无 tools）中 reasoning_content 会被 API 忽略（无害），故 `buildRequestBody` 仅在 `msg.metadata().containsKey("tool_calls")` 的分支回传——普通 assistant 消息（无 tool_calls）不注入 reasoning_content，与 DeepSeek 纯多轮忽略行为一致，避免请求体冗余。

## 4. 配置后的 Payload 数据结构

### 4.1 EFFORT 方言请求体（GLM-5.2 / DeepSeek）

```json
{
  "model": "glm-5.2",
  "messages": [
    {"role": "system", "content": "你是一个专业助手"},
    {"role": "user", "content": "分析量子计算的基本原理"}
  ],
  "stream": false,
  "max_tokens": 4096,
  "temperature": 1.0,
  "thinking": { "type": "enabled" },
  "reasoning_effort": "high"
}
```

> **思考模式下 temperature/top_p 全厂商失效**：经三家官方文档核查，思考模式下 `temperature`/`top_p`（及 `presence_penalty`/`frequency_penalty`）在所有厂商均不起作用——DeepSeek 明确声明"设置参数不会报错，但也不会生效"（[DeepSeek](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode) §输入输出参数）；GLM 思考模式示例虽含 `temperature:1.0` 但实际为兼容性保留、不生效；百炼 Qwen 思考模式示例亦不设置这些参数。这是跨方言（EFFORT + BUDGET）的统一行为，非厂商差异。`buildRequestBody` 无条件注入 `temperature`/`top_p`，启用思考后这些参数被厂商静默忽略——本期不特殊处理（透传不报错），配置者需知悉。

关闭思考（AC9）：
```json
{ ..., "thinking": { "type": "disabled" } }
```

### 4.2 BUDGET 方言请求体（Qwen / 百炼）

```json
{
  "model": "qwen3-max",
  "messages": [...],
  "stream": false,
  "max_tokens": 4096,
  "enable_thinking": true,
  "thinking_budget": 16000
}
```

> **百炼非流式思考支持确认**：经百炼官方文档 FAQ 核实（[深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking) §常见问题），商业版深度思考模型（含 `qwen-plus`、**`qwen3-max`**、`qwen-flash` 等）**支持非流式（同步）输出**——`stream:false` + `enable_thinking:true` 组合可正常工作。`parameter.enable_thinking only support stream call` 错误仅限开源版模型（`qwen3-235b-a22b`、`qwen3-32b` 等），本项目候选均为商业版，非流式思考无风险。阻塞路径（`GenericChatClient.chat()`）+ BUDGET 方言完全可用。

### 4.3 阻塞响应（两方言共用）

```json
{
  "model": "glm-5.2",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "reasoning_content": "让我从多个角度分析。首先考虑量子比特的叠加态和纠缠...",
      "content": "量子计算利用量子力学原理（叠加和纠缠）进行计算..."
    },
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 12, "completion_tokens": 358, "total_tokens": 370}
}
```

### 4.4 流式响应（SSE 逐行，思考先于回答）

```
data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"首先"}}]}
data: {"choices":[{"index":0,"delta":{"reasoning_content":"需要从叠加态..."}}]}
data: {"choices":[{"index":0,"delta":{"content":"量子"}}]}
data: {"choices":[{"index":0,"delta":{"content":"计算利用..."}}]}
data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":358,"total_tokens":370}}
data: [DONE]
```

## 5. 兼容性与破坏面控制（关键）

经全量核查（`grep -rn "new ChatRequest\(|new LlmResponse\(|new StreamChunk\(" src`），record 字段扩展的破坏面用**末位字段 + 向后兼容委托构造器**策略消除：

> 注：项目存在两个同名 `ChatRequest`——`infrastructure/llm/ChatRequest`（SPI 层，本次改动）与 `mode/ChatRequest`（web DTO，含 `enableThinking` 字段）。两者无关，测试中 `ChatServiceImpl*Test` 用的是 `mode.ChatRequest`，不受本次影响。下表只统计 `infrastructure.llm` 的三个 record。

### 5.1 `ChatRequest`（加 `thinking` 末位，第 9 参）

canonical 调用点（main，4 处）：
- `ChatRequest.of:33`、`withSystem:38`、`Builder.build:86` —— 这三处在 record 内部，随定义一并更新。
- `ChatModelAdapter.extractChatRequest:166` —— **必须**显式补第 9 参 `null`（Spring AI Prompt 不携带 per-request thinking，走候选默认）。

**无测试直接 new `infrastructure.llm.ChatRequest`**（测试构造走 mock `any(ChatRequest.class)` 或用 mode.ChatRequest）。破坏面 = 4 处 main，全在本任务改动文件内。

### 5.2 `LlmResponse`（加 `reasoningContent` 末位，第 6 参）

提供向后兼容委托构造器，消除全部测试破坏面：
```java
public record LlmResponse(String content, boolean truncated, TokenUsage tokenUsage,
                          List<ToolCall> toolCalls, Map<String,Object> responseMetadata,
                          String reasoningContent) {   // ← 末位新参
    // 向后兼容：旧 5 参签名（无 reasoningContent）
    public LlmResponse(String content, boolean truncated, TokenUsage tokenUsage,
                       List<ToolCall> toolCalls, Map<String,Object> responseMetadata) {
        this(content, truncated, tokenUsage, toolCalls, responseMetadata, "");
    }

调用点统计：main 1 处（`GenericChatClient.parseResponse:305`，改 6 参）+ test ~15 处（`IntentClassifierTest`/`Poc4`/`Poc5`/`ChatModelAdapterTest`/`EntitySeedExtractorTest`/`EntityEmbeddingServiceTest`，**保持 5 参不动**）。破坏面 = 1 处 main。

### 5.3 `StreamChunk`（加 `reasoningContent` 末位，第 5 参）

提供向后兼容委托构造器：
```java
public record StreamChunk(@Nullable String text, @Nullable List<ToolCallDelta> toolCalls,
                          @Nullable FinishReason finishReason, @Nullable TokenUsage usage,
                          @Nullable String reasoningContent) {   // ← 末位新参
    // 向后兼容：旧 4 参签名
    public StreamChunk(@Nullable String text, @Nullable List<ToolCallDelta> toolCalls,
                       @Nullable FinishReason finishReason, @Nullable TokenUsage usage) {
        this(text, toolCalls, finishReason, usage, null);
    }
}
```
调用点统计：main 2 处（`GenericChatClient.readSse:177`、`emitRoundEnd:212`，保持 4 参不动，reasoning 默认 null）+ test ~7 处（保持 4 参不动）。破坏面 = **0**（现有调用点全保留，仅新增 reasoning delta 处理处用 5 参）。

### 5.4 兼容性小结

| 维度 | 影响 |
|---|---|
| **现有候选**（未配 params.thinking） | 零影响。`extractDefault` 返回 null → 不注入，请求体字节级不变（AC3/R5） |
| **`LlmResponse`/`StreamChunk` 旧调用点** | 委托构造器保留旧签名，~22 处调用点无需改动 |
| **YAML** | 新增可选 `params.thinking.{dialect,enabled,reasoning-effort,thinking-budget}`；旧配置无需改动 |
| **`extraParams` 字段** | 保留不动（向后兼容），不用于思考参数——思考参数值得显式类型建模。建议加 `@Deprecated` + Javadoc 指向 `thinking` 字段，标记为技术债（两个"参数传递"机制并存——`extraParams` 死、`thinking` 活——对后来者造成困惑） |

## 6. 关键权衡

### 6.1 方言显式配置 vs 自动推断
选**显式 `dialect` 配置**。自动推断（按 provider/model 名匹配规则）看似省事，但百炼/火山等聚合平台同一 provider 多方言，规则会无限膨胀且易错。显式一行配置零歧义，符合 fail-fast（`llm-spi.md` 反模式节：不要静默掩盖配置错误）。

### 6.2 `reasoning_content` 边界暴露粒度
选**全链路透传 + message metadata 暴露**。SPI 层（LlmResponse/StreamChunk）完整保留，是厂商无关的能力；Spring AI 边界放 message metadata 是标准扩展点，不污染 ISP 契约。上层是否展示由业务层决定，本期不强加。

### 6.3 `reasoning_content` 工具调用多轮回传（本期纳入）

本期**纳入** reasoning_content 在工具调用多轮中的自动回传（详见 §3.4）。**前提修正**：原设计以"当前工具调用路径未接到思考模型"为由不做回传，经代码核查不成立——`ChatServiceImpl.java:106-108` 用 fallback chain 首候选（= default-model = `qwen3-max`）构建 Agent ChatClient，而 `qwen3-max` 同时 `supports-thinking: true`（`application-stable.yml:135`），且 implement.md Step 11 正要给它配 `params.thinking`。一旦配置完成，Agent ReAct 工具调用 + 思考模式会同时发生。

**厂商要求**：
- DeepSeek 官方文档明确："携带了 tools 参数的请求，在后续所有请求中，必须完整回传 reasoning_content，否则 API 返回 400"（[thinking_mode](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode) 工具调用节）。
- 百炼/Qwen：工具调用多轮中 reasoning_content 同样需参与上下文拼接。**[INFERENCE]** 百炼官方文档仅展示了纯多轮对话场景的 `preserve_thinking` 参数（[深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking) §跨轮次保留思考内容），未显式确认工具调用场景的 reasoning_content 回传要求；此处基于 DeepSeek 类比推断。实际无害（多传冗余字段不报错），但标注为推断而非文档确认。
- GLM（智谱）：文档未显式标注工具调用回传要求，但既然 reasoning_content 已透传到 metadata，回传是语义一致的。

**各厂商多轮行为差异**统一为：**工具调用场景无条件回传**（§3.4），纯多轮对话不回传（DeepSeek 忽略、无害）。修复仅 2 处现有方法内增量逻辑（`extractHistory` + `buildRequestBody`），无新类型，`MessageInformation.metadata` 已存在可承载。

### 6.4 委托构造器 vs 全量改调用点
选**委托构造器**。~22 处旧调用点零改动，破坏面从全量降到仅 5 处（4 处 ChatRequest main + 1 处 LlmResponse main）。代价是多 2 个构造器，但它们是纯委托、无逻辑，维护成本极低。

## 7. 回滚形态

纯增量改动，无 schema/迁移/破坏性 API 变更（委托构造器保证二进制兼容）。回滚 = revert 该任务提交。无数据持久化参与，无运行态需要迁移。
