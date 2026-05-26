# RAG Agent 包代码审查报告

**日期**: 2026-05-27  
**审查范围**: `src/main/java/com/smart/rag/rag/agent/**`  
**源文件**: 33 个 Java 文件，约 2856 行  
**审查口径**: 结合 Trellis backend spec 的异常处理、日志、质量、目录结构规范进行只读复核  
**最终建议**: REQUEST CHANGES

---

## 审查依据

- `.trellis/spec/backend/error-handling.md`
  - 业务错误消息应友好、中文、面向用户，不暴露堆栈、SQL、内部类名等内部细节。
- `.trellis/spec/backend/logging-guidelines.md`
  - 不记录敏感用户信息；使用参数化日志；异常细节可进内部日志但不能泄漏到用户可见错误。
- `.trellis/spec/backend/quality-guidelines.md`
  - DTO 隔离、统一异常处理、禁止模式、安全检查、SOLID/OCP/DIP。
- `.trellis/spec/backend/directory-structure.md`
  - 业务逻辑下沉到 service/tool，模块边界清晰。
- `.trellis/spec/guides/index.md`
  - 跨层数据流与重复模式需要显式思考。

---

## 总览

| 级别 | 数量 | 结论 |
|------|------|------|
| HIGH | 2 | 必须修复，存在模型上下文信息泄漏与敏感查询日志风险 |
| MEDIUM | 3 | 建议本迭代修复，影响检索正确性、工具调用稳定性和意图识别鲁棒性 |
| LOW | 1 | 可维护性优化 |

本次没有修改代码，也没有运行测试。审查重点是 Agent RAG 的工具调用、检索降级、异常边界、日志内容、callback schema 与测试覆盖。

---

## HIGH

### H1: ToolResult failure 暴露底层异常消息

**文件**:

- `src/main/java/com/smart/rag/rag/agent/tool/VectorSearchTool.java:85`
- `src/main/java/com/smart/rag/rag/agent/tool/Bm25SearchTool.java:101`
- `src/main/java/com/smart/rag/rag/agent/tool/HybridSearchTool.java:61`
- `src/main/java/com/smart/rag/rag/agent/tool/DocDetailTool.java:101`
- `src/main/java/com/smart/rag/rag/agent/tool/RerankTool.java:118`
- 同类模式还出现在 `KnowledgeBaseInfoTool`、`ParentDocLookupTool`、`QueryRewriteTool`、`AgentEventLookupTool`

**问题**:

多个 Tool 在 `catch (Exception e)` 后把 `e.getMessage()` 拼接进 `ToolResult.failure(...)`。`ToolResult` 的注释明确说明该 JSON 是“供 LLM 解析”，因此这些异常消息会进入模型上下文。

**风险**:

底层 DB、SDK、HTTP 客户端异常可能包含 SQL、表名、内部类名、供应商错误、连接信息或部署细节。该行为违反 `error-handling.md` 中“不在错误消息中暴露堆栈、SQL、内部类名”的规则。

**建议修复**:

- Tool 对外只返回稳定、中文、面向策略的错误文案。
- 内部异常细节只写日志，且日志中避免敏感内容。
- 可引入统一封装，例如 `ToolFailureFactory` 或 `ToolErrorMessageSanitizer`。

示例方向:

```java
log.error("Vector search error", e);
return ToolResult.failure(
    "vectorSearch",
    "向量检索服务暂时不可用，请改用已有上下文或稍后重试。",
    "INTERNAL_ERROR",
    duration
).toJson();
```

---

### H2: 日志记录完整用户查询或改写查询

**文件**:

- `src/main/java/com/smart/rag/chat/service/ChatAdvisorChainFactory.java:209`
- `src/main/java/com/smart/rag/rag/agent/tool/QueryRewriteTool.java:89`
- `src/main/java/com/smart/rag/rag/agent/service/HybridSearchService.java:90`
- `src/main/java/com/smart/rag/rag/agent/tool/AgentEventLookupTool.java:53`

**问题**:

日志中记录了完整用户查询、归一化查询或改写后的查询。

**风险**:

