# PRD: 修复 ChatModelAdapter 默认 options 类型缺失 ToolCallingChatOptions

> Task: `06-14-fix-chatmodeladapter-default-options-toolcallingchatoptions`
> Status: planning
> Owner: instant
> Created: 2026-06-14
> Predecessor: `06-14-decouple-spring-ai-chatclient-builder-injection`

---

## 1. 背景

上一个任务把 chat / rag / agent 三层从 Spring AI `ChatClient.Builder` 自动配置中解耦，统一通过 `ChatModelAdapter` 把 `ChatCapable` 适配为 Spring AI 的 `ChatModel`。但启动后实际对话报错：

```
WARN ... Illegal argument: ToolCall Advisor requires ToolCallingChatOptions
to be set in the ChatClientRequest options.
```

报错链路：
```
SimpleModeStrategy.buildAdvisorChain (line 41)
  → if (infra.hasTools()) chain.add(infra.getToolCallAdvisor())  // 无条件挂 ToolCallAdvisor
ChatRequestSpecFactory.createSpec
  → spec.tools((Object) toolCallbacks)                            // 尝试设置工具
ChatClient.builder(new ChatModelAdapter(chatCapable)).build()
  → ChatClient 默认 options 类型不是 ToolCallingChatOptions ❌
ToolCallAdvisor.before
  → request.options() instanceof ToolCallingChatOptions == false → 抛错
```

## 2. 根因

`ChatModelAdapter`（`src/main/java/com/smart/rag/infrastructure/llm/adapter/ChatModelAdapter.java`）实现了 Spring AI 的 `ChatModel` 接口，但**没有 override `getDefaultOptions()`**。

`ChatModel` 接口的默认 `getDefaultOptions()` 返回 `DefaultChatOptions`（或 null）。Spring AI 的 `ChatClient.builder(chatModel).build()` 会拿 `chatModel.getDefaultOptions()` 作为默认 options。

当 `ChatRequestSpecFactory.createSpec` 调用 `spec.tools((Object) toolCallbacks)` 时，Spring AI 期望 options 是 `ToolCallingChatOptions` 实例，把 toolCallbacks 写进去。但我们的默认 options 不是 `ToolCallingChatOptions` → `ToolCallAdvisor.before()` 强校验失败抛错。

**对比原路径**：Spring AI 自带的 `OpenAiChatModel.getDefaultOptions()` 返回 `OpenAiChatOptions`（extends `ToolCallingChatOptions`），所以原自动配置模式下能跑。

## 3. 目标与非目标

### 3.1 目标

1. 让 `ChatModelAdapter` 暴露正确的默认 options 类型（`ToolCallingChatOptions`），使得所有自建 ChatClient 都能挂载 ToolCallAdvisor。
2. 修复后所有现有 chat 路径（SIMPLE / MULTI_TURN / AGENT + 有/无 RAG + 有/无工具）必须正常工作。
3. 在 PRD §6 测试点覆盖回归。

### 3.2 非目标

- **不重构工具暴露策略**。当前 `SimpleModeStrategy` 无条件挂 ToolCallAdvisor 是另一个独立的设计问题（是否应按意图分类），单独任务跟进。
- **不引入具体的 ToolCallingChatOptions 子类**（如 OpenAiChatOptions）。返回通用的 `ToolCallingChatOptions.builder().build()` 足够——LLM 厂商无关，由 `delegate` 的 ChatCapable 处理实际调用。
- **不动 `ChatServiceImpl` / `AgentModeStrategy` / `IntentClassifier` / `RagConfig` 等自建点**。修复在 adapter 层完成，所有自建点自动受益。

---

## 4. 设计方案

### 4.1 修改 `ChatModelAdapter`

新增 `getDefaultOptions()` override：

```java
import org.springframework.ai.chat.model.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

@Override
public ChatOptions getDefaultOptions() {
    return ToolCallingChatOptions.builder().build();
}
```

**为什么返回 `ToolCallingChatOptions` 而非具体子类**：
- `ChatModelAdapter` 是厂商无关的桥接层，不应耦合 OpenAI / Bailian / ZhiPuAI 等具体厂商 options
- `ToolCallingChatOptions.builder().build()` 是 Spring AI 提供的通用实现，可被 `ToolCallAdvisor` 接受
- 实际 LLM 调用由 `delegate.chat(ChatRequest)` 处理，options 中的工具字段仅用于 advisor 链流转

### 4.2 影响面（PRD §3.2 列举的所有自建点）

| 文件 | 行 | 修复后行为 |
|---|---|---|
| `chat/service/impl/ChatServiceImpl.java` | 92, 119 | SIMPLE / MULTI_TURN 模式 + 工具正常 |
| `agent/mode/AgentModeStrategy.java` | 183, 240 | AGENT 模式 + 工具正常 |
| `agent/intent/IntentClassifier.java` | 180 | 意图分类不受影响（不挂 advisor，但默认 options 类型修复后语义统一） |
| `rag/config/RagConfig.java`（经 `RewriteClientResolver`） | 76 | Query rewrite 不挂 advisor，不受影响 |

