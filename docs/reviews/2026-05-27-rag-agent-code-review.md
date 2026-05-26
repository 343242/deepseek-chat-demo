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