用户问题可能包含姓名、手机号、账号、内部业务内容、知识库私密片段等敏感信息。`logging-guidelines.md` 明确要求“不记录敏感用户信息”。

**建议修复**:

- 日志改为记录 `queryLength`、hash、intent、结果数量、耗时等。
- 如确需调试文本，应在 dev profile 下输出脱敏后的前 N 个字符。
- `INFO` 日志不要包含原始 query。

示例方向:

```java
log.info("Agent intent classified: intent={}, confidence={}, queryLength={}",
    intentResult.intent(), intentResult.confidence(), request.message().length());
```

---

## MEDIUM

### M1: HybridSearchService 两路检索均失败时仍按成功空结果返回

**文件**: `src/main/java/com/smart/rag/rag/agent/service/HybridSearchService.java:76`

**问题**:

`vectorFuture.join()` 和 `bm25Future.join()` 各自失败时都会降级为空列表。如果两个分支都失败，`rrfFusion` 返回空列表，上层 `HybridSearchTool` 仍会生成“检索到 0 个相关文档片段”的成功结果。

**风险**:

模型会误判为知识库没有相关内容，而不是检索系统不可用，导致最终回答错误或漏答。

**建议修复**:

- 单分支失败可以降级，但应记录 partial degradation。
- 两个分支均失败时返回明确失败信号，或抛出内部异常给 Tool 统一转换为安全 failure。
- 增加单测覆盖“两路失败不返回 success 空结果”。

---

### M2: Tool callback schema 与工具真实输入语义不一致

**文件**:

- `src/main/java/com/smart/rag/rag/agent/tool/callback/AgentToolCallbackFactory.java:168`
- `src/main/java/com/smart/rag/rag/agent/tool/ParentDocLookupTool.java:45`
- `src/main/java/com/smart/rag/rag/agent/tool/DocDetailTool.java:43`
- `src/main/java/com/smart/rag/rag/agent/tool/AgentEventLookupTool.java:40`

**问题**:

`parentDocLookup` 描述为“无需输入参数”，但 callback 使用 `FunctionToolCallback.<String, String>` 和 `inputType(String.class)`；lambda 丢弃 request。`docDetail`、`agentEventLookup` 代码签名支持多参数，但 callback 只暴露单字符串，导致 `queryText` 或 `sessionId` 永远无法由模型传入。

**风险**:

工具 schema 与实际语义不一致会降低模型工具选择质量，增加调用失败率，也会让后续维护者误判 Tool 的真实契约。

**建议修复**:

- 无参工具使用明确的空输入 record。
- 多参数工具使用 request record，例如：

```java
public record DocDetailRequest(String docIds, String queryText) {}
public record AgentEventLookupRequest(String queryText, String sessionId) {}
```

---

### M3: IntentClassifier 用正则解析 LLM JSON

**文件**: `src/main/java/com/smart/rag/rag/agent/intent/IntentClassifier.java:91`

**问题**:

注释声称“Structured Output 映射到 IntentResult”，但实现是用正则提取 `intent` 和 `confidence` 字段。

**风险**:

字段大小写、转义、换行、code fence、字段顺序变化、confidence 带空格或 JSON 嵌套都可能解析失败。当前 `AgentIntent.valueOf(intentStr)` 也没有做大小写归一化。

**建议修复**:

- 使用 Jackson 解析为 DTO/record，解析失败时降级到 `SAFE_FALLBACK`。
- 或真正接入 Spring AI structured output。
- 增加 fenced JSON、非法 JSON、未知 intent、confidence 非数字的回归测试。

---

## LOW

### L1: 事件 payload 以字符串约定维护，扩展风险较高

**文件**:

- `src/main/java/com/smart/rag/rag/agent/event/AgentSessionEvent.java:44`
- `src/main/java/com/smart/rag/rag/agent/event/AgentEventStore.java:175`

**问题**:

`AgentSessionEvent.data` 是字符串，`formatEvent` 按字符串拼接恢复快照。事件 payload 缺少结构化契约。

**风险**:

