# LLM 思考程度参数统一适配

## Goal

让 RAG/Chat/Agent 三层能够按厂商、按请求控制 LLM 的"思考程度"，并把模型返回的思考过程（`reasoning_content`）完整透传到上层。

统一适配**仅限 OpenAI 兼容格式**，不处理 Claude（`thinking.budget_tokens`）与 Gemini（`thinkingBudget`）的原生协议——它们在本项目经 OpenAI 兼容端点接入时同样适用本方案。

## Background

### 现状（已确认事实，均经代码核查）

- `ChatCandidate.supportsThinking` + YAML `supports-thinking: true` **已存在**，但仅作为能力标记，从不参与请求构建。`application-stable.yml:135` 的 `qwen3-max` 即配了 `supports-thinking: true` 但无任何思考参数注入。
- `ChatRequest.extraParams` 字段存在（`ChatRequest.java:20`），但 `GenericChatClient.buildRequestBody`（`:232-287`）**从不读取它**——死字段。
- `buildRequestBody` 只构建 `model/messages/stream/temperature/max_tokens/top_p/tools`，**无任何思考参数**。
- 响应侧 `parseResponse`（`:289-310`）只解析 `content/tool_calls/usage`，**丢弃 `reasoning_content`**；流式 `readSse`（`:150-204`）只读 `content/tool_calls/finish_reason`，**丢弃 `reasoning_content` delta**。
- `ModelGroup.deepThinkingModel` 可指向另一个候选做"深度思考模型"切换（`application-stable.yml:121`），这是候选级切换，与本次的"同候选内调思考程度"正交、不冲突。

### 厂商参数差异（来自官方文档核查）

OpenAI 兼容端点下，思考参数存在两种互不兼容的"方言"：

