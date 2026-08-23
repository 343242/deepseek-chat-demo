# LLM SPI 规范

> 基础设施层 `com.smart.rag.infrastructure.llm` 与上层（chat / rag / agent）的契约。

---

## 1. Scope / Trigger

**触发场景**：任何在 chat / rag / agent 三层中使用 LLM 客户端的代码改动。

**强约束**：上层不得绕过 `LlmClientRegistry` + `RewriteClientResolver` 直接依赖 Spring AI 的 `ChatClient.Builder` / `ChatModel` 自动配置。

**为什么需要这条契约**：
- 基础设施层不向容器暴露 `ChatModel` bean（避免默认候选被硬编码进自动装配链）
- Spring AI 的 `ChatClientAutoConfiguration` 需要 `ChatModel` bean 才能创建 `ChatClient.Builder`
- 任何 `@Component` / `@Configuration` 类的构造参数或 `@Bean` 方法参数注入 `ChatClient.Builder` 都会触发"无 ChatModel bean"启动失败
- 历史遗留路径详见 task `06-14-decouple-spring-ai-chatclient-builder-injection`

---

## 2. Signatures

### `ChatModelAdapter` 桥接契约

`ChatModelAdapter` 必须实现 `ChatModel.getDefaultOptions()` 并返回 `ToolCallingChatOptions` 实例：

```java
@Override
public ChatOptions getDefaultOptions() {
    return ToolCallingChatOptions.builder().build();
}
```

**为什么必须返回 `ToolCallingChatOptions`**：
- Spring AI 的 `ToolCallAdvisor.before()` 强校验 `request.options() instanceof ToolCallingChatOptions`
- `ChatClient.builder(chatModel).build()` 会从 `chatModel.getDefaultOptions()` 拿默认 options
- 若返回 `DefaultChatOptions` 或 null → 挂载 `ToolCallAdvisor` 的请求会抛 `IllegalArgumentException: ToolCall Advisor requires ToolCallingChatOptions`
- 这是 P0 阻塞性缺陷，会破坏所有挂 tool 的 chat 路径（mode=SIMPLE/MULTI_TURN/AGENT + `hasTools()=true`）

**为什么返回通用 `ToolCallingChatOptions` 而非厂商子类**（如 `OpenAiChatOptions`）：
- `ChatModelAdapter` 是厂商无关的桥接层，不应耦合具体厂商 options
- 实际 LLM 调用由 `delegate.chat(ChatRequest)` 处理，options 仅用于 advisor 链流转
- `ToolCallingChatOptions.builder().build()` 是 Spring AI 提供的通用实现，满足 advisor 校验

**每次调用必须返回新实例**：使用 `ToolCallingChatOptions.builder().build()` 内联构造，**禁止缓存为 static 字段**——多个 ChatClient 共享同一 options 实例会有可变状态串扰风险（`toolCallbacks` 字段会被 advisor 修改）。

**包路径细节**（Spring AI 1.1.6）：
- 接口：`org.springframework.ai.chat.model.ChatModel`
- 返回类型：`org.springframework.ai.chat.prompt.ChatOptions`（**不是** `chat.model.ChatOptions`）
- 实现：`org.springframework.ai.model.tool.ToolCallingChatOptions`

### 统一装配点模式（08-17-usage-rework 起，取代"自建 ChatClient 模式"）

chat / agent 主调用链不再各自 `new ChatModelAdapter(...)`，统一经 `ChatModelAssembler` 装配：

```java
// infrastructure/llm/adapter/ChatModelAssembler（@Component，唯一组装点）
ChatClient client = assembler.chatClient(userId, candidateId, UsageScene.CHAT, conversationId);
// Agent 链路：护栏与 ChatClient 必须共享同一装饰器实例
UsageRecordingChatModel model = assembler.chatModel(userId, candidateId, UsageScene.AGENT, conversationId);
ChatClient client = assembler.chatClient(model);
```

装饰栈层序只在装配点定义一次：`UsageRecordingChatModel(ChatModelAdapter(capable))`。
`UsageRecordingChatModel` 是全部 LLM 调用的用量采集咽喉（阻塞/流式/CHAT/AGENT/INTENT 同一份逻辑），
同时暴露跨轮 token 累计供 `AgentGuardrails` 轮间检查；per-call 状态（计时/累计/归因上下文）放在
每请求实例的字段里，禁止做成单例 Bean。