随着事件类型增加，恢复快照、搜索、agentEventLookup 的格式容易漂移。

**建议修复**:

后续继续扩展事件体系时，定义事件 payload record 并集中序列化，避免各调用点自行拼 JSON 或拼文本。

---

## 测试缺口

当前 `src/test/java/com/smart/rag/rag/agent` 下主要是 `poc/` 测试。正式测试中仅看到 `HybridDocumentRetrieverTest` 间接覆盖 `HybridSearchService`。建议补充：

1. Tool 异常时不把内部异常消息写入 `ToolResult.errorMessage`。
2. `HybridSearchService` 两路检索都失败时不会返回 success 空结果。
3. `AgentToolCallbackFactory` 暴露的 input schema 与工具真实输入 DTO 一致。
4. `IntentClassifier` 对 fenced JSON、大小写、非法 JSON 的降级行为。
5. 查询日志不包含原始用户 query，仅包含 hash/长度/耗时/结果数。

---

## 修复优先级建议

**P0**:

1. 统一清洗 Tool failure 文案，禁止 `e.getMessage()` 进入 ToolResult。
2. 移除 INFO/DEBUG 中的完整用户查询文本。

**P1**:

1. 修正 hybrid 两路失败的成功空结果问题。
2. 修正 Tool callback schema，使用 request record。
3. 用 Jackson 或 structured output 替换 IntentClassifier 正则解析。

**P2**:

1. 为 Agent event payload 建立结构化契约。
2. 将上述测试缺口纳入正式单元测试。

---

## 复核状态

- 工作树审查前为干净状态。
- 本报告为只读审查结果沉淀，未修改生产代码。
- 未运行测试；本报告不声明行为已修复。

---

## 六维专项审查补充

**审查时间**: 2026-05-27  
**审查范围**: `src/main/java/com/smart/rag/rag/agent/**`  
**审查维度**: 资源关闭 / 边界条件 / 并发安全 / 性能陷阱 / 异常处理 / 内存泄漏  
**审查方式**: 只读源码审查，未修改生产代码，未运行测试  
**专项结论**: REQUEST CHANGES

### 专项总览

| 级别 | 数量 | 主要风险 |
|------|------|----------|
| HIGH | 2 | 异步检索线程池/超时风险，token 护栏未实际接入 |
| MEDIUM | 4 | Workspace 并发可变状态、事件表增长、异常消息泄漏、结果/prompt 膨胀 |
| LOW | 1 | Rerank 资源关闭路径存在但生命周期偏重 |

### HIGH

#### H3: HybridSearchService 使用公共 ForkJoinPool 执行阻塞检索且无超时

**文件**: `src/main/java/com/smart/rag/rag/agent/service/HybridSearchService.java:71`

**维度**: 并发安全 / 性能陷阱

**问题**:

`hybridSearch()` 使用 `CompletableFuture.supplyAsync()` 同时执行向量检索和 BM25 检索，但未传入专用 `Executor`，默认使用 JVM 公共 `ForkJoinPool`。两路任务都是阻塞型外部/DB 调用，并且没有 timeout、cancel 或隔离队列。

同时，代码先 `vectorFuture.join()`，再 `bm25Future.join()`。如果 vector 分支长时间卡住，即使 BM25 已完成，当前请求仍会阻塞。

**风险**:

- 高并发下阻塞任务占满 common pool，影响进程内其他异步任务。
- 单个检索分支卡住会拖住整个 agent 请求。
- 无超时会让 Servlet 请求线程持续等待，放大下游 DB/向量服务故障。

**建议修复**:

- 注入 RAG 检索专用 `Executor`，配置线程数、队列上限和拒绝策略。
- 对每个 future 使用 `orTimeout` / `completeOnTimeout`。
- 任一分支超时或失败时取消对应 future，并在结果 metadata 中标记降级状态。

#### H4: TokenCountingChatModel 未接入实际模型调用，token 护栏大概率失效

**文件**:

- `src/main/java/com/smart/rag/chat/service/ChatAdvisorChainFactory.java:285`
- `src/main/java/com/smart/rag/rag/agent/guardrail/AgentGuardrails.java:69`
- `src/main/java/com/smart/rag/rag/agent/guardrail/TokenCountingChatModel.java:50`