修复点集中，无需逐个改自建点。

---

## 5. 验证策略

### 5.1 启动验证（P0）

- [ ] 应用正常启动
- [ ] 启动后 `ToolRegistry` 日志显示 `Registered N tool callbacks`（N > 0）

### 5.2 功能验证（P0）

复现原报错的请求参数，预期恢复正常响应：
```json
{
  "model": "deepseek/deepseek-v4-flash",
  "enableThinking": false,
  "message": "你好",
  "ragEnabled": false,
  "mode": "SIMPLE"
}
```

更全面的回归矩阵：
- [ ] `mode=SIMPLE` + `ragEnabled=false` + `message="你好"` → 不再报错
- [ ] `mode=SIMPLE` + `ragEnabled=true` + 知识库相关问题 → RAG + 工具共存正常
- [ ] `mode=MULTI_TURN` + 多轮 → 记忆 + 工具共存正常
- [ ] `mode=AGENT` + 触发 GENERAL_TOOL 意图（如 "123 * 456"） → Agent 走完整工具链
- [ ] `mode=AGENT` + 触发 DIRECT_ANSWER 意图（如 "你好"） → Agent 跳过工具
- [ ] `POST /chat/stream` SSE 流式 + 工具调用 → 流式正常

### 5.3 单测验证（P0）

新增 `ChatModelAdapterTest`：
- [ ] `getDefaultOptions() != null`
- [ ] `getDefaultOptions() instanceof ToolCallingChatOptions`
- [ ] 多次调用返回的实例可以独立使用（builder 模式默认每次新建，不应共享状态）

### 5.4 GitNexus 验证

- [ ] `gitnexus_impact({target: "ChatModelAdapter", direction: "upstream"})` 确认所有调用方（4 个自建点 + `RewriteClientResolver`）从改动中受益，无 break

---

## 6. 测试点（落实为 ChatModelAdapterTest）

| 测试方法 | 断言 |
|---|---|
| `getDefaultOptions_returnsToolCallingChatOptions` | `assertThat(adapter.getDefaultOptions()).isInstanceOf(ToolCallingChatOptions.class)` |
| `getDefaultOptions_notNull` | `assertThat(adapter.getDefaultOptions()).isNotNull()` |
| `getDefaultOptions_freshInstancePerCall` | 两次调用返回的实例允许相等（值对象语义）但内部状态独立 |
| `call_withToolsPropagated_works`（可选 P1） | 通过 `ChatClient.builder(adapter).build()` + `spec.tools(...)` 调用，验证 ToolCallAdvisor 不抛 IllegalArgumentException |

---

## 7. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| `ToolCallingChatOptions.builder().build()` 返回的实例在某些 Spring AI 版本下不完整（缺字段） | 低 | 中 | 实测覆盖 §5.2 全矩阵；如发现，按 Spring AI 文档补字段（如 `toolNames` / `internalToolExecutionEnabled`） |
| 修改影响 `RewriteClientResolver` 路径的 query rewrite（rewriteClient 也基于 `ChatModelAdapter`） | 极低 | 低 | rewrite 不挂 advisor，options 类型变化无副作用；§5.2 验证 |
| 已有 843 个测试中可能有断言 `ChatModelAdapter` 默认 options 的测试 | 低 | 低 | `mvn test` 全跑；如有 break，按测试意图更新 |

---

## 8. 决策记录（2026-06-14）

1. **方案 A 单点修复**（adapter 层 override），不修改所有自建点。集中桥接、单一职责、与 §8.3 `RewriteClientResolver` 抽取的设计哲学一致。
2. **返回 `ToolCallingChatOptions`，不返回具体厂商子类**。保持 `ChatModelAdapter` 厂商无关；`delegate` 负责实际调用。
3. **不重构工具暴露策略**。当前任务只修复"挂 advisor 后报错"，"是否应该挂 advisor"是另一个设计问题（PRD §3.2 非目标）。

---

## 9. 实施步骤（jsonl 雏形）

1. `gitnexus_impact({target: "ChatModelAdapter", direction: "upstream"})` 确认调用方
2. 修改 `ChatModelAdapter.java`：新增 import + override `getDefaultOptions()`
3. 新增 `src/test/java/com/smart/rag/infrastructure/llm/adapter/ChatModelAdapterTest.java`：3-4 个断言点
4. `mvn compile` + `mvn test`
5. 启动应用，按 §5.2 跑回归矩阵（至少跑前 2 项）
6. `gitnexus_detect_changes()` 验证影响面
7. 更新 spec：在 `.trellis/spec/backend/llm-spi.md` 加一节"ChatModelAdapter 必须暴露 ToolCallingChatOptions"
