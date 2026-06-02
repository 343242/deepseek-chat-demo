# PRD: Agentic RAG Phase 0 — 3 个 PoC 验证

> **Task ID**: 05-22-agentic-rag-phase-0-3-poc
> **Branch**: agentic-rag-dev
> **Priority**: P0 — 阻塞后续所有 Phase
> **关联文档**: docs/AGENTIC-RAG-DESIGN.md

## 背景

Agentic RAG 设计文档（§2.3, §3.5, §4.2, §6.2）中有 3 个关键假设依赖 Spring AI 1.1.6 的具体 API 行为，无法通过阅读源码确认，必须编写最小 PoC 在运行时验证。这 3 个假设直接影响核心实现方案，验证结果将决定后续 Phase 2-6 的具体代码路径。

## 目标

在 `src/test/java` 下编写 3 个独立的 PoC 测试类，验证以下假设：

### PoC 1: `FunctionToolCallback.builder()` 泛型签名

**假设**（设计文档 §3.5）：
```java
FunctionToolCallback.<I, O>builder(String name, BiFunction<I, ToolContext, O> fn)
```
Tool 闭包通过此 builder 创建，接收 `BiFunction<ToolRequest, ToolContext, String>`。

**验证项**：
1. `FunctionToolCallback.builder()` 是否接受 `BiFunction`（含 ToolContext）？
2. 如果只接受 `Function`（不含 ToolContext），闭包如何传递 workspace？
3. Builder 的泛型签名和可用方法有哪些？
4. 创建的 callback 能否被 `StaticToolCallbackResolver` 包装？
5. 能否被 `DefaultToolCallingManager` 正确解析和调用？

**验收标准**：
- 明确 `FunctionToolCallback.builder()` 的真实签名
- 确认 workspace 闭包传递方案可行或需调整
- 产出签名文档记录

### PoC 2: ReAct 循环中 `ChatResponse.metadata().usage()` 可用性

**假设**（设计文档 §6.2 护栏）：
`ToolCallAdvisor` 的 ReAct 循环中，每轮中间 `ChatResponse` 暴露 `usage` 元数据，可用于 token 计数。

**验证项**：
1. ReAct 循环中 `Advisor.after()` 收到的 `ChatResponse` 是否包含 `usage`？
2. `usage` 的类型是什么？有哪些字段（inputTokens, outputTokens, totalTokens）？
3. 如果 `usage` 为 null，有哪些替代方案（如从 `AdvisedResponse` 中获取）？
4. `ToolCallAdvisor` 内部是否提供回调或拦截点来获取每轮 usage？

**验收标准**：
- 明确 token 计数的数据来源
- 确定使用 `usage` 还是字符估算方案
- 如果 usage 不可用，确认估算公式的可行性

### PoC 3: `BaseAdvisor.before()` 修改 System Prompt 可行性

**假设**（设计文档 §4.3）：
`AgentSystemPromptAdvisor` 实现 `BaseAdvisor`，在 `before()` 中动态修改或注入 System Prompt。

**验证项**：
1. `BaseAdvisor.before(AdvisedRequest)` 返回什么类型？能否修改 system message？
2. `AdvisedRequest` 是否可变？如何替换/追加 system prompt？
3. `before()` 中能否从外部状态（如 workspace 引用）读取数据并注入？
4. `before()` 在 ReAct 循环中是否每轮都被调用（还是只调用一次）？
5. Advisor order 机制是否保证 `AgentSystemPromptAdvisor`(order=1) 在 `ToolCallAdvisor`(order=2) 之前执行？

**验收标准**：
- 确认 `before()` 中修改 system prompt 的具体 API 调用方式
- 确认 ReAct 循环中 `before()` 的调用频率
- 确认 advisor order 排布正确

## 实施约束

1. PoC 代码放在 `src/test/java/com/demo/chat/rag/agent/poc/` 包下
2. 使用 Spring Boot Test 注解（`@SpringBootTest` 或 `@WebMvcTest`）确保 Spring AI 真实环境
3. 每个 PoC 类独立运行，不相互依赖
4. 不修改任何现有生产代码
5. 每个 PoC 输出明确的 PASS/FAIL 结论和发现摘要
6. PoC 完成后产出验证报告到 `docs/AGENTIC-RAG-POC-RESULTS.md`

## 产出物

| 产出 | 路径 |
|------|------|
| PoC 1 测试 | `src/test/java/com/demo/chat/rag/agent/poc/Poc1_FunctionToolCallbackSignatureTest.java` |
| PoC 2 测试 | `src/test/java/com/demo/chat/rag/agent/poc/Poc2_ReactLoopUsageMetadataTest.java` |
| PoC 3 测试 | `src/test/java/com/demo/chat/rag/agent/poc/Poc3_BaseAdvisorSystemPromptTest.java` |
| 验证报告 | `docs/AGENTIC-RAG-POC-RESULTS.md` |

## 影响范围

- 仅新增测试文件 + 1 个文档文件
- 不修改任何现有代码
- 不影响 CI 流程