**维度**: 边界条件 / 内存泄漏防线

**问题**:

`createGuardrails()` 创建了 `TokenCountingChatModel`，但该 wrapper 没有被接入实际 `ChatClient` / `ToolCallAdvisor` 模型调用路径。`AgentGuardrails.check()` 读取 `tokenCountingModel.getTotalTokens()`，但如果 wrapper 的 `call()` 未被实际调用，累计 token 将一直为 0。

**风险**:

- `TOKEN_LIMIT` 护栏不会触发。
- 长 ReAct 循环只能依赖 `maxToolIterations` 兜底。
- system prompt、workspace 文档和中间答案持续增长时，缺少上下文窗口级别的真实保护。

**建议修复**:

- 将 `TokenCountingChatModel` 真正注入 agent 模型调用链。
- 或改为从每轮 `ChatResponse` metadata / advisor context 读取 usage 并累加。
- 增加回归测试：模拟多轮调用后 token 超限必须触发 STOP。

### MEDIUM

#### M4: ToolWorkspace 是请求内共享可变状态，缺少并发契约

**文件**:

- `src/main/java/com/smart/rag/rag/agent/workspace/ToolWorkspace.java:30`
- `src/main/java/com/smart/rag/rag/agent/tool/callback/AgentToolCallbackFactory.java:128`

**维度**: 并发安全

**问题**:

`ToolWorkspace` 使用多个 `ArrayList` / `HashSet` 保存请求状态，所有 agent tool callback 闭包捕获同一个 workspace 实例。当前类没有同步、锁、线程安全集合或不可变状态合并机制。

如果 Spring AI 当前或未来配置允许一次模型响应触发多个 tool call 并行执行，workspace mutation 会产生竞态。

**风险**:

- `seenDocIds` 去重失效，重复文档进入上下文。
- `retrievedDocs`、`rewrittenQueries`、`intermediateAnswers` 出现 lost update。
- 读写交错时可能出现 `ConcurrentModificationException` 或导出状态不一致。

**建议修复**:

- 明确工具调用串行契约，并在注释/测试中固化。
- 如果无法保证串行，给 workspace mutation 加锁，或改成每个 tool 返回 delta，再由单线程合并。

#### M5: Agent 事件表和恢复快照缺少实际容量/TTL 控制

**文件**:

- `src/main/java/com/smart/rag/rag/agent/event/AgentEventStore.java:113`
- `src/main/java/com/smart/rag/rag/agent/event/AgentEventMapper.java:23`
- `src/main/resources/db/migration/V15__agent_session_event.sql:5`

**维度**: 内存泄漏 / 性能陷阱

**问题**:

`buildResumeSnapshot()` 一次性加载某会话全部事件；SQL 无 `LIMIT`、无时间窗。迁移只创建 `agent_session_event` 表和索引，没有落地注释中提到的 14 天 TTL 清理任务。

**风险**:

- 长会话恢复时一次性加载大量事件，占用内存并拖慢响应。
- 表长期增长，影响搜索、恢复快照和索引维护成本。
- `maxBytes` 预算只在 Java 拼接阶段生效，不能限制 DB 返回规模。

**建议修复**:

- 在 SQL 层增加时间窗和条数上限。
- 增加 Flyway 清理策略或应用启动定期清理任务。
- 恢复快照优先取 Critical/High 事件时也应在查询层分批或限量。

#### M6: Tool failure 直接回显底层异常消息

**文件**:

- `src/main/java/com/smart/rag/rag/agent/tool/VectorSearchTool.java:88`
- `src/main/java/com/smart/rag/rag/agent/tool/Bm25SearchTool.java:104`
- `src/main/java/com/smart/rag/rag/agent/tool/DocDetailTool.java:104`
- `src/main/java/com/smart/rag/rag/agent/tool/KnowledgeBaseInfoTool.java:58`

**维度**: 异常处理

**问题**:

