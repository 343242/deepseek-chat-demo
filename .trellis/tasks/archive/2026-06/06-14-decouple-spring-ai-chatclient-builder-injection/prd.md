# PRD: 解耦 Spring AI `ChatClient.Builder` 注入依赖

> Task: `06-14-decouple-spring-ai-chatclient-builder-injection`
> Status: planning
> Owner: instant
> Created: 2026-06-14

---

## 1. 背景

新基础设施层 `com.smart.rag.infrastructure.llm` 已经把所有 LLM 客户端封装为 `CapabilityClient` / `ChatCapable`，并暴露 `LlmClientRegistry` 作为唯一入口。`ChatModelAdapter` 把任意 `ChatCapable` 适配为 Spring AI 的 `ChatModel`。

`LlmAutoConfiguration` 注册了 `@Primary EmbeddingModel` bean（供 PgVectorStore 等使用），但**没有注册任何 `ChatModel` bean**——这是有意的：基础设施层希望"调用方按需把候选 ChatCapable 适配成 ChatModel"，而不是把"默认 ChatModel"硬塞给容器。

但仍有 3 个类直接通过构造函数注入 Spring AI 自动配置的 `ChatClient.Builder`，导致：

- Spring 容器初始化时必须存在 `ChatModel` bean（Spring AI 的 `ChatClientAutoConfiguration` 硬依赖）；
- 由于基础设施层不提供 `ChatModel` bean，**整个 `chatController` 链路启动失败**：
  `ChatController → ChatServiceImpl → ModeRouter → AgentModeStrategy → AdvisorInfrastructure → RagAdvisorFactory → ChatClient.Builder ❌`

报错样例：
```
No qualifying bean of type 'org.springframework.ai.chat.model.ChatModel' available
```

---

## 2. 目标与非目标

### 2.1 目标

1. **消除所有"通过构造函数注入 `ChatClient.Builder` 依赖 Spring AI 自动配置"的位置**，让 chat / rag / agent 三层不依赖 `ChatClientAutoConfiguration`。
2. **统一从 `LlmClientRegistry` 取候选 `ChatCapable`**，按需用 `ChatModelAdapter` 适配后 `ChatClient.builder(...).build()`。
3. **修复启动错误**：删除对 Spring AI `ChatModel` / `ChatClient.Builder` bean 的隐式依赖。
4. **保持现有行为不变**：失败转移、Advisor 链、Tool 调用、Token 计数、查询改写、Rerank 等全部保持原语义。

### 2.2 非目标

