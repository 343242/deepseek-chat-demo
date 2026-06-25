# AGENT 模式流式支持（复用 Spring AI 流式 tool manager）

## Goal
让 AGENT 对话模式支持流式输出（SSE）：Agent 推理过程中即可流出首字（TTFT），而非等整个 ReAct 循环阻塞完成。当前 `AgentModeStrategy.executeStream` 直接抛 `UNSUPPORTED_OPERATION`。

## Background
- AGENT 阻塞态工具调用已修复（B-i `50b36ce` + 多轮 tool 消息 `9146dd5`），阻塞 ReAct 经 **Spring AI `ChatClient.call()` + `ToolCallAdvisor` + `DefaultToolCallingManager`** 跑通（`AgentModeStrategy:255-269`、`:155-164`）。
- 流式路径缺失的**真实根因**：`ChatModelAdapter.stream()` 把 `capable.chatStream()` 的 `Flux<String>` 映射成纯文本 `ChatResponse`，**丢掉 tool_calls/finishReason/usage**（`ChatModelAdapter:60-66`）；`GenericChatClient.readSse` 只取 `delta.content`，不解析 `delta.tool_calls`/`finish_reason`（`GenericChatClient:150-181`）。导致 Spring AI 的 `ToolCallAdvisor` 在流式下看不到工具调用 → 无法 ReAct。
- **Poc6 实证（真实 Spring AI 1.1.6 jar）**：自定义 `ChatModel.stream()` 轮末发"完整 `toolCalls` + `finishReason=tool_calls`"汇总包 → `ToolCallAdvisor.adviseStream` **自动执行工具 + 再流一轮**（`streamToolFired=true` / `streamCount=2` / `callCount=0` 两轮全流式 / 两轮文本按序流到消费方）。即流式 ReAct **无需自研**，`.call()` 换 `.stream()` 即可。测试：`src/test/java/com/smart/rag/agent/poc/Poc6_SpringAiStreamingToolManagerTest`。
- 意图分类流式 `classifyStream` 已就绪（`fca03ea`）。

## Requirements
1. AGENT 模式流式接口返回 SSE token 流，不再抛 `UNSUPPORTED_OPERATION`。
2. 流式下保留 ReAct 工具循环：**复用 Spring AI 1.1.6 `ToolCallAdvisor.adviseStream`**（Poc6 已证契约），由它执行工具、喂回结果、续流，直到最终答案。
3. **经 `ChatModelAdapter.stream()` 走 Spring AI `ChatClient.stream()`**（与阻塞态同构），不自研 ReAct、不绕开适配器。Agent `executeStream` 镜像阻塞 `execute()` 的 advisor 链，仅 `.call()`→`.stream()`。
4. 与阻塞态对齐：意图分类、工具子集、workspace、护栏、记忆、落库在流式下均保留。
5. 容错：工具失败/超时、模型熔断、loop bounds、token 超限均有明确降级行为，不抛 500。
6. SPI 改型 `Flux<String>→Flux<StreamChunk>`：跨边界传递 tool delta 的载体；Spring AI 边界由 `ChatModelAdapter.stream` 投影成带 tool_calls 的 `Flux<ChatResponse>`；同步迁移 `ProbeHandler`（带首包 Predicate）。

## Constraints
- Spring AI 1.1.6，不升级；`ChatClient.stream()` + `ToolCallAdvisor.adviseStream` 契约固定（Poc6 已验）。
- `ChatCapable.chatStream` SPI 改型影响所有实现与调用方，必须完整迁移（含 `ProbeHandler` 泛型化 + Predicate）。
- 流式 `tool_call` delta 累积（OpenAI 按 index 分片、arguments 流式 JSON）由 `GenericChatClient` 单轮内累积，**轮末发完整 toolCalls 汇总包**（Poc6 验证的防御式，不依赖 Spring AI 的 delta 合并行为）。
- 真模型验证依赖 DeepSeek；本地用 mock + Poc6 契约覆盖。
- 弹性（重试/首包探测/熔断）经 `ResilientChatClient.chatStream`（L1）继承，与 SIMPLE/MULTI_TURN 流式同路径，**不在 Agent 层另挂重试**。
- 阈值 design 阶段定稿：per-tool timeout=30s / Flux 超时=600s / SseEmitter 超时=610s / token 预算=模型窗口 80% / **loop bounds = `AgentGuardrails` 迭代(maxToolIterations=10)+token 双阈值抛异常关连接 + 时间 timeout 兜底**（Spring AI `ToolCallAdvisor` 无迭代上限，已证）。