多个工具将 `e.getMessage()` 拼进 `ToolResult.failure()`。该 JSON 会返回给 LLM 阅读，属于用户/模型可见错误面。

**风险**:

底层异常可能包含 SQL、表名、连接信息、供应商错误、内部类名或其他部署细节。该问题也与本报告 H1 属同类风险。

**建议修复**:

- 对外返回固定中文错误文案和换策略建议。
- 内部日志保留异常堆栈，但避免敏感信息。
- 建议抽取统一工具错误文案工厂，避免每个 Tool 自行拼接。

#### M7: Workspace 和 system prompt 缺少容量上限，长轮次会膨胀

**文件**:

- `src/main/java/com/smart/rag/rag/agent/workspace/ToolWorkspace.java:90`
- `src/main/java/com/smart/rag/rag/agent/tool/HybridSearchTool.java:55`
- `src/main/java/com/smart/rag/rag/agent/advisor/AgentSystemPromptAdvisor.java:69`

**维度**: 边界条件 / 内存泄漏

**问题**:

`addRetrievedDocs()` 直接追加文档，不去重、不限量。多轮检索、改写、rerank、parent lookup 可能让 workspace 文档不断增长。`AgentSystemPromptAdvisor.before()` 每轮从 workspace 读取中间答案并拼入 system prompt，缺少 token/字符预算裁剪。

**风险**:

- 请求内内存和 JSON 输出持续增长。
- prompt 变长导致模型费用和延迟上升。
- token 护栏未实际接入时，超上下文风险进一步放大。

**建议修复**:

- workspace 层统一设置最大文档数、最大内容字符数、最大中间答案数。
- 检索结果默认走去重追加。
- prompt 注入层按 token/字符预算裁剪低优先级内容。

### LOW

#### L2: RerankTool 每次调用创建并关闭 reranker，资源关闭正确但生命周期偏重

**文件**: `src/main/java/com/smart/rag/rag/agent/tool/RerankTool.java:76`

**维度**: 资源关闭 / 性能陷阱

**问题**:

`RerankTool` 每次执行都会创建 `BailianRerankPostProcessor`，并在 `finally` 中调用 `shutdown()`。资源关闭路径存在，但如果底层包含 HTTP client / 线程池，频繁创建和销毁会带来额外开销。

**风险**:

- 高并发 rerank 时对象和连接生命周期抖动。
- 如果 `shutdown()` 非幂等或内部关闭异常处理不充分，可能影响原始 rerank 异常定位。

**建议修复**:

- 如果 `BailianRerankPostProcessor` 线程安全，改为 Spring 单例 bean 并随 bean 生命周期关闭。
- 如果不线程安全，保留当前模式，但明确 `shutdown()` 幂等性并补测试。

### 六维归纳

| 维度 | 当前结论 |
|------|----------|
| 资源关闭 | `RerankTool` 有 `finally shutdown()`，但每次创建/销毁偏重 |
| 边界条件 | token 护栏未实际接入；workspace、prompt、事件恢复缺少上限 |
| 并发安全 | `HybridSearchService` 使用 common pool；`ToolWorkspace` 共享可变状态无并发契约 |
| 性能陷阱 | 阻塞检索跑公共线程池；事件全量加载；reranker 反复创建 |
| 异常处理 | 多个 Tool failure 对 LLM 暴露底层异常消息 |
| 内存泄漏 | `agent_session_event` 无实际 TTL；workspace/prompt 随轮次增长 |

### 专项修复优先级

**P0**:

1. 为 `HybridSearchService` 配置专用 executor + 超时 + 降级标记。
2. 修正 token 统计接入路径，让 `TOKEN_LIMIT` 护栏真实生效。

**P1**:

1. 为 `ToolWorkspace` 明确串行契约或加并发保护。
2. 给 `agent_session_event` 增加清理机制和恢复查询上限。
3. 统一 Tool failure 安全文案，禁止 `e.getMessage()` 进入 ToolResult。
4. 为 workspace/prompt 增加容量预算。

**P2**:

1. 评估 reranker 生命周期，决定单例复用或保留短生命周期并补充关闭测试。