- **不解耦 Spring AI 框架扩展点**。下列接口是 Spring AI 框架的扩展面，必须保留 Spring AI 类型：
  - `@Tool` 注解（chat/tool/*、agent/tool/*）
  - `BaseAdvisor` 实现（`AgentSystemPromptAdvisor`）
  - `ChatModel` 实现（`TokenCountingChatModel`、`AgentModeStrategy$NoOpChatModel`）
  - `RetrievalAugmentationAdvisor` / `DocumentPostProcessor` / `QueryTransformer`（RAG 检索后处理链）
  - `ChatClient.ChatClientRequestSpec` / `Advisor` API（Advisor 调用链构造）
- **不重构 `ChatClient.builder(new ChatModelAdapter(...))` 自建模式**。这是已经验证过的桥接模式，4 处自建点保持现状。
- **不删除 `LlmAutoConfiguration.primaryEmbeddingModel`**。PgVectorStore 强依赖 Spring AI 的 `EmbeddingModel` bean。
- **不引入新的 `@Primary ChatModel` bean**（即上次诊断中的"方案 A"）。本任务采用方案 B：彻底切断对自动配置的依赖。

---

## 3. 当前耦合面盘点

### 3.1 ❌ 强依赖 `ChatClient.Builder` 自动配置的注入点（本次要改）

| # | 文件 | 行 | 注入位置 | 用途 |
|---|---|---|---|---|
| 1 | `rag/config/RagAdvisorFactory.java` | 42, 58 | 字段 + 构造参数 | **死字段**：赋值后从未被引用。`create()` / `createIsolatedRetriever()` / `getPostProcessors()` 均不读它。`QueryTransformer` 是通过构造参数注入的预构建 bean（来自 `RagConfig.rewriteQueryTransformer`），不再需要 builder。**处理方式：直接删字段 + 删构造参数** |
| 2 | `rag/config/RagConfig.java` | 34, 50, 59-60 | 构造参数 + `resolveRewriteBuilder` 默认分支 | 作为 `resolveRewriteBuilder` 的 `defaultBuilder` 形参，用于"未指定 rewrite 候选 ID"和"指定但 registry 找不到"两个分支的回退 |
| 3 | `agent/tool/QueryRewriteTool.java` | 40 | 构造参数 | 在 `@Tool` 方法里调 LLM 改写查询 |

### 3.1.1 ⚠️ 已知存在的其他 `ChatClient.Builder` 注入点（本期不纳入）

| 文件 | 行 | 模块 | 是否启用 | 不纳入理由 |
|---|---|---|---|---|
| `evaluation/runner/EvaluationRunner.java` | 56, 73 | evaluation | `@Profile("evaluation")` + `app.evaluation.enabled=true` 默认不激活 | 评测子系统独立，启用时再单独建任务 |
| `evaluation/dataset/DatasetGenerator.java` | 41, 48 | evaluation | 同上 | 同上 |
| `evaluation/config/EvaluationConfig.java` | 64 | evaluation | 同上 | **自包含**（`new ZhiPuAiChatModel(api, options)` 直接 new，不依赖自动配置 bean）；启用时也不会触发本次同款启动错误 |

> 启用 evaluation profile 时，`EvaluationRunner` / `DatasetGenerator` 仍会因注入 `ChatClient.Builder` 触发"无 ChatModel bean"问题，需在评测模块独立任务中处理。

### 3.2 ✅ 已经自建 ChatClient（无需改动，仅作参考）

| 文件 | 行 | 模式 |
|---|---|---|
| `chat/service/impl/ChatServiceImpl.java` | 92, 119 | `ChatClient.builder(new ChatModelAdapter(chatCapable)).build()` |
| `agent/mode/AgentModeStrategy.java` | 183, 240 | 同上 |
| `agent/intent/IntentClassifier.java` | 180 | 同上 |
| `rag/config/RagConfig.java` | 76 | `ChatClient.builder(new ChatModelAdapter(chatCapable)).build()`（在 `resolveRewriteBuilder` 里） |

> 注：`rag/config/RagConfig.java` 已经在 76 行展示了"按候选自建"的正确模式，只是构造参数仍然吃自动配置的 `defaultBuilder` 作为 fallback。这次任务把 fallback 也改成从 registry 取默认 chat 候选自建。

### 3.3 🚫 Spring AI 框架扩展点（保留，不改）

| 文件 | Spring AI 角色 |
|---|---|
| `agent/advisor/AgentSystemPromptAdvisor.java` | `implements BaseAdvisor` |
| `agent/guardrail/TokenCountingChatModel.java` | `implements ChatModel`（供 `TokenCountingAdvisor` 使用） |
| `agent/mode/AgentModeStrategy.java` (NoOpChatModel 内嵌类) | `implements ChatModel`（占位） |
| `chat/tool/*` (CalculatorTools / DateTimeTools / CodeExecutionTool) | `@Tool` 注解 |
| `agent/tool/*` (Bm25 / Vector / Hybrid / ParentDoc / Rerank) | `@Tool` 注解 |
| `agent/tool/callback/AgentToolCallbackFactory.java` | Spring AI ToolCallback 工厂 |
| `chat/service/ChatAdvisorChainFactory.java` | 处理 `Advisor` 链 |
| `chat/service/ChatRequestSpecFactory.java` | 构造 `ChatClient.ChatClientRequestSpec` |
| `rag/retrieval/RerankDocumentPostProcessor.java` | `implements DocumentPostProcessor` |
| `rag/retrieval/MmrDocumentPostProcessor.java` | `implements DocumentPostProcessor` |
| `rag/chunk/ParentDocumentPostProcessor.java` | `implements DocumentPostProcessor` |
| `rag/chunk/*ChunkStrategy.java` | 使用 Spring AI `Document` / `TokenTextSplitter` |
| `evaluation/config/EvaluationConfig.java` | 评测模块（独立子系统，本次不动） |

---

## 4. 设计方案

### 4.1 核心思路

**所有"需要一个 ChatClient"的地方统一改为"从 `LlmClientRegistry` 取候选 `ChatCapable` → `ChatModelAdapter` 适配 → `ChatClient.builder(...).build()`"。**

删除构造函数对 `ChatClient.Builder` 的形参注入，断开 Spring AI `ChatClientAutoConfiguration` 的依赖。

### 4.2 候选解析策略

| 场景 | 候选选择 | 备注 |
|---|---|---|
| RAG query rewrite (`RagConfig.rewriteQueryTransformer`) | `RewriteClientResolver.resolveDefault()` 或 `resolve(properties.queryRewriteModel())` | 候选 ID 为空时走 `resolveDefault()`；非空时走 `resolve(id)`，无效 ID 抛 `RemoteException`（fail-fast，不回退） |
| Query rewrite tool (`QueryRewriteTool`) | `RewriteClientResolver.resolveDefault()` | 构造期一次解析，缓存到 `chatClient` 字段 |

> **`RagAdvisorFactory` 不参与候选解析**：经 BUG-1 核实，其 `chatClientBuilder` 字段是死字段，本期仅做删除（见 §4.3 修改 1），不消费 resolver。
>
> **关键决定**：fallback 不再走 Spring AI 自动配置的 `ChatClient.Builder`，统一从 registry 取默认 chat 候选。如果 registry 为空（无 chat 候选），属于配置错误，应在启动时 fail-fast，而不是依赖自动配置的空 builder 静默通过。

### 4.3 类修改清单（精确到 file:line）

#### 修改 1：`rag/config/RagAdvisorFactory.java`（死字段清理）

**当前（line 42, 58）**：
```java
private final ChatClient.Builder chatClientBuilder;
// ...
public RagAdvisorFactory(ChatClient.Builder chatClientBuilder, ...) {
    this.chatClientBuilder = chatClientBuilder;
    // ...
}
```

**目标**：直接删除字段和构造参数。无需新增 `resolveRewriteBuilder()` 方法（该字段是死字段，从未被任何方法引用——见 §3.1 表格 1 的说明）。

```java
// 字段 chatClientBuilder 删除
// 构造参数 chatClientBuilder 删除
// 构造体中的 this.chatClientBuilder = chatClientBuilder; 删除
```

`RagAdvisorFactory` 的 `create()` 方法使用的 `QueryTransformer rewriteQueryTransformer` 来自另一个构造参数（由 `RagConfig.rewriteQueryTransformer` bean 注入），不依赖 builder。修改 1 仅做删除，不引入新行为。

#### 修改 2：`rag/config/RagConfig.java`（resolveRewriteBuilder 三分支决策）

**当前 `resolveRewriteBuilder(defaultBuilder, properties, llmRegistry)` 三分支**（line 59-80）：

| 分支 | 触发条件 | 当前行为 | 死代码？ |
|---|---|---|---|
| A | `rewriteCandidateId` 为空 | `return defaultBuilder` | 否 |
| B | `rewriteCandidateId` 非空但 registry 找不到 | `if (chatCapable == null) return defaultBuilder` | **是**：`llmRegistry.get(id, Class)` 找不到时抛 `RemoteException`，永远不返回 null |
| C | `rewriteCandidateId` 非空且命中 | `ChatClient.builder(new ChatModelAdapter(chatCapable)).build().mutate()` | 否 |

**改造决策**：
- 删除 `defaultBuilder` 形参（即构造参数 `ChatClient.Builder chatClientBuilder`）
- 分支 A 改为：通过 `RewriteClientResolver.resolveDefault()` 拿到基于默认 chat 候选的 `ChatClient`，返回 `.mutate()`（保持返回类型为 Builder）
- **分支 B 重新决策**：原代码 silent fallback 到 defaultBuilder 是死分支。改造后 `llmRegistry.get(rewriteCandidateId, ChatCapable.class)` 抛 `RemoteException`，需要显式决策：
  - **方案 B1（推荐，fail-fast）**：让 `RemoteException` 向上抛出，由 Spring 包装为 `BeanCreationException`，错误信息明确告知"配置的 rewrite 候选 ID 无效"
  - **方案 B2（保持 silent fallback）**：try-catch RemoteException，记 WARN 后回退到 `resolveDefault()`
  - **本期采用 B1**：避免静默 fallback 掩盖配置错误，与 §8.2 fail-fast 决策一致
- 分支 C 保持不变

**目标签名**：
```java
private ChatClient.Builder resolveRewriteBuilder(
        RagRetrievalProperties properties,
        RewriteClientResolver resolver) {

    String rewriteCandidateId = properties.queryRewriteModel();
    if (rewriteCandidateId == null || rewriteCandidateId.isBlank()) {
        log.info("Query rewrite using default chat candidate");
        return resolver.resolveDefault().mutate();
    }
    // 命中则用指定候选；未命中则 RemoteException 向上抛（fail-fast）
    return resolver.resolve(rewriteCandidateId).mutate();
}
```

**`RewriteClientResolver` 注入方式**：`RagConfig` 是 `@Configuration` 类，`rewriteQueryTransformer` 是 `@Bean` 方法。`RewriteClientResolver` 作为 `@Bean` 方法的参数注入（Spring 自动从容器解析）：
```java
@Bean
public RewriteQueryTransformer rewriteQueryTransformer(
        RagRetrievalProperties properties,
        LlmClientRegistry llmRegistry,
        RewriteClientResolver resolver) {  // 新增参数
    // ...
    ChatClient.Builder builder = resolveRewriteBuilder(properties, resolver);
    // ...
}
```
原 `chatClientBuilder` 参数删除，新增 `resolver` 参数。`RagConfig` 类本身不需要新增构造函数或字段，注入发生在 `@Bean` 方法签名层。

#### 修改 3：`agent/tool/QueryRewriteTool.java`

**当前（line 37, 40）**：
```java
private final ChatClient chatClient;
public QueryRewriteTool(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
    this.chatClient = chatClientBuilder.build();
    // ...
}
```

**目标**：
```java
private final ChatClient chatClient;
public QueryRewriteTool(RewriteClientResolver resolver, ObjectMapper objectMapper) {
    this.chatClient = resolver.resolveDefault();
    // ...
}
```

#### 修改 4：`evaluation/config/EvaluationConfig.java` — **本期不动**

**核实结论**：该文件 line 64 是 `ZhiPuAiChatModel chatModel = new ZhiPuAiChatModel(api, options);` + `return ChatClient.builder(chatModel).build();`，**完全自包含**，不依赖任何 Spring AI 自动配置的 `ChatModel` bean。即便启用 evaluation profile，此文件也不会触发本次同款启动错误。

`evaluation/runner/EvaluationRunner.java` 和 `evaluation/dataset/DatasetGenerator.java` 才是真耦合点，但本期不纳入（见 §3.1.1）。

---

### 4.4 不修改的"看起来像耦合"的地方

| 位置 | 为什么不改 |
|---|---|
| `chat/service/impl/ChatServiceImpl.java:92, 119` | 已经是 `ChatClient.builder(new ChatModelAdapter(...))` 自建模式，不依赖自动配置 |
| `chat/service/ChatRequestSpecFactory.java` `createSpec(ChatClient, ...)` 形参 | `ChatClient` 由上游 `ChatServiceImpl` 自建后传入，是值传递，不是 bean 注入 |
| `chat/service/StrategyExecutionContext.java` `ChatClient chatClient` 字段 | 同上，值对象字段 |
| `chat/service/ChatAdvisorChainFactory.java` | 处理 `Advisor` 链，是 Spring AI 框架扩展面的合法使用 |

---

## 5. 验证策略

### 5.1 启动验证（P0）

- [ ] 应用能正常启动，`chatController` bean 创建成功
- [ ] 启动日志中**不再出现** `No qualifying bean of type 'ChatModel'`
- [ ] Spring AI `ChatClientAutoConfiguration` 退化为不创建 `ChatClient.Builder` bean（因无 `ChatModel` bean 触发）

### 5.2 功能验证（P0）

- [ ] `POST /chat` 在 `mode=SIMPLE` + `ragEnabled=false` 下正常返回
- [ ] `POST /chat` 在 `mode=SIMPLE` + `ragEnabled=true` 下触发 RAG query rewrite（验证 `RagAdvisorFactory` / `RagConfig` 改造正确）
- [ ] `POST /chat` 在 `mode=AGENT` 下 Tool 调用正常，特别是 `QueryRewriteTool` 能正常改写
- [ ] `POST /chat/stream` SSE 流式正常
- [ ] 多模型 fallback：主候选失败时切到次候选

### 5.3 单测验证（P1）

- [ ] `QueryRewriteTool` 构造测试：mock `RewriteClientResolver.resolveDefault()` 返回非空 `ChatClient`
- [ ] `RewriteClientResolver` 测试：当 `LlmClientRegistry.getDefault(CHAT)` 抛 `RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR)`（无 chat 候选）时，包装为 `ModelNotFoundException` 抛出
- [ ] `RewriteClientResolver.resolve(candidateId)` 测试：当 `registry.get(id, Class)` 抛 `RemoteException` 时，原异常直接向上抛（不二次包装）
- [ ] `RagConfig.rewriteQueryTransformer` bean 创建测试：`properties.queryRewriteModel()` 为空时走 `resolveDefault()`；非空且命中时走 `resolve(id).mutate()`

### 5.4 GitNexus 验证

- [ ] 改动前：`gitnexus_impact({target: "RagAdvisorFactory", direction: "upstream"})` 看依赖方
- [ ] 改动后：`gitnexus_detect_changes()` 验证只影响预期符号
- [ ] 改动后：`grep -rn 'ChatClient.Builder' src/main/java/com/smart/rag/{chat,rag,agent}/ | grep -v import | grep -v '//'` 返回 0 行（除了 import 和注释）

---

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| `LlmClientRegistry.getDefault(CHAT)` 在测试环境无候选 → 启动失败 | 中 | 高 | 测试 profile 必须提供至少一个 chat 候选（与生产一致）；单测 mock registry |
| `QueryRewriteTool` 当前用自动配置的 builder，可能隐含默认 system prompt 等配置 | 低 | 中 | 改造后查 `RewriteQueryTransformer` 文档，确认无隐式依赖；如发现需补充 prompt，在 `ChatModelAdapter` 之外不动，统一通过 `ChatClient.builder(...).defaultSystem(...)` 设置 |
| Spring AI 在未来版本可能要求必须存在 `ChatModel` bean | 低 | 中 | 本期接受此约束；若上游变更，再加 `@Primary ChatModel` bean（即方案 A） |
| 评测模块（`evaluation/runner/EvaluationRunner` + `evaluation/dataset/DatasetGenerator`）启用 profile 时触发同款启动错误 | 低（默认不激活 `@Profile("evaluation")`） | 中 | **本期不纳入**，单独建任务跟进；启用前需先把这两处改为从 `LlmClientRegistry` 取候选（`EvaluationConfig` 本身已自包含，无需改） |

---

## 7. 实施步骤（jsonl 雏形）

> 实施 jsonl 在 planning → in_progress 切换前由本 PRD 转化生成，下面是预期步骤。

1. `gitnexus_impact` 跑 `RagAdvisorFactory` / `RagConfig` / `QueryRewriteTool` 的 upstream，确认依赖方清单（不跑 `EvaluationConfig`，本期不动）
2. **新增 `infrastructure/llm/adapter/RewriteClientResolver.java`**（`@Component`）：注入 `LlmClientRegistry`，实现 `resolve(candidateId)` + `resolveDefault()`，在 `resolveDefault()` 内 catch `RemoteException` 包装为 `ModelNotFoundException`
3. **改造 `RagConfig.rewriteQueryTransformer`**：删除 `ChatClient.Builder chatClientBuilder` 形参，新增 `RewriteClientResolver resolver` 形参；`resolveRewriteBuilder` 签名同步更新；分支 A 用 `resolver.resolveDefault().mutate()`，分支 B/C 走 `resolver.resolve(id).mutate()`（无效 ID 抛 `RemoteException` 即 fail-fast）
4. **改造 `RagAdvisorFactory`**：删除 `chatClientBuilder` 字段 + 构造参数 + 构造体内的赋值语句（纯删除，无新增逻辑）
5. **改造 `QueryRewriteTool`**：构造参数 `ChatClient.Builder` → `RewriteClientResolver`，构造体 `this.chatClient = chatClientBuilder.build()` → `this.chatClient = resolver.resolveDefault()`
6. **不动 `EvaluationConfig` / `EvaluationRunner` / `DatasetGenerator`**：本期范围明确排除（见 §3.1.1、§8.1）
7. 跑 `mvn compile` + `mvn test`
8. 启动应用，按 §5.2 走 happy path（重点验证 `mode=SIMPLE` + `ragEnabled=true` 触发 query rewrite）
9. 跑 `gitnexus_detect_changes` 确认影响面
10. 跑 `grep -rn 'ChatClient\.Builder' src/main/java/com/smart/rag/{chat,rag,agent}/ | grep -v import | grep -v '//'` 应返回 0 行
11. 更新 spec：把"禁止注入 `ChatClient.Builder` 形参、统一走 `RewriteClientResolver`"写入 `.trellis/spec/backend/llm-spi.md`

---

## 8. 决策记录（2026-06-14）

1. **`EvaluationConfig` 不纳入本期**。评测子系统本期保持现状，不在本次解耦范围内。若未来评测模块被启用触发同类启动错误，单独建任务跟进。
2. **fail-fast 异常使用项目异常体系**：抛 `com.smart.rag.infrastructure.exception.ModelNotFoundException`（已存在，`ServiceException` 子类，错误码 `MODEL_NOT_FOUND(203001)`）。
   - **核实结论**：`LlmClientRegistry.getDefault(capability)` / `get(candidateId, type)` 找不到候选时抛 **`RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR)`**（C 类第三方错误），而非 `NoSuchElementException`，也永不返回 null。
   - **构造位置**：`RewriteClientResolver` 内部调用 `registry.getDefault(CHAT)` 时 catch `RemoteException`，包装为 `new ModelNotFoundException("default-chat", "未配置任何 CHAT 能力候选，无法初始化 RewriteClient/QueryRewriteTool")`。
   - **抛出后效果**：Spring 包装为 `BeanCreationException`，但根因栈保留 `ModelNotFoundException`，可读性远胜 `IllegalStateException`。
   - **错误码复用决定**：复用现有 `MODEL_NOT_FOUND(203001)`，不新增错误码——"模型不存在"语义对"无默认 chat 候选"足够贴合；若未来细分场景需要单独错误码再拆。
   - **fail-fast 范围**：仅覆盖"registry 无默认 CHAT 候选"的启动期失败场景。`resolve(candidateId)` 调用 `registry.get(id, Class)` 时若候选 ID 无效（即 §4.3 修改 2 的分支 B），原始 `RemoteException` 直接向上抛，不包装——错误信息已经够清晰，二次包装反而掩盖根因。
3. **抽出公共组件 `RewriteClientResolver`**。
   - **放置位置**：`src/main/java/com/smart/rag/infrastructure/llm/adapter/RewriteClientResolver.java`（与 `ChatModelAdapter` 同包）。
   - **分层论证（回应 DESIGN-1 反馈）**：该包已建立"`ChatCapable` ↔ Spring AI 类型适配"的先例——`ChatModelAdapter` 实现 Spring AI 的 `ChatModel` 接口。`RewriteClientResolver` 与之同构：以 `ChatModelAdapter` 为底座，输出 Spring AI 的 `ChatClient`。把它放在 `rag/config/` 或 `chat/service/` 会导致跨模块依赖（chat 和 agent 也需要使用），且与 `ChatModelAdapter` 的"配对适配器"角色被拆散。**保留 `infrastructure/llm/adapter/` 是分层一致性最优解**。
   - **职责**：封装 `registry.getDefault(CHAT)` → `ChatModelAdapter` → `ChatClient.builder(...).build()` 链路；fail-fast 异常集中在此处。
   - **API（回应 DESIGN-2 反馈：返回 ChatClient 而非 Builder）**：
     ```java
     @Component
     public class RewriteClientResolver {
         private final LlmClientRegistry registry;
         public RewriteClientResolver(LlmClientRegistry registry) { ... }

         /** 解析用于 query rewrite 的 ChatClient；candidateId 无效时抛 RemoteException（fail-fast） */
         public ChatClient resolve(@Nullable String candidateId) { ... }

         /** 永远走默认 chat 候选；registry 无默认候选时抛 ModelNotFoundException */
         public ChatClient resolveDefault() { ... }
     }
     ```
   - **返回 ChatClient 而非 Builder 的理由**（采纳 DESIGN-2）：Builder 暴露会让消费方意外修改 builder 状态（如 `.defaultSystem()`）；返回已 build 的 `ChatClient` 更安全。消费方若需要 Builder（如 `RagConfig` 喂给 `RewriteQueryTransformer.builder().chatClientBuilder(...)`），自行调 `chatClient.mutate()` 转回 Builder——`mutate()` 是 Spring AI 提供的浅拷贝契约，开销可忽略。
   - **消费方**：`RagConfig`（替换 `resolveRewriteBuilder` 私有方法）、`QueryRewriteTool`（构造期解析一次缓存）。**注意**：`RagAdvisorFactory` 因 BUG-1（死字段）只需删字段，不消费此 resolver。

---

## 9. 参考链接

- 错误堆栈完整链：见对话历史 / `chatController → ChatServiceImpl → ModeRouter → AgentModeStrategy → AdvisorInfrastructure → RagAdvisorFactory → ChatClient.Builder`
- 现有的"自建 ChatClient" 模式：`ChatServiceImpl.java:92, 119`
- 现有的"registry 取候选 + adapter"模式：`RagConfig.java:70-76`
- Spring AI 自动配置入口：`org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration`
