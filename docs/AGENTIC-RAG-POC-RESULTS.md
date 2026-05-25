# Agentic RAG Phase 0 — PoC 验证报告

> **日期**: 2026-05-22
> **分支**: agentic-rag-dev
> **Spring AI 版本**: 1.1.6
> **关联设计文档**: docs/AGENTIC-RAG-DESIGN.md (§2.3, §3.5, §4.2, §6.2)

## 总结论

**3 个 PoC 全部 PASS** — 设计文档的 3 个关键假设均得到验证，但有 2 项发现需要对设计文档进行修正。

| PoC | 假设 | 结果 | 发现 |
|-----|------|------|------|
| PoC 1 | FunctionToolCallback.builder() 接受 BiFunction | PASS | call() 会 JSON 序列化返回值 |
| PoC 2 | ChatResponse.metadata().usage() 可用于 token 计数 | PASS* | EmptyUsage 返回 0 非 null |
| PoC 3 | BaseAdvisor.before() 可修改 System Prompt | PASS | — |

*需修正设计文档中的 token 检测策略

---

## PoC 1: FunctionToolCallback.builder() 泛型签名

**测试文件**: `Poc1_FunctionToolCallbackSignatureTest.java`
**结果**: 10/10 PASS

### 验证项

| # | 验证项 | 结果 |
|---|--------|------|
| 1 | builder 接受 BiFunction<String, ToolContext, String> | PASS |
| 2 | ToolContext 可能为 null — 需防御性处理 | PASS |
| 3 | ToolContext 非空时可读取 context map | PASS |
| 4 | 闭包可捕获外部局部变量（workspace） | PASS |
| 5 | 多个 Tool 闭包共享同一 workspace 引用 | PASS |
| 6 | StaticToolCallbackResolver 可包装闭包 callback | PASS |
| 7 | DefaultToolCallingManager 可通过 resolver 解析 | PASS |
| 8 | ToolMetadata.returnDirect 可设置 | PASS |
| 9 | 完整闭包传递路径（搜索→重排→workspace 传递） | PASS |
| 10 | call() 返回值经 JSON 序列化 | PASS |

### 关键发现

**F1: FunctionToolCallback.call() 的输入/输出行为**

- **输入**: `call("\"hello\"")` — JSON 字符串 `"hello"` 被 ObjectMapper 反序列化为 `hello`（去除引号）
- **输出**: 返回值 `"ok"` 经过 `ToolCallResultConverter` JSON 序列化为 `"\"ok\""`（添加引号）
- **影响**: 设计文档中 Tool 返回值不需要手动 JSON 序列化；框架会自动处理

**F2: 签名完全匹配设计文档**

```java
FunctionToolCallback.<String, String>builder(String name, BiFunction<String, ToolContext, String> fn)
```

与设计文档 §3.5 假设完全一致。闭包捕获 workspace 方案可行。

---

## PoC 2: ReAct 循环 usage 元数据可达性

**测试文件**: `Poc2_ReactLoopUsageMetadataTest.java`
**结果**: 10/10 PASS

### 验证项

| # | 验证项 | 结果 |
|---|--------|------|
| 1 | ChatResponse.getMetadata() 非 null | PASS |
| 2 | ChatResponseMetadata 默认包含 EmptyUsage（非 null） | PASS |
| 3 | EmptyUsage token 值为 0（非 null） | PASS |
| 4 | Usage 接口方法可达 | PASS |
| 5 | ChatClientResponse 包装 ChatResponse | PASS |
| 6 | Advisor.after() 可提取 token 计数 | PASS |
| 7 | 字符估算降级方案可行 | PASS |
| 8 | 累积 token 计数器方案可行 | PASS |
| 9 | 外层 Advisor.after() 只在 ReAct 循环结束后调用一次 | PASS |
| 10 | 混合 token 计数策略（精确 + 估算） | PASS |

### 关键发现

**F3: EmptyUsage.getPromptTokens() 返回 0（Integer），不是 null**

设计文档 §6.2 原假设 "检查 `usage.getPromptTokens() != null` 来判断是否有真实 usage" **不正确**。

实际行为：
- `EmptyUsage.getPromptTokens()` → `0`（Integer 包装类型）
- `EmptyUsage.getCompletionTokens()` → `0`
- `EmptyUsage.getTotalTokens()` → `0`

**修正后的检测策略**:
```java
boolean hasRealUsage = usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0;
```

用 `> 0` 而非 `!= null` 来区分 EmptyUsage 和真实 usage。