## Acceptance Criteria
- [ ] AGENT 流式接口返回 SSE 流，不抛 `UNSUPPORTED_OPERATION`。
- [ ] 单轮无工具：流式输出最终答案，**TTFT < 阻塞态端到端 P95 × 0.5**。
- [ ] 多轮工具（真模型 DeepSeek：首轮 tool_call → 执行工具 → 次轮正文）：工具被真实执行，最终答案正确流出（Poc6 已证 Spring AI 契约；P5 真模型验）。
- [ ] `ChatModelAdapter.stream` 回灌单测：`StreamChunk` 序列（text + tool delta 跨片 + finishReason）→ 聚合 `ChatResponse` 含完整 `tool_calls` + `finishReason`（Poc6 契约）。
- [ ] `GenericChatClient` SSE 三态单测：content / tool_calls 按 index 合并 / finish_reason / usage / 末包完整 toolCalls。
- [ ] **loop bounds（硬上界）**：迭代(maxToolIterations=10)/token(80% 窗口) 超阈值 → **抛 `GuardrailHardStopException` 关连接 abort**（流式 OkHttp cancel + 落 partial / 阻塞降级 multiTurn）；不依赖 LLM 遵从。Spring AI 无迭代上限，已证。
- [ ] **token 预算（M1）**：经 guardrails；**流式需补 `TokenCountingChatModel.stream` 累计 usage**（当前是透传桩，否则 token 门槛失效）；超限 → 抛异常关连接（硬 abort，见上）。
- [ ] **模型熔断/降级（R4 用户策略）**：Agent 流式**不走跨模型降级**；L1 重试 + 首包探测 + 熔断继承（同 SIMPLE/MULTI_TURN 流式）。
- [ ] **记忆 save / 落库（M4）**：`onStreamComplete` 经 `createFilteredMemory` + `ChatMemory.save` 落 user + finalAssistant（无 tool_calls）；中间 ReAct 轮不落库（filtered memory 已处理，对齐阻塞态）。
- [ ] **SPI 迁移回归（C2）**：SIMPLE/MULTI_TURN 流式、`IntentClassifier.classifyStream`、阻塞态 `execute`、`ProbeHandler`(embedding/rerank 共用探测) 全部不回归。
- [ ] **客户端断连**：Flux cancel 传播 + OkHttp call cancel（继承 SIMPLE/MULTI_TURN 流式路径）。
- [ ] `detect_changes({scope:"compare", base_ref:"main"})` 范围可控（仅预期文件）。

## Out of Scope
- **strategy 层自研 ReAct**（v3 方案）：降级为 fallback。仅当 P5 真模型验证发现 Spring AI 流式 tool manager 在 DeepSeek 多轮下不增量流（缓冲整轮）且影响 TTFT AC 时才启用（v3 design 保留在 git 历史）。
- **跨模型降级**（R4 显式排除）：Agent 流式只走单模型 + L1 重试 + 超时。
- 意图分类作为 SSE 早期事件推送前端（形态 B）。
- "工具执行进度"事件实时推送前端（协议预留，不实现）。
- SIMPLE/MULTI_TURN 的工具流式增强（只保证不回归）。

## Notes
- v4 基于 Poc6 实证重构：放弃 v3 strategy 层自研 ReAct，复用 Spring AI `ToolCallAdvisor.adviseStream`。v3 的 `reactLoop`/`ToolCallAccumulator`/acc 与订阅 1:1/`consecLoop`/`consecFail`/自建 ToolResponseMessage/depth/retry-on-stream 全部不再需要——由 Spring AI + 已有阻塞 advisor 链接管。
- **范围扩展（v4.1）**：硬上界讨论发现 `AgentGuardrails` STOP 在**阻塞态也是软注入**、且 Spring AI `ToolCallAdvisor` **无迭代上限**（`ToolCallingChatOptions`/`ToolCallAdvisor` 无 maxIterations，javap 已证）→ 本任务顺带把 STOP 升级为"抛异常关连接"硬上界，**阻塞+流式共享**。即任务从"纯加流式"扩到"流式 + 阻塞 guardrail 硬化"（见 design §4.3、implement P4b）。
- 弹性（重试/首包探测/熔断）经 L1 `ResilientChatClient.chatStream` 继承，与 SIMPLE/MULTI_TURN 流式同路径。
- 关联 [[agent-tool-opacity-fix]]（含 Poc6 结论与改写范围）、[[async-executor-threadpool-preference]]、[[rag-dynamic-context-systemmessage-after-history]]。