- **当前装配点消费方**：`ChatServiceImpl.chat/chatStream`（CHAT）、`AgentModeStrategy`（AGENT）、`IntentClassifier`（INTENT，per-request，勿缓存）
- **未接入**：查询改写（REWRITE）——ChatClient 深藏在 Spring AI RAG 单例结构（`RewriteClientResolver` 消费方构造期缓存），per-request 用户归因需重构 RAG 组装链后接入
- **禁止**：上层直接 `new ChatModelAdapter(...)` / `new UsageRecordingChatModel(...)`（历史允许的自建点 ChatServiceImpl:121/155、AgentModeStrategy、IntentClassifier 已全部收敛到装配点）
- **禁止注入**：`ChatModel` / `ChatClient.Builder` Spring AI bean（原有约束不变）

### 上层允许的入口（保留）

```java
// 通过 Resolver 拿到 ChatClient（推荐用于 query rewrite / 工具内部 LLM 调用）
com.smart.rag.infrastructure.llm.adapter.RewriteClientResolver

public ChatClient resolve(@Nullable String candidateId);
public ChatClient resolveDefault();
```

```java
// 通过 Registry 拿到原始候选（用于多候选 fallback 链，如 ChatServiceImpl）
com.smart.rag.infrastructure.llm.registry.LlmClientRegistry

public CapabilityClient getDefault(LlmCapability capability);
public <T extends CapabilityClient> T getDefault(LlmCapability capability, Class<T> type);
public CapabilityClient get(String candidateId);
public <T extends CapabilityClient> T get(String candidateId, Class<T> type);
public List<CapabilityClient> getChain(LlmCapability capability);
public CapabilityClient find(String candidateId);  // 返回 null，不抛异常
```

### ~~自建 ChatClient 模式~~（已废除，见上方统一装配点模式）

历史做法（调用方在 `fallbackExecutor.execute` 内自行 `new ChatModelAdapter(...)`）已于 08-17-usage-rework 全部收敛到 `ChatModelAssembler`；上层不得再自建。

---

## 3. Contracts

### 模型 ID 格式契约

**所有模型 ID 必须使用 registry 候选 ID 格式**（如 `deepseek-v4-flash`），**不接受 `provider/model` 复合格式**（如 `deepseek/deepseek-v4-flash`）。

**契约校验点**：`ChatServiceImpl.resolveCandidateId(ChatRequest)` 在请求入口对 `request.model()` 做格式校验。检测到 `/` 字符立即抛 `IllegalArgumentException`，被 `GlobalExceptionHandler.handleIllegalArgument` 映射为 `ClientErrorCode.BAD_REQUEST`（业务码 100001）。

**统一性要求**：项目内所有引用模型 ID 的位置必须使用同一格式：
- API 请求体 `model` 字段：registry 候选 ID（`deepseek-v4-flash`）
- API 响应字段 `compositeId` / `modelId`：值同上（字段名保留以维持 API 兼容，但值不再是"复合"语义）
- 配置项 `app.agent.intent-model`：registry 候选 ID
- 配置项 `app.rag.query-rewrite-model`：registry 候选 ID（null 表示走默认候选）
- 文档示例（`docs/API-DOCS.md`、`docs/ARCHITECTURE.md`、`README.md`）：registry 候选 ID

**反模式**（禁止）：
- ❌ 在 yml 配置或 API 文档里写 `deepseek/deepseek-v4-flash` 这种带前缀的格式
- ❌ 在 Java 代码里加 `provider/model` 解析层（已明确选择"强制 registry ID"，不留兼容层）
- ❌ 假设 `compositeId` / `modelId` 字段值是"复合"的（值是单段 registry 候选 ID，字段名只是历史遗留）

**为何用 `IllegalArgumentException` 而非 `ClientException`**（spec 偏差说明）：
- `quality-guidelines.md` 禁止 `IllegalArgumentException`，但 `GlobalExceptionHandler.handleIllegalArgument` 把它映射为 `ClientErrorCode.BAD_REQUEST`，运行时行为等价 `ClientException(BAD_REQUEST)`
- 此处的 service-layer guard clause 选用 `IllegalArgumentException` 是为了让"格式校验失败"的语义清晰可辨（与 `ChatMode.valueOf` 抛 `IllegalArgumentException` 的既有模式一致）
- 如未来 spec 修订，可改为 `ClientException(BAD_REQUEST, msg)`，行为不变