**F4: Usage 字段名确认**

| 设计文档假设 | 实际 API |
|-------------|---------|
| getPromptTokens() | getPromptTokens() ✓ |
| getGenerationTokens() | **getCompletionTokens()** ✗ |
| getTotalTokens() | getTotalTokens() ✓ |

**F5: 外层 Advisor 无法逐轮获取 usage**

`BaseAdvisor.adviseCall()` 实现：
1. `before(request)` → 修改请求
2. `chain.nextCall(modifiedRequest)` → 包含 ToolCallAdvisor 的整个 ReAct 循环
3. `after(response)` → **只在全部循环完成后调用一次**

结论：usage 只能从最终结果获取，无法逐轮精确计数。

### 最终 token 计数策略

```
策略:
1. 每轮迭代后检查 usage.getPromptTokens() > 0
2. 如果 > 0 → 使用 getTotalTokens() 精确值
3. 如果 = 0 → 使用字符估算 (text.length / 4)
4. 累积到 AgentGuardrails 的 totalCount
5. 上限检查: totalCount > modelContextWindow * 0.8
```

---

## PoC 3: BaseAdvisor.before() System Prompt 注入

**测试文件**: `Poc3_BaseAdvisorSystemPromptTest.java`
**结果**: 10/10 PASS

### 验证项

| # | 验证项 | 结果 |
|---|--------|------|
| 1 | ChatClientRequest 是 Record，提供 mutate() Builder | PASS |
| 2 | 可通过 mutate().prompt() 替换整个 Prompt | PASS |
| 3 | 可通过 mutate().context() 注入键值对 | PASS |
| 4 | 在 Prompt messages 首位插入 SystemMessage | PASS |
| 5 | 替换现有 SystemMessage | PASS |
| 6 | 中间答案注入到 SystemMessage 末尾 | PASS |
| 7 | 可实现 BaseAdvisor 的 before/after 方法 | PASS |
| 8 | before() 返回新 request，不影响原始 request | PASS |
| 9 | Advisor order 排布正确 | PASS |
| 10 | 完整 before() 注入流程（多轮 workspace 更新） | PASS |

### 关键发现

**F6: ChatClientRequest.mutate() 方案完全可行**

```java
// 核心注入模式
ChatClientRequest modified = original.mutate()
    .prompt(new Prompt(modifiedMessages))
    .build();
```

- `ChatClientRequest` 是 Java Record（不可变），但提供 `mutate()` → Builder
- Builder 支持 `prompt(Prompt)` 和 `context(String, Object)`
- 修改后的 request 是新对象，不影响原始 request

**F7: 中间答案注入路径**

```java
// 1. 查找现有 SystemMessage
for (Message msg : request.prompt().getInstructions()) {
    if (msg instanceof SystemMessage sysMsg) {
        // 2. 追加中间答案
        newMessages.add(new SystemMessage(
            sysMsg.getText() + "\n\n## 已收集的信息\n" + contextBuilder
        ));
    } else {
        newMessages.add(msg);
    }
}
// 3. 构建 新 Prompt
return request.mutate().prompt(new Prompt(newMessages)).build();
```

**F8: Advisor Order 排布**

```
before() 执行顺序（按 order 升序）:
  ConversationContextAdvisor(-1) → AgentSystemPromptAdvisor(1) → ToolCallAdvisor(2)
```

AgentSystemPromptAdvisor 的 System Prompt 注入在 ToolCallAdvisor 之前生效。

---

## 对设计文档的影响

### 需修正项

| 位置 | 原内容 | 修正为 |
|------|--------|--------|
| §6.2 护栏 | `usage.getPromptTokens() != null` 检测 | `usage.getPromptTokens() > 0` 检测 |
| §6.2 护栏 | `getGenerationTokens()` | `getCompletionTokens()` |

### 无需修正项

- §3.5 闭包传递方案：签名完全匹配
- §4.3 System Prompt 注入方案：API 行为完全匹配
- §2.3 ReAct 循环 Advisor 排布：order 机制正确

---

## 测试统计

- **总测试数**: 30
- **通过**: 30
- **失败**: 0
- **执行时间**: ~5s

---

## 下一步

1. 修正设计文档 §6.2 中的 token 检测策略和字段名
2. 进入 Phase 1: 实现 IntentClassifier + AgentSystemPromptAdvisor
3. Phase 1 完成后进入 Phase 2: 实现 Tool 闭包 + workspace