| 方言 | 字段 | 代表厂商 | 文档来源 |
|---|---|---|---|
| EFFORT | `thinking.type` + `reasoning_effort` | DeepSeek（v4-pro/flash）、智谱 GLM（5.2+） | [DeepSeek](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)、[智谱](https://docs.bigmodel.cn/cn/guide/capabilities/thinking) |
| EFFORT-lite | 仅 `reasoning_effort`（无 `thinking.type`） | OpenAI GPT-5（本项目当前无此候选） | [OpenAI](https://community.openai.com/t/request-for-compatibility-matrix-reasoning-effort-sampling-parameters-across-gpt-5-series/1371738) |
| BUDGET | `enable_thinking` + `thinking_budget` | Qwen / 百炼（qwen-plus/max/3.x） | [百炼深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking) |

EFFORT 取值因厂商而异：GLM 支持 `max|xhigh|high|medium|low|minimal|none` 全档（`low`/`medium` 映射为 `high`，`xhigh` 映射为 `max`）；DeepSeek 支持 `low|high|xhigh|max` 四档，但映射因模型而异——v4-flash 将 `xhigh` 映射为 `high`，v4-pro 将 `xhigh` 映射为 `max`（详见 [DeepSeek 映射表](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)）。适配器透传不校验取值，映射由厂商侧完成。BUDGET 的 `thinking_budget` 为整数 token 上限。本项目当前候选仅涉及 DeepSeek / GLM（EFFORT）与 Qwen（BUDGET），EFFORT-lite 仅作文档完整性列出。

## Requirements

### R1 — 归一化思考意图建模
新增厂商无关的 `ThinkingConfig`（开关 + effort/budget），承载于 `ChatRequest`，支持每请求覆盖候选默认。

### R2 — 双方言请求体注入
新增方言解析器，将 `ThinkingConfig` + 方言（EFFORT/BUDGET）映射为厂商要求的请求体字段，注入 `buildRequestBody`。方言由候选 `params.thinking.dialect` 显式声明。

### R3 — 思考内容响应透传
阻塞与流式两条路径均提取 `reasoning_content`：阻塞经 `LlmResponse.reasoningContent`；流式经 `StreamChunk.reasoningContent`（与 text 同为即时 chunk，保 TTFT）。

### R4 — Spring AI 边界暴露
`reasoning_content` 经 `AssistantMessage.metadata`（Spring AI 4 参构造器已有该槽位，`ChatModelAdapter.ToolCallAssistantMessage:158` 即用 `super(content, Map.of(), ...)`）向上暴露，key 为 `reasoning_content`，不破坏 ISP 契约（`llm-spi.md` §2）。

### R5 — 零行为变更默认
未在 `params.thinking` 显式配置的候选，请求体不注入任何思考参数，行为与现状完全一致。`supports-thinking: true` 仅声明能力，不触发注入。

### R6 — 工具调用多轮 reasoning_content 回传
当思考候选被用于工具调用（ReAct 多轮）时，上一轮的**完整** reasoning_content 经 `AssistantMessage.metadata` → `MessageInformation.metadata` → `buildRequestBody` assistant message 自动回传到下一轮请求。阻塞路径一次性提取完整字符串；**流式路径在 `readSse` 层累积所有 delta 片段，轮末汇总包携带完整拼接值**——Spring AI 1.1.6 `MessageAggregator` 对 metadata 用 `putAll`（last-writer-wins，非拼接），故累积必须在到达聚合器之前完成；`DefaultToolCallingManager` 经 `.properties()` 保留 metadata。避免 DeepSeek 等厂商工具调用场景的 400 错误（DeepSeek 官方文档："携带了 tools 参数的请求，在后续所有请求中，必须完整回传 reasoning_content"）。纯多轮对话（无 tools）不回传——DeepSeek 确认会被 API 忽略，无害。**[INFERENCE]** DeepSeek 回传要求经官方文档确认；百炼/Qwen 的工具调用场景回传要求未经百炼文档显式确认（仅展示纯多轮 `preserve_thinking`），此处基于 DeepSeek 类比——多传冗余字段无害。

## Acceptance Criteria

- [ ] **AC1**：EFFORT 方言候选（如 GLM-5.2）发出请求时，请求体含 `"thinking":{"type":"enabled"}`；若配了 `reasoning-effort` 则同时含 `"reasoning_effort":"<值>"`，未配时由厂商默认值生效。
- [ ] **AC2**：BUDGET 方言候选（如 qwen3-max）发出请求时，请求体含 `"enable_thinking":true` 与 `"thinking_budget":<N>`。
- [ ] **AC3**：未配 `params.thinking` 的候选，请求体无任何思考相关字段（与现状字节级一致）。
- [ ] **AC4**：`ChatRequest.thinking` 非空时覆盖候选 `params.thinking` 默认；为 null 时回落候选默认；两者皆无时不注入。
- [ ] **AC5**：阻塞响应含 `reasoning_content` 时，`LlmResponse.reasoningContent()` 返回该文本；无则返回空串。
- [ ] **AC6**：流式响应的 `reasoning_content` delta 以独立 `StreamChunk`（仅 `reasoningContent` 非空）即时下发，先于 `content`。
- [ ] **AC7**：`reasoning_content` 经 `AssistantMessage.getMetadata().get("reasoning_content")` 可在 Spring AI 边界取得。
- [ ] **AC8**：`GenericChatClientSseTest` 新增覆盖 `reasoning_content` delta 的用例通过；现有用例不回归。
- [ ] **AC9**：关闭思考（EFFORT `type:disabled` / BUDGET `enable_thinking:false`）时请求体正确表达，模型直接回答。
- [ ] **AC10**：工具调用多轮场景中，上一轮 assistant 消息（含 tool_calls）的**完整** reasoning_content 经 `extractHistory` 提取后，经 `buildRequestBody` 回传到下一轮请求体 assistant message 的 `reasoning_content` 字段。**阻塞路径**：`parseResponse` 一次性提取完整字符串；**流式路径**：`readSse` 累积所有 delta 片段，轮末汇总包携带完整拼接值。无 reasoning_content 的 assistant 消息不注入该字段。

## Out of Scope

- Claude `thinking.budget_tokens`、Gemini `thinkingBudget` 的原生协议适配（经 OpenAI 兼容端点时已由本方案覆盖）。
- effort 取值的厂商级映射校验（如拒绝 DeepSeek 不支持的档位）——透传即合规，映射由厂商侧完成。
- 上层（chat/agent）UI 对思考过程的展示编排——本期仅保证 SPI + 边界透传到位。
- 纯多轮对话（非工具调用）中 reasoning_content 的回传——DeepSeek 官方文档确认纯多轮中会被 API 忽略，故本期仅做工具调用场景回传（R6），纯多轮不回传。

## Open Questions

### OQ1 — 思考参数默认行为（已决策）

**已决策：采纳方案 A**（经复核确认）。

- **(A) 采纳**：不注入任何参数，`supports-thinking` 纯为能力声明，思考开关由厂商默认决定。零行为变更。
- (B) 否决：自动注入"开启思考 + 厂商默认 effort/budget"——隐式注入会掩盖"用户想配但忘了配 params.thinking"的不完整状态，违反 fail-fast（`llm-spi.md` 反模式节）。

决策理由：(1) 零行为变更（R5）；(2) 与 `supportsStreaming` 同语义，符合 ISP；(3) 符合 fail-fast。`application-stable.yml` 的 `qwen3-max` 需显式补 `params.thinking` 才启用思考（见 implement.md Step 11）。

无待决策的开放问题。如实现阶段发现新约束，在此追加。