### 注入契约

| 依赖项 | 是否允许直接注入 | 替代方案 |
|---|---|---|
| `LlmClientRegistry` | ✅ 允许（任何层） | — |
| `RewriteClientResolver` | ✅ 允许（chat / rag / agent） | — |
| `ChatModelAdapter` | ❌ 禁止注入 | 由 `RewriteClientResolver` 内部 new |
| `ChatClient.Builder`（Spring AI 自动配置） | ❌ **绝对禁止** | 用 `RewriteClientResolver` 或自建模式 |
| `ChatModel`（Spring AI bean） | ❌ **绝对禁止** | 同上 |
| `EmbeddingModel`（Spring AI bean） | ✅ 允许（基础设施层已通过 `LlmAutoConfiguration.embeddingModel` 提供；`@ConditionalOnMissingBean` 保证容器内唯一候选，无需 `@Primary`） | — |

### 返回类型契约

`RewriteClientResolver` 返回 `ChatClient`（已 build），**不返回 `ChatClient.Builder`**。需要 Builder 时消费方自行 `.mutate()`：
- `RewriteQueryTransformer.builder().chatClientBuilder(...)` 需要 `ChatClient.Builder` → 传入 `resolver.resolveDefault().mutate()`

### Spring AI 框架扩展面（合法保留）

下列 Spring AI 类型允许使用，因为它们是框架扩展点，不是 LLM 客户端调用：
- `@Tool` 注解（chat/tool/*、agent/tool/*）
- `BaseAdvisor` 实现（`AgentSystemPromptAdvisor`）
- `ChatModel` 实现（`UsageRecordingChatModel`）
- `RetrievalAugmentationAdvisor` / `DocumentPostProcessor` / `QueryTransformer`（RAG 检索链）
- `ChatClient.ChatClientRequestSpec` / `Advisor` API（Advisor 调用链）

---

## 4. Validation & Error Matrix

| 条件 | 抛出异常 | 触发点 | 处理建议 |
|---|---|---|---|
| `LlmClientRegistry.getDefault(CHAT)` 找不到默认候选 | `RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR)` | registry 内部 | 由 `RewriteClientResolver.resolveDefault()` 包装为 `ModelNotFoundException` |
| `LlmClientRegistry.get(candidateId, type)` 候选 ID 无效 | `RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR)` | registry 内部 | `RewriteClientResolver.resolve(id)` 不二次包装，原异常向上抛（fail-fast） |
| `LlmClientRegistry.find(candidateId)` 找不到 | 返回 `null`（不抛异常） | registry 内部 | 仅在需要"可选候选"语义时使用 |
| 容器初始化时无任何 CHAT 候选 | `ModelNotFoundException("default-chat", ...)` | `RewriteClientResolver.resolveDefault()` | Spring 包装为 `BeanCreationException`，启动失败，错误信息明确 |
| `@Bean` 方法参数声明 `ChatClient.Builder` | 编译期不报错，**容器启动失败** | Spring AI `ChatClientAutoConfiguration` | 改用 `RewriteClientResolver` |

### 错误码

- `MODEL_NOT_FOUND(203001)` — ServiceErrorCode，用于"无默认 CHAT 候选"
- `LLM_CONFIG_ERROR` — RemoteErrorCode，用于"候选 ID 无效"（registry 层抛出，不二次包装）

---

## 5. Good / Base / Bad Cases

### ✅ Good — Resolver 注入（推荐）

```java
@Component
public class QueryRewriteTool {
    private final ChatClient chatClient;

    public QueryRewriteTool(RewriteClientResolver resolver, ObjectMapper objectMapper) {
        this.chatClient = resolver.resolveDefault();
    }
}
```

### ✅ Base — `@Bean` 方法参数注入 Resolver

```java
@Bean
public RewriteQueryTransformer rewriteQueryTransformer(
        RagRetrievalProperties properties,
        RewriteClientResolver resolver) {  // ✅ 不是 ChatClient.Builder
    ChatClient.Builder builder = resolveRewriteBuilder(properties, resolver);
    return RewriteQueryTransformer.builder()
            .chatClientBuilder(builder)
            .promptTemplate(new PromptTemplate(template))
            .build();
}

private ChatClient.Builder resolveRewriteBuilder(
        RagRetrievalProperties properties,
        RewriteClientResolver resolver) {
    String candidateId = properties.queryRewriteModel();
    if (candidateId == null || candidateId.isBlank()) {
        return resolver.resolveDefault().mutate();
    }
    return resolver.resolve(candidateId).mutate();  // 无效 ID 抛 RemoteException（fail-fast）
}
```

### ❌ Bad — 直接注入 ChatClient.Builder（禁止）

```java
// 启动失败：No qualifying bean of type 'ChatModel'
@Component
public class QueryRewriteTool {
    public QueryRewriteTool(ChatClient.Builder chatClientBuilder, ...) {
        this.chatClient = chatClientBuilder.build();
    }
}
```

---

## 6. Tests Required

新增任何消费 `RewriteClientResolver` 的类时，必须覆盖以下断言点：

| 测试点 | 断言 |
|---|---|
| `RewriteClientResolver.resolveDefault()` 在 registry 无候选时抛 `ModelNotFoundException` | exception modelId == "default-chat"；errorCode == 203001 |
| `RewriteClientResolver.resolve(null)` 委托给 `resolveDefault()` | 返回非 null ChatClient |
| `RewriteClientResolver.resolve(" ")` 等价于 `resolve(null)` | 同上 |
| `RewriteClientResolver.resolve(validId)` 返回基于该候选的 ChatClient | chatClient 非空 |
| `RewriteClientResolver.resolve(invalidId)` 不 catch `RemoteException` | 原 `RemoteException` 向上抛 |
| `RagConfig.resolveRewriteBuilder` 在 candidateId 无效时 fail-fast | 不静默 fallback 到默认候选 |

测试位置参考：`src/test/java/com/smart/rag/infrastructure/llm/adapter/RewriteClientResolverTest.java`（P1 跟进，本期未编写）。

---

## 7. Wrong vs Correct

### ❌ Wrong — 构造函数注入 `ChatClient.Builder`

```java
// RagAdvisorFactory.java（历史版本，已删除）
public class RagAdvisorFactory {
    private final ChatClient.Builder chatClientBuilder;  // 死字段 + 强依赖自动配置

    public RagAdvisorFactory(ChatClient.Builder chatClientBuilder, ...) {
        this.chatClientBuilder = chatClientBuilder;
    }
}
```

**问题**：
1. Spring AI `ChatClientAutoConfiguration` 必须从容器找到 `ChatModel` bean 才能创建 `ChatClient.Builder`
2. 基础设施层不暴露 `ChatModel` bean
3. → 启动失败：`No qualifying bean of type 'ChatModel'`
4. 即便不报错，构造参数 `ChatClient.Builder` 也是反模式：暴露 Builder 让消费方意外修改 builder 状态

### ✅ Correct — 删除死字段，需要 LLM 调用时通过 Resolver

```java
// RagAdvisorFactory.java（当前版本）
public class RagAdvisorFactory {
    // 字段 chatClientBuilder 已删除（死字段，从未被引用）

    public RagAdvisorFactory(VectorStore vectorStore, ...) {
        // 无 ChatClient.Builder 参数
    }
}

// 真正需要 LLM 调用的类（QueryRewriteTool）
public class QueryRewriteTool {
    private final ChatClient chatClient;

    public QueryRewriteTool(RewriteClientResolver resolver, ObjectMapper objectMapper) {
        this.chatClient = resolver.resolveDefault();
    }
}
```

---

## Anti-patterns（禁止）

### Don't：把"无候选"静默 fallback 到默认候选

```java
// ❌ Bad：掩盖配置错误
try {
    return resolver.resolve(rewriteCandidateId);
} catch (RemoteException e) {
    log.warn("Candidate not found, falling back");
    return resolver.resolveDefault();  // 掩盖了配置错误
}
```

**为什么禁止**：用户配置了无效的候选 ID，应该立刻在启动期发现并修复，而不是运行期静默用默认候选，掩盖配置错误。

**正确做法**：让 `RemoteException` 向上抛，Spring 包装为 `BeanCreationException`，启动失败信息明确告知"配置的候选 ID 无效"。

### Don't：在 chat / rag / agent 三层新写 `new ChatModelAdapter(...)`

```java
// ❌ Bad：在业务层直接 new adapter
ChatClient client = ChatClient.builder(new ChatModelAdapter(chatCapable)).build();
```

**为什么禁止**：桥接逻辑必须集中在 `infrastructure/llm/adapter/` 包内，避免业务层耦合 Spring AI 类型。

**正确做法**：通过 `RewriteClientResolver` 间接使用；除非是 `ChatServiceImpl` 这种多候选 fallback 主链场景（PRD §3.2 列举的允许自建点）。

---

## 思考参数契约（thinking / reasoning_content）

> task `08-04-thinking-effort-adapter`。改动全在 `infrastructure/llm` 包内：`ThinkingConfig` / `ThinkingDialect` / `ThinkingBodyResolver` + `GenericChatClient` / `ChatModelAdapter`。

### 请求侧注入

- **触发条件**：候选 `params.thinking` 已配置，或 `ChatRequest.thinking` 非空。两者皆无 → 不注入任何思考参数（零行为变更，字节级不变）。
- **优先级**：`ChatRequest.thinking`（per-request）> 候选 `params.thinking` 默认 > 不注入。per-request 仅覆盖参数，**方言始终由候选声明**。
- **`supports-thinking: true` 仅是能力声明，不触发注入**（与 `supportsStreaming` 同语义，fail-fast）。
- **YAML 键名必须 kebab-case**：`params.thinking.{dialect,enabled,reasoning-effort,thinking-budget}`。Spring Boot `Map<String,Object>` 绑定保留原始键名，camelCase（`reasoningEffort`）会被**静默忽略**——配置失效无报错。

### 方言映射（由候选显式声明，禁止按 provider/model 推断）

| 方言 | 请求体字段 | 代表厂商 |
|---|---|---|
| `effort` | `thinking: {type: enabled\|disabled}` + `reasoning_effort`（可选） | DeepSeek、智谱 GLM |
| `budget` | `enable_thinking: bool` + `thinking_budget`（可选） | 百炼 Qwen |

### 响应侧暴露

- SPI：阻塞 `LlmResponse.reasoningContent()`；流式 `StreamChunk.reasoningContent()`（reasoning delta 以独立 chunk 即时下发，先于 content，保 TTFT）。
- Spring AI 边界：`AssistantMessage.getMetadata().get("reasoning_content")`。**不得**放 `ChatResponse.metadata`（该槽位被 usage 占用语义）。
- 流式路径中，轮末汇总包（finish_reason / `[DONE]` 兜底）必须携带**完整累积** reasoning——`MessageAggregator` 对 metadata 是 `putAll`（last-writer-wins，非拼接），累积必须在 `readSse` 层完成。

### 工具调用多轮回传（DeepSeek 400 规避）

- 上一轮 assistant 消息（含 tool_calls）的完整 `reasoning_content` 必须回传到下一轮请求体：`extractHistory`（AssistantMessage.metadata → `MessageInformation.metadata`）→ `buildRequestBody`（assistant+tool_calls 分支输出 `reasoning_content` 字段）。
- **仅工具调用场景回传**：无 tool_calls 的 assistant 消息不注入（纯多轮对话中该字段被 API 忽略，避免冗余）。
- 阻塞路径经 `parseResponse` 一次性提取；流式路径经 `readSse` 累积 → 轮末汇总包 → `MessageAggregator.putAll` 保留 last-winner。

### 禁止（Anti-patterns）

- ❌ YAML 用 camelCase 键（`reasoningEffort`/`thinkingBudget`）——静默失效。
- ❌ 以 `supports-thinking` 为注入门槛或自动注入"开启思考 + 厂商默认档"——掩盖配置缺失，违反 fail-fast。
- ❌ 按 provider/model 名推断方言（同一模型跨平台方言不同，如 GLM-5.2 智谱=EFFORT / 百炼=BUDGET）。
- ❌ 流式路径在 `MessageAggregator` 之后拼接 reasoning 片段——`putAll` 非拼接，轮末只剩最后片段，回传不完整 → DeepSeek 400。

---

## 百炼 SDK 客户端接入（08-23-bailian-sdk-integration）

> bailian provider 的 CHAT/EMBEDDING 底层实现为 dashscope-sdk-java（官方 SDK），
> 经 `ProviderClientFactory` 扩展点注入；RERANK 保留手写客户端（官方推荐路径即兼容端点）。

### 工厂分流与守卫

- `ChatCapabilityStrategy` 与 Embedding/Rerank 同构（`AbstractProviderFactoryAwareStrategy`）。
  bailian 的三能力均有 `ProviderClientFactory`；非 bailian provider 走 Generic* 回落。
- **BYOK/DB 守卫（CHAT）**：DB 行 `provider_code='bailian'` + 自定义 baseUrl（私有网关）不得
  被 SDK 客户端劫持（DashScope 原生协议打该 URL 会静默打挂）。`BailianChatClientFactory` 仅在
  baseUrl 属 `dashscope.aliyuncs.com` / `*.maas.aliyuncs.com` 或候选显式 `params.sdk-client: true`
  时产出 `BailianChatClient`，否则回落 `GenericChatClient`。
- **URL 归一化**：SDK baseUrl = provider.url 的域名部分 + `/api/v1`（`DashScopeUrls`）。stable 的
  `.../compatible-mode/v1` 兼容层路径必须剥离——rerank 兼容端点 `/compatible-api/v1/reranks` 为
  绝对路径，与带路径的 provider.url 直接拼接会产生双路径。
- apiKey 经 param 级 `.apiKey(...)` 传递；**禁止** `Constants.baseHttpApiUrl` 全局静态覆盖
  （多客户端多域名共存会互相污染）。

### SDK facade 双路由（P0 真机实测矩阵）

| 模型族 | facade | 路由 |
|---|---|---|
| qwen3.7-plus / qwen3.8-max（次版本 ≥3.7）及 `-vl` 系 | `MultiModalConversation` | multimodal-generation |
| qwen3-max / qwen-plus-latest（其余） | `Generation` | text-generation |

- 路由由候选 `params.route: text|multimodal` 显式声明，未声明按模型名推断
  （`BailianChatClient.resolveRoute`）。**路由错配服务端报 400 `url error`**——SDK Models
  常量收录某模型 ≠ 该路由可调（qwen3.7-plus 收录于 Generation.Models 但 text 路由拒服务）。
- multimodal 路由消息形状：content 为 `[{text: ...}]` 数组（纯文本用法）；工具轮 assistant 的
  content 允许空数组 + toolCalls 元数据；tool 结果消息 role=tool + toolCallId（真机验证通过）。

### 流式契约注意（易踩坑）

- **中间块可能携带非终止 finish_reason（字面 `"null"` 字符串）**：收口轮末汇总包必须以
  「识别到终止枚举（stop/length/tool_calls/content_filter）」为触发条件，仅判非空会过早收口、
  吞掉真正终止块（P1 真机发现，`DashScopeStreamAccumulator` 有回归单测守护）。
- DashScope 每 chunk 均携带累积 usage（非 OpenAI 的流末 usage-only 块形态）；tool_calls 分片
  为 OpenAI index 语义（首片 id+name、后续 arguments 片段、尾片 finish=tool_calls 且
  toolCalls 空片段）→ 轮末汇总必须由 accumulator drain 产出，不能读尾片字段。
- 显式 `incrementalOutput(true)` 才透传增量；false/null 时 SDK 会强制 wire 层 true 并内部
  合并后发累积值（`Generation.mergeSingleResponse`），与 StreamChunk 增量语义不符。

### 依赖对齐约束

- okhttp 锁 4.12.0（与 SDK 传递依赖同版）；`opendataloader-pdf-core` 传递 `okhttp-jvm:5.4.0`
  已 exclusion——新增传递 okhttp 的依赖时必须检查 duplicate-class。
- SDK HTTP 路径无内置重试（仅 WebSocket 有）；重试语义唯一归 Resilient 层。
- `TextEmbedding` facade 无 ConnectionOptions 构造器（沿用 SDK 默认超时）；`Generation` /
  `MultiModalConversation` 有（构造器第三参）。

---

## Related

- 任务记录：
  - `.trellis/tasks/archive/2026-06/06-14-decouple-spring-ai-chatclient-builder-injection/prd.md`（基础解耦）
  - `.trellis/tasks/06-14-fix-chatmodeladapter-default-options-toolcallingchatoptions/prd.md`（getDefaultOptions 修复）
  - `.trellis/tasks/08-04-thinking-effort-adapter/`（思考参数统一适配，2026-08）
- 异常体系：[Error Handling](./error-handling.md)
- 设计原则：[Quality Guidelines](./quality-guidelines.md)
