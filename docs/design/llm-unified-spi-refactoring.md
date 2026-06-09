# LLM 调用层统一 SPI 重构方案

> **版本**: v1.0  
> **日期**: 2026-06-08  
> **状态**: 设计方案（待评审）

---

## 1. 背景与动机

### 1.1 现状问题

当前项目的 LLM 调用点散落在 12 个位置，存在三条核心问题：

**问题一：四种并行的传输层，十二个调用点**

| # | 调用点 | 传输层类型 | 传输层 | 重试/熔断 |
|---|--------|-----------|--------|----------|
| 1 | `AbstractModeStrategy` 聊天（阻塞） | ① Spring AI | `ChatClient.call()` | 由 `ChatServiceImpl` 编排 |
| 2 | `AbstractModeStrategy` 聊天（流式） | ② 原生 OkHttp SSE | `OkHttpSseModelStreamClient.stream()` | `StreamRetryHandler` |
| 3 | `MultiTurnModeStrategy` 多轮流式 | ② 原生 OkHttp SSE | `OkHttpSseModelStreamClient.stream()` | `StreamRetryHandler` |
| 4 | `IntentClassifier` 意图分类 | ① Spring AI | `ChatClient.prompt().call()` | 自写 for-loop 2 次 |
| 5 | `AgentModeStrategy` Agent 工具调用 | ① Spring AI | `ChatClientRegistry.getChatModel()` + `TokenCountingChatModel` | 依赖 `StreamRetryHandler` |
| 6 | `RagConfig` Query Rewrite | ① Spring AI | `RewriteQueryTransformer` 内部 `ChatClient.Builder` | 无独立重试 |
| 7 | `DashScopeEmbeddingModel` 向量嵌入 | ③ RestClient（百炼原生 API） | `RestClient.post()` | 自写 3 次指数退避 |
| 8 | `BailianRerankPostProcessor` 重排序 | ④ RestClient（Bailian `/reranks`） | `RestClient.post()` | 自写 3 次指数退避 + 专用线程池 |
| 9 | `LlmJudgeImpl` Judge 评估 | ① Spring AI | `ChatClient.prompt().call()` | 自写 for-loop 2 次 |
| 10 | `DatasetGenerator` 数据集生成 | ① Spring AI | `ChatClient.prompt().call()` | 无独立重试 |
| 11 | `DeepSeekModelProvider` 模型发现 | ③ RestClient | `RestClient.get().uri('/models')` | 无重试 |
| 12 | `MiniMaxModelProvider` 模型发现 | ③ RestClient | `RestClient.get().uri('/v1/models')` | 无重试 |

> **传输层类型分组**：① Spring AI `ChatClient`（6 个调用点）② 原生 OkHttp SSE（2 个调用点）③ RestClient / 百炼原生 API（2 个调用点）④ RestClient / Bailian 非标准 API（1 个调用点）⑤ RestClient / 模型发现（2 个调用点，实为同一类）。
> 注：③④⑤ 底层均为 `RestClient`，但目标 API 不同（百炼 Embedding / 百炼 Rerank / 模型列表），重试策略各异，故单独计数。

**问题二：嵌入/重排绕过 Provider 体系**

`DashScopeEmbeddingModel` 和 `BailianRerankPostProcessor` 直接用 `RestClient` 调用 LLM API，完全不走 `ModelProvider` 体系，导致：
- 无法统一管理供应商健康状态
- 无法统一施加重试/熔断策略
- 新增供应商时需要在多个位置分散配置
**问题三：重试策略碎片化**

多个调用点各自实现重试，逻辑不一致（2 次/3 次、有无退避、有无熔断），无法保证弹性行为的统一性。

### 1.2 保留的设计模式

以下**设计模式**经过验证是合理的，本次重构保留其核心思想，但**实现类被新架构替代**：

- **SPI 模式** — 原 `ModelProvider` 接口 + `AbstractModelProvider` 模板方法的 SPI 思想保留，由 `LlmProvider`（轻量工厂接口）接替
- **注册表模式** — 原 `ProviderRegistry` 自动发现 + `ModelRouter` 路由的思想保留，由 `LlmClientRegistry` 接替
- **两阶段降级算法** — 原 `StreamRetryHandler`（同模型重试 → 跨模型降级）核心算法保留，由 `RetryPolicy` + `FallbackExecutor` 接替
- **熔断器** — 原 `ModelCircuitBreakerRegistry` 三态熔断器保留，由 `LlmCircuitBreakerAdapterRegistry` 统一管理
- **首包探测** — 原 `ProbeStreamHandler` 首包探测保留，由 `ProbeHandler` 在 `ResilientChatClient` 中施加

---

## 2. 设计目标

| 目标 | 度量 |
|------|------|
| **新增供应商零代码** | OpenAI 兼容 API 只需在 `providers` 中添加连接配置 + 在 `chat/embedding/rerank` candidates 中添加模型引用，不写 Java 代码 |
| **新增能力类型可扩展** | 新增 TTS/STT 等能力只需扩展枚举 + 新增接口/抽象类/装饰器 + 在 `LlmClientRegistry` 和 `LlmProperties` 的 switch 中添加 case，已有调用方代码不修改 |
| **重试/熔断统一** | 所有 LLM 调用共享同一套弹性策略配置，不分散在各调用点 |
| **面向接口编程** | 调用方依赖能力接口（`ChatCapable` 等），不依赖具体实现 |
| **配置驱动** | 模型声明、Fallback Chain、弹性参数全部 YAML 配置化 |
| **向后兼容** | 分阶段迁移，核心聊天路径最后迁移，可灰度切换 |

---

## 3. 核心架构

### 3.1 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│                       调用方 (Callers)                        │
│  ChatService · IntentClassifier · LlmJudge · RagConfig 等    │
└───────────────────────────┬──────────────────────────────────┘
                            │ 依赖能力接口（ChatCapable / EmbeddingCapable / RerankCapable）
┌───────────────────────────▼──────────────────────────────────┐
│                    Resilience 弹性层（装饰器）                  │
│  ResilientChatClient · ResilientEmbeddingClient               │
│  ResilientRerankClient                                        │
│  （重试策略 RetryPolicy · 熔断保护 CircuitBreaker              │
│    · 首包探测 ProbeHandler · 跨模型降级 FallbackExecutor）      │
└───────────────────────────┬──────────────────────────────────┘
                            │ 委托
┌───────────────────────────▼──────────────────────────────────┐
│               CapabilityClient 能力抽象层（接口 + 抽象类）       │
│  接口：ChatCapable · EmbeddingCapable · RerankCapable         │
│  抽象类：AbstractChatClient · AbstractEmbeddingClient          │
│           AbstractRerankClient                                 │
└───────────────────────────┬──────────────────────────────────┘
                            │ 由 LlmProvider.createClient(candidate) 创建
┌───────────────────────────▼──────────────────────────────────┐
│              LlmProvider 供应商层（轻量工厂接口）               │
│  ┌────────────────────────────┐ ┌────────────────────────┐   │
│  │ GenericOpenAiProvider      │ │ @Component Providers   │   │
│  │ （Registrar 从 YAML 创建）  │ │ （自注册，特殊 Client） │   │
│  │                            │ │                        │   │
│  │ 按 endpoint 创建通用 Client │ │ 如需保留特殊逻辑       │   │
│  └────────────────────────────┘ └────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────┐
  │ 配置层（供应商与模型解耦）                                    │
  │                                                            │
  │ providers: Map<id, ProviderConfig>  ← 只关心连接            │
  │   (url / apiKey / endpoints)                               │
  │                                                            │
  │ chat / embedding / rerank: ModelGroup  ← 按能力分组         │
  │   (default-model / deep-thinking-model / candidates[])     │
  │   candidates 按 priority 排序 = Fallback Chain              │
  └────────────────────────────────────────────────────────────┘

  LlmClientRegistry（遍历 ModelGroup → 按 provider 引用查找 Provider → 创建 Client → Resilient 包装）

  底层依赖：Spring AI ChatModel · RestClient · OkHttp
```

> **启动时组装**：LlmClientRegistry 遍历 ModelGroup 的 candidates，按 `candidate.provider` 查找
> LlmProvider Bean → Provider 创建原始 CapabilityClient → Registry 统一包装 Resilient 装饰器 → 注册到索引。
> **运行时调用**：Callers → Resilient 装饰器（重试/熔断/探测） → 原始 CapabilityClient → LLM API。
> 供应商与模型解耦——Provider 只是工厂，ModelGroup 决定"用哪些模型"。
> Callers 只依赖弹性层暴露的能力接口，不直接接触 LlmProvider 或底层 SDK。

### 3.2 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 供应商与模型解耦 | **供应商只关心连接，模型按能力分组并引用供应商** | 关注点分离：ProviderConfig 只有 url/apiKey/endpoints，ModelGroup 有 candidates/default-model |
| LlmProvider 设计 | **轻量工厂接口**（id + config + createClient），不持有模型/clients | Provider 只是"怎么创建客户端"的工厂，模型配置独立管理 |
| candidates = Fallback Chain | **候选列表按 priority 排序即为降级链**，不需要单独的 fallback 配置 | 配置简化，candidates 和 Fallback Chain 是同一个列表 |
| 命名槽位 | **default-model + deep-thinking-model** | 调用方按场景选择模型，deep-thinking-model 可指向不同模型 |
| 通用供应商 | **GenericOpenAiProvider**，由 Registrar 从 YAML 批量创建 | 90%+ 的供应商是 OpenAI 兼容 API，纯配置即可接入 |
| 特殊 Client | **按 endpoint 路径自动识别**（如百炼 Embedding → BaiLianEmbeddingClient） | 不需要独立的百炼 Provider，特殊逻辑封装在 Client 中 |
| 接口 vs 抽象类 | **CapabilityClient 用接口，三个能力客户端用抽象类** | 接口保证根的灵活性，抽象类提取公共字段避免重复 |
| 弹性层粒度 | **三个类型安全的装饰器，共享同一套配置**（详见 §7.1 + §7.7） | 类型安全 + 配置统一 + 装饰器透明性 |
| Spring AI 兼容 | **`ChatCapable extends ChatModel`**，接口级原生继承 | 零转换成本，客户端实例天然就是 ChatModel |
| 重试策略覆盖 | **全局默认 + 按能力类型可选覆盖** | YAML `resilience.retry-overrides` 按能力覆盖 |

---

## 4. 接口与抽象类设计

### 4.1 `LlmCapability` — 能力枚举

```java
package com.smart.rag.infrastructure.llm;

/**
 * 模型能力声明
 * <p>
 * 每个 ModelCandidate 通过 capability 声明该模型支持的操作。
 * 调用方据此过滤可用客户端，Registry 据此构建按能力分类的索引。
 * <p>
 * 扩展方式：新增枚举值即可，不影响已有代码。
 * <p>
 * <b>CHAT 的流式支持</b>：CHAT 能力同时提供 {@code chat()}（阻塞）和 {@code chatStream()}（流式）方法。
 * 模型是否支持流式由 {@code ModelCandidate.supportsStreaming()} 字段声明（YAML: {@code supports-streaming: true}），
 * 未声明时 {@code chatStream()} 抛出 {@code UnsupportedOperationException}。
 */
public enum LlmCapability {
    /** 对话能力（同时支持阻塞 chat() 和流式 chatStream()，流式支持由 ModelCandidate.supportsStreaming 声明） */
    CHAT,
    /** 向量嵌入 */
    EMBEDDING,
    /** 重排序 */
    RERANKING;
    // === 未来扩展示例 ===
    // TTS,              // 文本转语音
    // SPEECH_TO_TEXT,   // 语音转文本
    // IMAGE_GENERATION, // 图像生成
}
```

### 4.2 `ModelCandidate` — 模型候选声明

```java
package com.smart.rag.infrastructure.llm;

import java.util.Map;

/**
 * 模型候选声明——描述一个可调用的 LLM 模型
 * <p>
 * 映射 YAML 中 {@code app.llm.<capability>.candidates[]} 的每个条目。
 * 供应商与模型解耦：候选声明引用供应商 id，不嵌套在供应商配置下。
 * <p>
 * <b>一对一约束</b>：每个候选声明且仅声明一种能力（由所属 {@code ModelGroup} 决定）。
 *
 * <pre>
 * YAML 示例：
 * chat:
 *   candidates:
 *     - id: qwen3-max           ← 候选唯一标识
 *       provider: bailian       ← 引用供应商 id
 *       model: qwen3-max        ← 发送给 LLM API 的原始模型名
 *       supports-thinking: true
 *       priority: 4
 * </pre>
 */
public record ModelCandidate(
    /** 候选唯一标识（用于 default-model / deep-thinking-model 引用，如 "qwen3-max"） */
    String id,

    /** 引用的供应商 id（如 "bailian"、"deepseek"、"ollama"） */
    String provider,

    /** 发送给 LLM API 的原始模型名（如 "qwen3-max"、"Qwen/Qwen3-Embedding-8B"） */
    String model,

    /** 优先级，数字越小越优先，candidates 按此排序即为 Fallback Chain */
    int priority,

    /** 该候选声明的能力（由所属 ModelGroup 决定，冗存便于查询） */
    LlmCapability capability,

    // ====== 模型级元数据（可选，按能力类型使用） ======
    // 注意：以下字段仅对特定能力有意义，其余场景为 null。
    // Embedding candidate: dimension 非 null，supportsThinking/supportsStreaming 为 null
    // Chat candidate: supportsThinking/supportsStreaming 非 null，dimension 为 null

    /** 是否支持深度思考（仅 chat 场景，YAML: {@code supports-thinking}，非 chat 时为 null） */
    Boolean supportsThinking,

    /** 向量维度（仅 embedding 场景，非 embedding 时为 null） */
    Integer dimension,

    /** 是否支持流式输出（仅 chat 场景，YAML: {@code supports-streaming}，非 chat 时为 null） */
    Boolean supportsStreaming,

    /** 默认调用参数（temperature、maxTokens 等），调用方可以覆盖 */
    Map<String, Object> params
) {}
```

### 4.3 `CapabilityClient` — 客户端根接口

```java
package com.smart.rag.infrastructure.llm;

/**
 * 能力客户端根接口
 * <p>
 * 所有 LLM 能力客户端（Chat / Embedding / Rerank）的公共契约。
 * 不定义具体 LLM 调用方法——通过 Registry 的类型查询获取具体能力接口。
 *
 * <pre>
 * 使用示例：
 * ChatCapable chat = registry.getDefault(LlmCapability.CHAT, ChatCapable.class);
 * LlmResponse resp = chat.chat(ChatRequest.of("你好"));
 * </pre>
 */
public interface CapabilityClient {

    /** 候选唯一标识（对应 YAML candidate.id，如 "qwen3-max"） */
    String candidateId();

    /** 供应商 ID（对应 YAML candidate.provider，如 "bailian"） */
    String providerId();

    /** 发送给 LLM API 的原始模型名（对应 YAML candidate.model） */
    String modelName();

    /** 该客户端声明的能力（一对一） */
    LlmCapability capability();

    /** 该客户端是否可用（供应商 API key 有效 + 基础连通性） */
    boolean isAvailable();

    /** 返回模型候选声明 */
    ModelCandidate candidate();
}
```

### 4.4 `LlmProvider` — 供应商接口（轻量工厂）

```java
package com.smart.rag.infrastructure.llm;

import com.smart.rag.infrastructure.llm.config.ProviderConfig;

/**
 * LLM 供应商接口
 * <p>
 * 供应商只负责"怎么连"和"怎么创建客户端"，不持有模型列表。
 * 模型配置独立管理（{@code ModelGroup} + {@code ModelCandidate}），通过 {@code provider} 字段引用供应商。
 * <p>
 * <b>职责</b>：
 * <ol>
 *   <li>识别自己是谁（id）</li>
 *   <li>提供连接配置（config → url / apiKey / endpoints）</li>
 *   <li>按候选模型声明创建对应的能力客户端（createClient）</li>
 * </ol>
 * <p>
 * <b>实现方式</b>：
 * <ul>
 *   <li>OpenAI 兼容 API → {@code GenericOpenAiProvider}（配置驱动，Registrar 创建）</li>
 *   <li>非标准 API → 手写 {@code @Component}（如需保留特殊 Client 逻辑）</li>
 * </ul>
 */
public interface LlmProvider {

    /** 供应商 id（对应 YAML providers key，如 "bailian"、"deepseek"） */
    String id();

    /** 供应商连接配置（url、apiKey、endpoints） */
    ProviderConfig config();

    /**
     * 按候选模型声明创建能力客户端
     * <p>
     * Provider 根据 {@code candidate.capability()} 选择对应 endpoint，
     * 使用 {@code config().url()} + endpoint 构建 HTTP 客户端。
     * <p>
     * 创建的是原始客户端（未包装 Resilience），
     * 由 {@code LlmClientRegistry} 在注册时统一包装 Resilient 装饰器。
     *
     * @param candidate 模型候选声明
     * @return 对应的能力客户端实例
     */
    CapabilityClient createClient(ModelCandidate candidate);

    /**
     * 释放资源（连接池、线程池等）
     * <p>
     * 不持有资源的 Provider（如纯配置驱动）无需覆写。
     */
    default void close() {}
}
```
### 4.5 `Message` — 对话消息

```java
package com.smart.rag.infrastructure.llm;

import java.util.Map;

/**
 * 对话消息
 * <p>
 * Agent 场景下 {@code toolCallId} 用于匹配工具调用的请求-响应配对，
 * 不可丢弃。
 */
public record Message(
    String role,
    String content,
    /** 工具调用 ID（仅 role=tool 时非空），用于 Agent 场景的请求-响应配对 */
    String toolCallId,
    /** 附加元数据（如 tool_calls 列表、name 等），不参与 equals/hashCode */
    Map<String, Object> metadata
) {
    public static Message user(String content) { return new Message("user", content, null, Map.of()); }
    public static Message assistant(String content) { return new Message("assistant", content, null, Map.of()); }
    public static Message system(String content) { return new Message("system", content, null, Map.of()); }

    /**
     * 工具响应消息（保留 toolCallId 用于 Agent 请求-响应配对）
     */
    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, toolCallId, Map.of());
    }

    /**
     * 自定义 equals/hashCode：仅比较 role、content、toolCallId，排除 metadata。
     * <p>
     * Java record 默认所有组件参与 equals/hashCode，但 metadata 是附加调试信息
     * （如 tool_calls 列表），不应影响消息的身份判定（如对话历史去重）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message other)) return false;
        return java.util.Objects.equals(role, other.role)
            && java.util.Objects.equals(content, other.content)
            && java.util.Objects.equals(toolCallId, other.toolCallId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(role, content, toolCallId);
    }
}
```

### 4.6 `ChatRequest` — Chat 请求模型

```java
package com.smart.rag.infrastructure.llm;

import java.util.List;
import java.util.Map;

/**
 * Chat 请求
 * <p>
 * 仅包含 Chat 场景所需的字段。Embedding 和 Rerank 各自定义独立的请求类型。
 */
public record ChatRequest(
    /** 用户输入 */
    String input,

    /** System prompt */
    String systemPrompt,

    /** 对话历史（仅多轮对话使用） */
    List<Message> history,

    /** 温度（覆盖 ModelCandidate.params 中的值） */
    Double temperature,

    /** 最大 token 数 */
    Integer maxTokens,

    /** Top-P 采样 */
    Double topP,

    /** 额外参数，透传给底层 SDK */
    Map<String, Object> extraParams
) {
    public static ChatRequest of(String input) {
        return new ChatRequest(input, null, List.of(),
            null, null, null, Map.of());
    }

    public static ChatRequest withSystem(String systemPrompt, String input) {
        return new ChatRequest(input, systemPrompt, List.of(),
            null, null, null, Map.of());
    }
}
```

> **设计决策**：不再使用统一的 `ChatRequest` 万能 Record。Chat 使用 `ChatRequest`，
> Embedding 使用 `embed(String text, EmbeddingType type)` 方法参数，
> Rerank 使用独立的 `RerankRequest`（见 4.9 节）。每种能力只看到自己需要的字段。

### 4.7 `EmbeddingType` — 嵌入类型

```java
package com.smart.rag.infrastructure.llm;

/**
 * 向量嵌入类型
 * <p>
 * 区分检索时的查询向量和入库时的文档向量。
 * 部分模型（如百炼 text-embedding-v4）对两者使用不同的编码策略。
 */
public enum EmbeddingType {
    /** 检索查询 */
    QUERY,
    /** 文档索引 */
    DOCUMENT
}
```

### 4.8 `LlmResponse` — Chat 响应

```java
package com.smart.rag.infrastructure.llm;

import java.util.List;
import java.util.Map;

/**
 * 统一 Chat 响应
 * <p>
 * 命名为 {@code LlmResponse} 以避免与 Spring AI 的
 * {@code org.springframework.ai.chat.model.ChatResponse} 类型冲突。
 * {@code ChatCapable} 继承 Spring AI {@code ChatModel}，
 * 子类实现 {@code call(Prompt)} 时返回 Spring AI 的 {@code ChatResponse}，
 * 而本 SPI 的 {@code chat()} 方法返回 {@code LlmResponse}。
 */
public record LlmResponse(
    /** 生成的文本内容 */
    String content,

    /** 是否被截断（达到 maxTokens） */
    boolean truncated,

    /** Token 使用量 */
    TokenUsage tokenUsage,

    /** 工具调用结果（Agent 场景） */
    List<ToolCall> toolCalls,

    /** 供应商原始响应元数据（调试用，不暴露未类型化对象） */
    Map<String, Object> responseMetadata
) {
    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {}
    public record ToolCall(String id, String name, String arguments) {}
}
```

### 4.9 `RerankRequest` / `RerankResult`

```java
package com.smart.rag.infrastructure.llm;

import java.util.List;

/**
 * 重排序请求
 * <p>
 * 不包含 topN 字段——截断由 {@link RerankCapable#rerank(RerankRequest, int)} 重载方法处理。
 */
public record RerankRequest(
    /** 检索查询文本 */
    String query,
    /** 候选文档列表 */
    List<String> documents
) {}

/**
 * 重排序结果
 */
public record RerankResult(
    /** 在原始文档列表中的索引 */
    int originalIndex,
    /** 重排序得分（越高越相关） */
    double score,
    /** 文档内容 */
    String document
) {}
```

---

## 5. 能力契约接口

> **设计决策**：每种能力定义独立的接口（`ChatCapable` / `EmbeddingCapable` / `RerankCapable`），
> 抽象类和弹性装饰器均实现同一接口。调用方依赖接口而非实现类，
> Registry 返回的对象无论是否经过 Resilient 包装，类型签名一致。

### 5.1 `ChatCapable` — Chat 能力契约

```java
package com.smart.rag.infrastructure.llm;

import reactor.core.publisher.Flux;
import java.util.List;

/**
 * Chat 能力契约
 * <p>
 * 定义 Chat 场景的全部操作。AbstractChatClient 和 ResilientChatClient 均实现此接口。
 * 调用方通过此接口与 Chat 客户端交互，无需关心是否经过弹性包装。
 * <p>
 * <b>Spring AI 原生兼容</b>：本接口直接继承 Spring AI 的 {@code ChatModel}，
 * 因此任何 {@code ChatCapable} 实例天然就是 {@code ChatModel}，可直接用于
 * Agent 工具调用等需要 Spring AI 原生类型的场景，无需 unwrap 转换。
 * 需要 {@code ChatClient} 的场景通过 {@code ChatClient.builder(chatCapable).build()} 构造。
 */
public interface ChatCapable extends CapabilityClient, org.springframework.ai.chat.model.ChatModel {

    /** 阻塞式对话 */
    LlmResponse chat(ChatRequest request);

    /** 流式对话（SSE） */
    Flux<String> chatStream(ChatRequest request);

    /** 带工具调用的对话（Agent 场景） */
    default LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        throw new UnsupportedOperationException(
            "Tool calling not supported by " + candidateId());
    }

    /** 是否支持流式（由 ModelCandidate.supportsStreaming 声明，未声明时默认 false） */
    default boolean supportsStreaming() {
        return candidate() != null
            && candidate().supportsStreaming() != null
            && candidate().supportsStreaming();
    }
}
```

### 5.2 `EmbeddingCapable` — Embedding 能力契约

```java
package com.smart.rag.infrastructure.llm;

import java.util.List;

/**
 * Embedding 能力契约
 */
public interface EmbeddingCapable extends CapabilityClient {

    /** 单条文本向量嵌入 */
    float[] embed(String text, EmbeddingType type);

    /** 批量文本向量嵌入（默认逐条调用，子类可覆写为批量 API） */
    default List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        return texts.stream().map(text -> embed(text, type)).toList();
    }

    /** 向量维度 */
    int dimensions();
}
```

### 5.3 `RerankCapable` — Rerank 能力契约

```java
package com.smart.rag.infrastructure.llm;

import java.util.List;

/**
 * Rerank 能力契约
 */
public interface RerankCapable extends CapabilityClient {

    /** 重排序 */
    List<RerankResult> rerank(RerankRequest request);

    /** 带 topN 截断的重排序（默认客户端截断，子类可覆写为服务端截断） */
    default List<RerankResult> rerank(RerankRequest request, int topN) {
        return rerank(request).stream().limit(topN).toList();
    }
}
```

---

## 6. 能力客户端抽象类

### 6.1 `AbstractChatClient`

```java
package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.llm.*;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatGenerationMetadata;
import org.springframework.ai.chat.model.Generation;
import java.util.List;
import java.util.Set;

/**
 * Chat 客户端抽象基类
 * <p>
 * 实现 {@link ChatCapable} 接口。子类只需实现 {@link #chat} 和 {@link #chatStream}，
 * 其他元信息方法由基类通过 {@link ModelCandidate} 统一处理。
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientChatClient} 装饰器在外部施加。
 * <p>
 * <b>Spring AI ChatModel 桥接义务</b>：{@code ChatCapable extends ChatModel}，
 * 因此子类还必须实现 {@code call(Prompt)} 和 {@code stream(Prompt)} 方法。
 * 推荐在子类中桥接到 {@link #chat(ChatRequest)} 和 {@link #chatStream(ChatRequest)}：
 * <pre>
 * public ChatResponse call(Prompt prompt) {
 *     return chat(ChatRequest.of(prompt.getContents()));
 * }
 * </pre>
 */
public abstract class AbstractChatClient implements ChatCapable {

    protected final ModelCandidate candidate;
    protected final String providerId;

    protected AbstractChatClient(ModelCandidate candidate, String providerId) {
        this.candidate = candidate;
        this.providerId = providerId;
    }

    @Override
    public final String candidateId() { return candidate.id(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelName() { return candidate.model(); }

    @Override
    public final LlmCapability capability() { return candidate.capability(); }

    @Override
    public final ModelCandidate candidate() { return candidate; }

    /**
     * 默认可用。子类可覆写为实际的 API key 检查或连通性探测。
     * ResilientChatClient 会在此基础上叠加熔断器状态判断。
     */
    @Override
    public boolean isAvailable() { return true; }

    /**
     * 阻塞式对话
     *
     * @param request 对话请求（包含 prompt、systemPrompt、history 等）
     * @return 统一响应
     */
    public abstract LlmResponse chat(ChatRequest request);

    /**
     * 流式对话
     *
     * @param request 对话请求
     * @return 文本片段流（SSE），由上游 SseStreamBridge 桥接为 SSEEmitter
     */
    public abstract Flux<String> chatStream(ChatRequest request);

    /**
     * 带工具调用的对话（Agent 场景）
     * <p>
     * 默认不支持，AgentModeStrategy 可覆写。
     *
     * @param request 对话请求
     * @param tools   工具定义列表
     * @return 统一响应（可能包含 toolCalls）
     */
    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        throw new UnsupportedOperationException(
            "Tool calling not supported by " + candidateId());
    }

    /**
     * 是否支持流式
     */
    public boolean supportsStreaming() {
        return candidate.supportsStreaming() != null && candidate.supportsStreaming();
    }

    // ======== Spring AI ChatModel 桥接（继承自 ChatCapable extends ChatModel）========

    /**
     * Spring AI ChatModel 桥接 — 将 {@code call(Prompt)} 委托给 {@link #chat(ChatRequest)}
     * <p>
     * {@code ChatCapable extends ChatModel}，因此子类天然就是 {@code ChatModel}。
     * 此默认实现从 {@code Prompt.getInstructions()} 提取 SystemMessage，
     * 从 {@code Prompt.getContents()} 提取用户输入，构建 {@code ChatRequest}。
     * <p>
     * <b>重要</b>：{@code ResilientChatClient} 会覆写此方法，确保通过弹性层（CircuitBreaker → RetryPolicy → delegate）。
     * 直接调用此方法（未经 Resilient 包装）会绕过弹性保护。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        LlmResponse llmResp = chat(request);
        return wrapAsChatResponse(llmResp);
    }

    /**
     * Spring AI ChatModel 桥接 — 将 {@code stream(Prompt)} 委托给 {@link #chatStream(ChatRequest)}
     * <p>
     * 流式场景下无法逐 chunk 获取 token usage（供应商不在每个 SSE 事件中返回 usage），
     * 因此 {@code ChatResponseMetadata.usage} 为空，token 统计由 {@code TokenCountingChatModel}
     * 在流结束后从 {@code LlmResponse} 的 {@code tokenUsage} 中提取。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        return chatStream(request)
            .map(chunk -> new ChatResponse(
                List.of(new Generation(new AssistantMessage(chunk)))));
    }

    /**
     * 将 SPI 的 {@code LlmResponse} 转换为 Spring AI 的 {@code ChatResponse}
     * <p>
     * 供 {@code call(Prompt)} 桥接使用。
     */
    protected ChatResponse wrapAsChatResponse(LlmResponse llmResp) {
        AssistantMessage assistantMsg = new AssistantMessage(
            llmResp.content() != null ? llmResp.content() : "");
        Generation generation = new Generation(assistantMsg,
            ChatGenerationMetadata.builder()
                .finishReason(llmResp.truncated() ? "length" : "stop")
                .build());

        ChatResponseMetadata.Builder metaBuilder = ChatResponseMetadata.builder();
        if (llmResp.tokenUsage() != null) {
            metaBuilder.usage(Usage.builder()
                .promptTokens(llmResp.tokenUsage().promptTokens())
                .completionTokens(llmResp.tokenUsage().completionTokens())
                .totalTokens(llmResp.tokenUsage().totalTokens())
                .build());
        }
        return new ChatResponse(List.of(generation), metaBuilder.build());
    }
    /**
     * 从 Spring AI {@code Prompt} 提取 {@code ChatRequest}，保留 SystemMessage。
     * <p>
     * {@code Prompt.getContents()} 仅返回用户消息文本，会丢失 SystemMessage。
     * 此方法从 {@code Prompt.getInstructions()} 中提取 SystemMessage。
     */
    protected ChatRequest extractChatRequest(Prompt prompt) {
        String systemPrompt = null;
        String userContent = prompt.getContents();
        if (prompt.getInstructions() != null) {
            for (var msg : prompt.getInstructions()) {
                if (msg instanceof org.springframework.ai.chat.messages.SystemMessage sm) {
                    systemPrompt = sm.getText();
                    break;
                }
            }
        }
        return systemPrompt != null
            ? ChatRequest.withSystem(systemPrompt, userContent)
            : ChatRequest.of(userContent);
    }

}
```

### 6.2 `AbstractEmbeddingClient`

```java
package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.llm.*;
import java.util.List;
import java.util.Set;

/**
 * Embedding 客户端抽象基类
 * <p>
 * 子类只需实现 {@link #embed} 和 {@link #dimensions}，
 * 批量嵌入的默认实现为逐条调用，子类可覆写为批量 API 以提升性能。
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientEmbeddingClient} 装饰器在外部施加。
 *
 * <pre>
 * 子类实现示例：
 * - GenericEmbeddingClient（OpenAI 兼容 Embedding API）
 * - BaiLianEmbeddingClient（百炼非标准 API，批量分片 + text_type 路由）
 * </pre>
 */
public abstract class AbstractEmbeddingClient implements EmbeddingCapable {

    protected final ModelCandidate candidate;
    protected final String providerId;

    protected AbstractEmbeddingClient(ModelCandidate candidate, String providerId) {
        this.candidate = candidate;
        this.providerId = providerId;
    }

    @Override
    public final String candidateId() { return candidate.id(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelName() { return candidate.model(); }

    @Override
    public final LlmCapability capability() { return candidate.capability(); }

    @Override
    public final ModelCandidate candidate() { return candidate; }

    @Override
    public boolean isAvailable() { return true; }

    /**
     * 单条文本向量嵌入
     *
     * @param text 待嵌入文本
     * @param type 嵌入类型（QUERY 或 DOCUMENT）
     * @return 向量浮点数组
     */
    public abstract float[] embed(String text, EmbeddingType type);

    /**
     * 批量文本向量嵌入
     * <p>
     * 默认实现为逐条调用 {@link #embed}。
     * 子类可覆写为批量 API 调用以减少网络往返。
     * <p>
     * <b>语义约束：fail-fast</b>。
     * 批量嵌入不返回部分结果——任意一条失败时，整个批次由 {@code ResilientEmbeddingClient}
     * 的重试策略从头重试（全部重新调用）。调用方拿到的要么是完整列表，要么是重试耗尽后的异常。
     * 注意：这并非原子性保证（已发出的 HTTP 请求无法回滚），而是"尽力全量重试"语义。
     *
     * @param texts 待嵌入文本列表
     * @param type  嵌入类型
     * @return 向量列表，与输入 texts 一一对应
     */
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        return texts.stream()
            .map(text -> embed(text, type))
            .toList();
    }

    /**
     * 向量维度
     * <p>
     * 用于入库时校验维度一致性。
     *
     * @return 该模型输出的向量维度
     */
    public abstract int dimensions();
}
```

### 6.3 `AbstractRerankClient`

```java
package com.smart.rag.infrastructure.llm.client;

import com.smart.rag.infrastructure.llm.*;
import java.util.List;
import java.util.Set;

/**
 * Rerank 客户端抽象基类
 * <p>
 * 子类只需实现 {@link #rerank(RerankRequest)}。
 * 带 topN 截断的重排序有默认实现。
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientRerankClient} 装饰器在外部施加。
 *
 * <pre>
 * 子类实现示例：
 * - GenericRerankClient（OpenAI 兼容 Rerank API）
 * - BaiLianRerankClient（百炼 /rerank 非标准端点）
 * </pre>
 */
public abstract class AbstractRerankClient implements RerankCapable {

    protected final ModelCandidate candidate;
    protected final String providerId;

    protected AbstractRerankClient(ModelCandidate candidate, String providerId) {
        this.candidate = candidate;
        this.providerId = providerId;
    }

    @Override
    public final String candidateId() { return candidate.id(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelName() { return candidate.model(); }

    @Override
    public final LlmCapability capability() { return candidate.capability(); }

    @Override
    public final ModelCandidate candidate() { return candidate; }

    @Override
    public boolean isAvailable() { return true; }

    /**
     * 重排序
     *
     * @param request 重排序请求（包含 query、候选文档列表）
     * @return 按相关性降序排列的结果
     */
    public abstract List<RerankResult> rerank(RerankRequest request);

    /**
     * 带 topN 截断的重排序
     * <p>
     * 默认实现为调用 {@link #rerank(RerankRequest)} 后截断。
     * 子类可覆写为服务端截断以减少网络传输。
     *
     * @param request 重排序请求
     * @param topN    返回的最大结果数
     * @return 截断后的结果
     */
    public List<RerankResult> rerank(RerankRequest request, int topN) {
        return rerank(request).stream()
            .limit(topN)
            .toList();
    }
}
```

---

## 7. 统一弹性层

### 7.1 设计原则

- **重试策略**：所有 LLM 操作共享全局默认参数配置（YAML 一处定义），按能力类型可选覆盖（`retry-overrides`）
- **熔断保护**：按 `candidateId` 粒度独立熔断（同一个供应商的 chat 和 embedding 互不影响）
- **装饰器模式**：ResilientClient 包装原始 Client，透明施加弹性行为
- **类型安全**：三个 Resilient 装饰器，分别对应三种能力类型，编译期检查

### 7.2 `RetryPolicy` — 统一重试策略

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitOpenException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 统一重试策略
 * <p>
 * 所有 LLM 操作共用同一套重试参数，由 {@code app.llm.resilience.retry} 配置。
 * 替换各调用点散落的 for-loop / 自写指数退避。
 * <p>
 * 可重试判定：直接复用已有 {@link FallbackEligibility}。
 * 可降级 = 可重试（C 类 RemoteException + 网络异常），不可降级 = 不可重试（A/B 类 + 编程异常）。
 * 与现有 {@code StreamRetryHandler} 的判定逻辑完全一致，无额外配置。
 * <p>
 * 配置示例：
 * <pre>
 * app:
 *   llm:
 *     resilience:
 *       retry:
 *         maxAttempts: 3
 *         baseDelayMs: 500
 *         maxDelayMs: 5000
 *         multiplier: 2.0
 * </pre>
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double multiplier;
    private final FallbackEligibility fallbackEligibility;
    public RetryPolicy(RetryProperties properties, FallbackEligibility fallbackEligibility) {
        this.maxAttempts = properties.effectiveMaxAttempts();
        this.baseDelayMs = properties.effectiveBaseDelayMs();
        this.maxDelayMs = properties.effectiveMaxDelayMs();
        this.multiplier = properties.effectiveMultiplier();
        this.fallbackEligibility = fallbackEligibility;
    }

    /**
     * 受检异常兼容的函数式接口
     * <p>
     * {@link java.util.function.Supplier} 不允许抛出 checked exception，
     * 而 LLM 调用可能抛出 {@link java.io.IOException} 等受检异常。
     * 此接口替代 Supplier 作为重试执行器的参数类型。
     */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    /**
     * 带指数退避的同步重试执行器
     *
     * @param action 可重试的操作（允许抛出 checked exception）
     * @return 操作结果
     * @throws Exception 重试耗尽后抛出最后一个异常
     */
    public <T> T executeWithBackoff(CheckedSupplier<T> action) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                if (!isRetryable(e) || attempt == maxAttempts - 1) {
                    throw e;
                }
                lastException = e;
                long delay = Math.min(
                    baseDelayMs * (long) Math.pow(multiplier, attempt),
                    maxDelayMs
                );
                Thread.sleep(delay);
            }
        }
        throw lastException; // unreachable but satisfies compiler
    }

    /**
     * 带指数退避的异步重试执行器（流式路径使用）
     * <p>
     * 使用 Flux.retryWhen() 实现流式重试：
     * <ul>
     *   <li>对 Flux 整体重试（不转换为 Mono，保留流式语义）</li>
     *   <li>重试间隔由指数退避控制</li>
     *   <li>仅对可重试异常触发重试，不可重试异常直接向下游传播</li>
     *   <li>已有数据发送给下游后不再重试（避免内容重复），异常直接传播给
     *       {@link FallbackExecutor} 做跨模型降级</li>
     * </ul>
     * <p>
     * <b>emitted 追踪</b>：内部创建 {@code AtomicBoolean}，通过 {@code doOnNext} 自动标记。
     * 调用方无需手动管理 emitted 状态——一旦有数据发送给下游，重试自动停止。
     *
     * @param streamSupplier 返回 Flux 的可重试操作
     * @return 带重试语义的 Flux
     */
    public <T> Flux<T> retryStream(Supplier<Flux<T>> streamSupplier) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        return Flux.defer(streamSupplier)
            .doOnNext(__ -> emitted.set(true))
            .retryWhen(Retry.backoff(maxAttempts, Duration.ofMillis(baseDelayMs))
                .maxBackoff(Duration.ofMillis(maxDelayMs))
                .filter(e -> isRetryable(e) && !emitted.get())
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    /**
     * 空操作重试（不需要重试的场景直接透传）
     */
    public <T> T executeDirect(CheckedSupplier<T> action) throws Exception {
        return action.get();
    }

    /**
     * 判断异常是否可重试
     * <p>
     * 两层过滤：
     * <ol>
     *   <li>{@link ModelCircuitOpenException} — 熔断器已 OPEN，重试无意义，直接传播给
     *       {@link FallbackExecutor} 做跨模型降级（与现有 {@code StreamRetryHandler} 行为一致）</li>
     *   <li>{@link FallbackEligibility#isEligible} — 可降级 = 可重试，不可降级 = 不可重试</li>
     * </ol>
     */
    private boolean isRetryable(Throwable e) {
        if (e instanceof ModelCircuitOpenException) {
            return false;
        }
        return fallbackEligibility.isEligible(e);
    }

    /**
     * 判断异常是否可触发跨模型降级（语义上等同于 isRetryable）
     * <p>
     * 保留此方法以保持 {@link FallbackExecutor} 调用语义清晰。
     */
    public boolean isFallbackEligible(Throwable e) {
        return fallbackEligibility.isEligible(e);
    }

}
```

### 7.3 `FallbackExecutor` — 跨模型降级执行器

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.FallbackEligibility;

import java.util.List;
import java.util.function.Function;

/**
 * 跨模型 Fallback 降级执行器
 * <p>
 * 按 Fallback Chain 顺序尝试，单个客户端失败后自动降级到下一个。
 * 集成已有的 {@link FallbackEligibility} 过滤用户错误（不可降级的异常直接终止）。
 * <p>
 * <b>调用层次</b>：
 * <pre>
 *   FallbackExecutor.execute(chain, client -> client.chat(request))
 *     │
 *     ├─ client = ResilientChatClient（已包装，含重试+熔断）
 *     │    └─ ResilientChatClient.chat() → circuitBreaker → retryPolicy → delegate.chat()
 *     │
 *     └─ client 失败后 → FallbackExecutor 尝试 chain 中下一个 ResilientChatClient
 * </pre>
 * <b>关键约束</b>：传入 {@code action} 的 client 必须是已包装 Resilience 的客户端，
 * 否则 FallbackExecutor 只做跨模型降级，不提供单模型重试和熔断保护。
 * Registry 返回的 Fallback Chain 已包含 Resilient 包装。
 */
public class FallbackExecutor {

    private static final Logger log = LoggerFactory.getLogger(FallbackExecutor.class);

    private final FallbackEligibility fallbackEligibility;

    public FallbackExecutor(FallbackEligibility fallbackEligibility) {
        this.fallbackEligibility = fallbackEligibility;
    }

    /**
     * 执行 Fallback Chain（阻塞式）
     *
     * @param chain  按优先级排序的客户端列表
     * @param action 对单个客户端执行的操作
     * @return 第一个成功的结果
     * @throws RemoteException 所有客户端都失败时抛出（RemoteErrorCode.LLM_ALL_MODELS_FAILED）
     */
    public <T extends CapabilityClient, R> R execute(
            List<T> chain,
            Function<T, R> action) {

        Exception lastException = null;
        for (T client : chain) {
            if (!client.isAvailable()) {
                continue;
            }
            try {
                return action.apply(client);
            } catch (Exception e) {
                lastException = e;
                if (!fallbackEligibility.isEligible(e)) {
                    // 用户错误（ContentFilteredException 等）不降级，直接抛出
                    throw e;
                }
                log.warn("Client '{}' failed, trying next: {}",
                    client.candidateId(), e.getMessage());
            }
        }
        throw new RemoteException(RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用", lastException);
    }

    /**
     * 执行 Fallback Chain（流式）
     * <p>
     * 语义：按 chain 顺序订阅 Flux，当前 client 的流报错时自动切换到下一个。
     * <p>
     * <b>调用层次</b>：
     * <pre>
     *   FallbackExecutor.executeStream(chain, c -> c.chatStream(req))
     *     │
     *     ├─ 订阅 client_0 的 Flux（已包装 Resilient：circuitBreaker → retryStream → probeHandler）
     *     │    ├─ 流正常 → 直接输出，不尝试下一个
     *     │    └─ 流报错（重试+熔断均耗尽）→ 切换到 client_1 的 Flux
     *     │
     *     └─ 所有 client 均报错 → Flux.error(RemoteException)
     * </pre>
     * <b>注意</b>：切换发生在 Flux 信号层面（onErrorResume），
     * 意味着已发送给下游的数据片段不会回滚。
     * 流式降级的效果是"从头开始用新模型重新生成"，而非"续接上一个模型的输出"。
     *
     * @param chain  按优先级排序的客户端列表
     * @param action 对单个客户端执行的操作，返回 Flux
     * @return 带降级语义的 Flux
     */
    public <T extends CapabilityClient> Flux<String> executeStream(
            List<T> chain,
            Function<T, Flux<String>> action) {

        // 预过滤不可用客户端，消除装配期急迫递归
        List<T> available = chain.stream()
            .filter(CapabilityClient::isAvailable)
            .toList();
        if (available.isEmpty()) {
            return Flux.error(new RemoteException(
                RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用"));
        }
        return buildStreamChain(available, action, 0);
    }

    private <T extends CapabilityClient> Flux<String> buildStreamChain(
            List<T> chain,
            Function<T, Flux<String>> action,
            int index) {

        if (index >= chain.size()) {
            return Flux.error(new RemoteException(RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用（流式降级链耗尽）"));
        }

        T client = chain.get(index);

        // isAvailable 已在 executeStream 入口预过滤，此处无需再检查
        return action.apply(client)
            .onErrorResume(e -> {
                // 用户错误不降级，直接向下游传播
                if (!fallbackEligibility.isEligible(e)) {
                    return Flux.error(e);
                }
                log.warn("Stream client '{}' failed at index {}, falling back to next: {}",
                    client.candidateId(), index, e.getMessage());
                // 惰性递归：仅在运行时错误触发，每次只深入一层，不积累栈帧
                return buildStreamChain(chain, action, index + 1);
            });
    }
}
```


### 7.4 `CircuitBreaker` — 熔断器适配器（包装已有基础设施）

> **设计决策**：不重写三态熔断器，而是**包装已有的 `ModelCircuitBreakerRegistry`**。
> 已有实现已包含完整状态机（synchronized + Clock 注入 + halfOpenMaxProbes + releaseProbe），
> 新增 `execute()` / `executeStream()` 高层方法供 Resilient 装饰器使用。

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.ModelCircuitOpenException;
import com.smart.rag.infrastructure.fallback.ProbeTimeoutException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 熔断器适配器 — 包装已有的 {@link ModelCircuitBreakerRegistry}
 * <p>
 * 复用已有三态熔断器实现（CLOSED → OPEN → HALF_OPEN），
 * 新增 {@code execute()} / {@code executeStream()} 高层方法，
 * 供 Resilient 装饰器统一调用。
 * <p>
 * <b>异常过滤</b>：仅将基础设施异常（{@link FallbackEligibility#isEligible} 返回 true）
 * 计为熔断失败。用户错误（ContentFilteredException 等）不触发熔断计数，
 * 避免因客户端问题误开熔断器。
 * <p>
 * <b>流超时语义</b>：{@code executeStream()} 仅检测首包超时（与已有 {@code ProbeStreamHandler} 一致），
 * 首包到达后不再限制流的总时长。全流超时由客户端断开连接自然触发。
 */
public class CircuitBreaker {

    private final ModelCircuitBreakerRegistry registry;
    private final FallbackEligibility fallbackEligibility;
    private final String candidateId;

    public CircuitBreaker(ModelCircuitBreakerRegistry registry,
                          FallbackEligibility fallbackEligibility,
                          String candidateId) {
        this.registry = registry;
        this.fallbackEligibility = fallbackEligibility;
        this.candidateId = candidateId;
    }

    /**
     * 阻塞式执行（带熔断保护）
     * <p>
     * OPEN → 抛出 {@link ModelCircuitOpenException}（已有异常类型）
     * HALF_OPEN → 放行（由已有 halfOpenMaxProbes 控制并发数）
     */
    public <T> T execute(RetryPolicy.CheckedSupplier<T> action) throws Exception {
        if (!registry.isCallAllowed(candidateId)) {
            throw new ModelCircuitOpenException(candidateId);
        }
        try {
            T result = action.get();
            registry.recordSuccess(candidateId);
            return result;
        } catch (Exception e) {
            // 仅基础设施异常计为熔断失败
            if (fallbackEligibility.isEligible(e)) {
                registry.recordFailure(candidateId);
            }
            throw e;
        }
    }

    /**
     * 流式执行（带熔断保护）
     * <p>
     * 订阅时检查状态，流结束时记录成功，异常时按可降级性记录失败。
     * HALF_OPEN 下的探测流由已有 {@code releaseProbe()} 管理。
     * <p>
     * <b>首包超时</b>：由 {@link ProbeHandler} 在 {@code ResilientChatClient} 中施加，
     * 本方法不做首包超时检测（避免双层超时冲突）。ProbeHandler 超时抛出
     * {@link ProbeTimeoutException}，由外层 {@link RetryPolicy#retryStream} 识别为可重试异常。
     */
    public <T> Flux<T> executeStream(Supplier<Flux<T>> streamSupplier) {
        if (!registry.isCallAllowed(candidateId)) {
            return Flux.error(new ModelCircuitOpenException(candidateId));
        }
        return Flux.defer(streamSupplier)
            .doOnComplete(() -> {
                registry.recordSuccess(candidateId);
                registry.releaseProbe(candidateId);
            })
            .doOnError(e -> {
                // 仅基础设施异常计为熔断失败。
                // 排除 ProbeTimeoutException：ProbeStreamHandler 已调用 breakers.recordFailure。
                if (!(e instanceof ProbeTimeoutException) && isRetryable(e)) {
                    registry.recordFailure(candidateId);
                }
                registry.releaseProbe(candidateId);
            })
            .doOnCancel(() -> {
                // 客户端主动断开不算失败
                registry.releaseProbe(candidateId);
            });
    }

    /**
     * 判断异常是否为基础设施异常（可触发熔断计数）
     * <p>
     * 排除 {@link ProbeTimeoutException}（已由 ProbeStreamHandler 处理）。
     */
    private boolean isRetryable(Throwable e) {
        if (e instanceof ProbeTimeoutException) {
            return false;
        }
        return fallbackEligibility.isEligible(e);
    }

    /** 当前状态（委托给已有实现） */
    public CircuitBreakerState getState() {
        return registry.stateOf(candidateId);
    }
}
```

### 7.5 `LlmCircuitBreakerAdapterRegistry` — 熔断器注册表（包装已有）

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;

/**
 * 按 candidateId 管理的熔断器注册表 — 包装已有 {@link ModelCircuitBreakerRegistry}
 * <p>
 * 不创建新的熔断器实例，而是为每个 candidateId 创建 {@link CircuitBreaker} 适配器，
 * 底层委托给已有的 {@link ModelCircuitBreakerRegistry}。
@Component
public class LlmCircuitBreakerAdapterRegistry {

    private final ModelCircuitBreakerRegistry delegate;
    private final FallbackEligibility fallbackEligibility;
    private final ConcurrentHashMap<String, CircuitBreaker> adapters = new ConcurrentHashMap<>();

    public LlmCircuitBreakerAdapterRegistry(ModelCircuitBreakerRegistry delegate,
                                            FallbackEligibility fallbackEligibility) {
        this.delegate = delegate;
        this.fallbackEligibility = fallbackEligibility;
    }

    /**
     * 获取或创建指定 candidateId 的熔断器适配器
     */
    public CircuitBreaker getOrCreate(String candidateId) {
        return adapters.computeIfAbsent(candidateId,
            id -> new CircuitBreaker(delegate, fallbackEligibility, id));
    }
}
```


### 7.6 `ProbeHandler` — 首包探测处理器（包装已有基础设施）

> **设计决策**：不重写首包探测，**包装已有的 `ProbeStreamHandler`**。
> 已有实现使用 `Flux.create` + `AtomicBoolean` + 手动 timer 精确控制首包超时，
> 并在超时时调用 `breakers.recordFailure()` 更新熔断计数。
> 新架构复用此实现，额外集成 `SharedProbeRegistry` 探测去重。

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.ProbeStreamHandler;
import com.smart.rag.infrastructure.fallback.probe.SharedProbeRegistry;

/**
 * 首包探测处理器 — 包装已有的 {@link ProbeStreamHandler}
 * <p>
 * 复用已有的首包超时检测（Flux.create + timer + breakers.recordFailure），
 * 额外集成 {@link SharedProbeRegistry} 探测去重：
 * <ul>
 *   <li>同一 candidateId 的并发探测共享同一个探测结果，避免重复探测</li>
 * <p>
 * 降级语义：
 * <ul>
 *   <li>首包超时 → 已有 ProbeStreamHandler 调用 breakers.recordFailure() + 抛出 ProbeTimeoutException</li>
 *   <li>ProbeTimeoutException 被 {@link RetryPolicy#retryStream} 识别为可重试异常</li>
 *   <li>重试耗尽 → 异常冒泡到 {@link CircuitBreaker#executeStream} 的 doOnError → recordFailure</li>
 *   <li>由 {@link FallbackExecutor#executeStream} 降级到下一个模型</li>
 * </ul>
 */
public class ProbeHandler {

    private final ProbeStreamHandler delegate;
    @Nullable
    private final SharedProbeRegistry probeRegistry;
    private final long probeTimeoutMs;

    public ProbeHandler(ProbeStreamHandler delegate,
                        @Nullable SharedProbeRegistry probeRegistry,
                        long probeTimeoutMs) {
        this.delegate = delegate;
        this.probeRegistry = probeRegistry;
        this.probeTimeoutMs = probeTimeoutMs;
    }

    /**
     * 包装 Flux，添加首包超时检测 + 探测去重
     * <p>
     * 探测去重语义：
     * <ul>
     *   <li>已有探测成功 → 跳过探测，直接发流（模型已验证可用，无需重复探测）</li>
     *   <li>已有探测失败 → 跳过探测，直接发流（探测失败可能是并发条件导致，
     *       当前请求让下游 retryStream 处理，而非立即报错）</li>
     *   <li>无在飞探测 → 正常委托给 ProbeStreamHandler 进行首包探测</li>
     * </ul>
     *
     * @param candidateId 用于日志、熔断记录、探测去重 key
     * @param raw         原始流式响应
     * @return 带首包探测的 Flux
     */
    public Flux<String> wrap(String candidateId, Flux<String> raw) {
        // 探测去重：如果已有同模型的探测在飞，等待其结果后跳过探测
        if (probeRegistry != null) {
            CompletableFuture<ProbeResult> inFlight = probeRegistry.getInFlight(candidateId);
            if (inFlight != null) {
                // 无论已有探测成功还是失败，都直接发流。
                // 成功说明模型可用，失败可能是并发条件导致，让 retryStream 处理。
                return Mono.fromFuture(() -> inFlight)
                    .timeout(Duration.ofMillis(probeTimeoutMs))
                    .onErrorResume(e -> Mono.empty()) // 探测等待超时也继续发流
                    .thenMany(raw);
            }
        }
        // 委托给已有的 ProbeStreamHandler（内部已集成 breakers.recordFailure）
        return delegate.wrapWithProbe(candidateId, raw);
    }
}
```



### 7.7 Resilient 装饰器

> **设计决策**：装饰器**只实现能力接口**（`ChatCapable` / `EmbeddingCapable` / `RerankCapable`），
> **不继承**被装饰者的抽象类。纯组合 + 委托，避免继承+委托的双重关系。
> 调用方通过接口交互，无法区分原始客户端和弹性包装——这正是装饰器的透明性。

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.*;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;

/**
 * ResilientChatClient — Chat 能力的弹性装饰器
 * <p>
 * 策略矩阵：
 * <pre>
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ chat         │ 指数退避重试   │ ✓            │ ✗（阻塞式）   │
 * │ chatStream   │ 指数退避重试   │ ✓            │ ✓ ProbeHandler│
 * │ chatWithTools│ 指数退避重试   │ ✓            │ ✗            │
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 * </pre>
 */
public class ResilientChatClient implements ChatCapable {

    private final ChatCapable delegate;
    private final CircuitBreaker circuitBreaker;
    private final RetryPolicy retryPolicy;
    private final ProbeHandler probeHandler;

    public ResilientChatClient(ChatCapable delegate,
                                CircuitBreaker circuitBreaker,
                                RetryPolicy retryPolicy,
                                ProbeHandler probeHandler) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retryPolicy = retryPolicy;
        this.probeHandler = probeHandler;
    }

    // ======== CapabilityClient 委托 ========
    @Override public String candidateId() { return delegate.candidateId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelName() { return delegate.modelName(); }
    @Override public LlmCapability capability() { return delegate.capability(); }
    @Override public boolean isAvailable() {
        return delegate.isAvailable()
            && circuitBreaker.getState() != CircuitBreakerState.OPEN;
    }
    @Override public ModelCandidate candidate() { return delegate.candidate(); }

    // ======== Chat 操作（带弹性包装） ========

    @Override
    public LlmResponse chat(ChatRequest request) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.chat(request)
            )
        );
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        // emitted 追踪由 retryStream 内部管理：一旦有数据发送给下游，重试自动停止，
        // 异常直接传播给 FallbackExecutor 做跨模型降级。
        return circuitBreaker.executeStream(() ->
            retryPolicy.retryStream(() -> {
                Flux<String> raw = delegate.chatStream(request);
                return probeHandler != null
                    ? probeHandler.wrap(candidateId(), raw)
                    : raw;
            })
        );
    }

    @Override
    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.chatWithTools(request, tools)
            )
        );
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }


    // ======== Spring AI ChatModel 桥接（通过弹性层）========
    /**
     * Spring AI ChatModel 桥接 — 通过弹性层委托给 delegate
     * <p>
     * {@code AgentModeStrategy} 等 Spring AI 组件通过 {@code ChatModel.call(Prompt)} 调用，
     * 此覆写确保调用经过 CircuitBreaker → RetryPolicy → delegate 完整弹性链。
     * <p>
     * <b>Prompt 提取</b>：从 {@code Prompt.getInstructions()} 提取 SystemMessage 和用户消息，
     * 从 {@code Prompt.getContents()} 提取用户输入文本，确保 SystemMessage 不丢失。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        LlmResponse llmResp = circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() -> delegate.chat(request)));
        // 内联 LlmResponse → ChatResponse 转换（与 AbstractChatClient.wrapAsChatResponse 逻辑一致）
        AssistantMessage assistantMsg = new AssistantMessage(
            llmResp.content() != null ? llmResp.content() : "");
        Generation generation = new Generation(assistantMsg,
            ChatGenerationMetadata.builder()
                .finishReason(llmResp.truncated() ? "length" : "stop")
                .build());
        ChatResponseMetadata.Builder metaBuilder = ChatResponseMetadata.builder();
        if (llmResp.tokenUsage() != null) {
            metaBuilder.usage(Usage.builder()
                .promptTokens(llmResp.tokenUsage().promptTokens())
                .completionTokens(llmResp.tokenUsage().completionTokens())
                .totalTokens(llmResp.tokenUsage().totalTokens())
                .build());
        }
        return new ChatResponse(List.of(generation), metaBuilder.build());
    }

    /**
     * Spring AI ChatModel 桥接 — 通过弹性层委托给 delegate（流式）
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        return circuitBreaker.executeStream(() ->
            retryPolicy.retryStream(() -> {
                Flux<String> raw = delegate.chatStream(request);
                return probeHandler != null
                    ? probeHandler.wrap(candidateId(), raw)
                    : raw;
            })
        ).map(chunk -> new ChatResponse(
            List.of(new Generation(new AssistantMessage(chunk)))));
    }

    /**
     * 从 Spring AI {@code Prompt} 提取 {@code ChatRequest}，保留 SystemMessage 和对话历史。
     * <p>
     * {@code Prompt.getContents()} 仅返回用户消息文本，会丢失 SystemMessage。
     * 此方法从 {@code Prompt.getInstructions()} 中提取完整消息列表。
     */
    private ChatRequest extractChatRequest(Prompt prompt) {
        String systemPrompt = null;
        String userContent = prompt.getContents();
        // 从 instructions 中提取 SystemMessage
        if (prompt.getInstructions() != null) {
            for (var msg : prompt.getInstructions()) {
                if (msg instanceof org.springframework.ai.chat.messages.SystemMessage sm) {
                    systemPrompt = sm.getText();
                    break;
                }
            }
        }
        return systemPrompt != null
            ? ChatRequest.withSystem(systemPrompt, userContent)
            : ChatRequest.of(userContent);
    }
}
```

```java
/**
 * ResilientEmbeddingClient — Embedding 能力的弹性装饰器
 *
 * 策略矩阵：
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ embed        │ 指数退避重试   │ ✓            │ ✗            │
 * │ embedBatch   │ 指数退避重试   │ ✓            │ ✗            │
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 */
public class ResilientEmbeddingClient implements EmbeddingCapable {

    private final EmbeddingCapable delegate;
    private final CircuitBreaker circuitBreaker;
    private final RetryPolicy retryPolicy;

    public ResilientEmbeddingClient(EmbeddingCapable delegate,
                                      CircuitBreaker circuitBreaker,
                                      RetryPolicy retryPolicy) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retryPolicy = retryPolicy;
    }

    // ======== CapabilityClient 委托 ========
    @Override public String candidateId() { return delegate.candidateId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelName() { return delegate.modelName(); }
    @Override public LlmCapability capability() { return delegate.capability(); }
    @Override public boolean isAvailable() {
        return delegate.isAvailable()
            && circuitBreaker.getState() != CircuitBreakerState.OPEN;
    }
    @Override public ModelCandidate candidate() { return delegate.candidate(); }

    // ======== Embedding 操作（带弹性包装） ========

    @Override
    public float[] embed(String text, EmbeddingType type) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.embed(text, type)
            )
        );
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.embedBatch(texts, type)
            )
        );
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
```

```java
/**
 * ResilientRerankClient — Rerank 能力的弹性装饰器
 *
 * 策略矩阵：
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ rerank       │ 指数退避重试   │ ✓            │ ✗            │
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 */
public class ResilientRerankClient implements RerankCapable {

    private final RerankCapable delegate;
    private final CircuitBreaker circuitBreaker;
    private final RetryPolicy retryPolicy;

    public ResilientRerankClient(RerankCapable delegate,
                                   CircuitBreaker circuitBreaker,
                                   RetryPolicy retryPolicy) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retryPolicy = retryPolicy;
    }

    // ======== CapabilityClient 委托 ========
    @Override public String candidateId() { return delegate.candidateId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelName() { return delegate.modelName(); }
    @Override public LlmCapability capability() { return delegate.capability(); }
    @Override public boolean isAvailable() {
        return delegate.isAvailable()
            && circuitBreaker.getState() != CircuitBreakerState.OPEN;
    }
    @Override public ModelCandidate candidate() { return delegate.candidate(); }

    // ======== Rerank 操作（带弹性包装） ========

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.rerank(request)
            )
        );
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request, int topN) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.rerank(request, topN)
            )
        );
    }
}
```

---

## 8. Provider 实现

### 8.1 通用 OpenAI 兼容 Provider（零代码配置）

```java
package com.smart.rag.infrastructure.llm.provider.generic;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.*;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;

/**
 * 通用 OpenAI 兼容 Provider — 轻量工厂
 * <p>
 * 只关心"怎么连"和"怎么创建客户端"，不持有模型列表。
 * 模型配置独立管理（{@code ModelGroup} + {@code ModelCandidate}），
 * 通过 {@code candidate.provider()} 引用此 Provider。
 * <p>
 * 按 {@code candidate.capability()} 选择对应 endpoint，构建 HTTP 客户端。
 * 特殊 Client（如 {@code BaiLianEmbeddingClient}）在 endpoint 匹配时创建。
 */
public class GenericOpenAiProvider implements LlmProvider {

    private final ProviderConfig config;

    public GenericOpenAiProvider(ProviderConfig config) {
        this.config = config;
    }

    @Override public String id() { return config.id(); }
    @Override public ProviderConfig config() { return config; }

    @Override
    public CapabilityClient createClient(ModelCandidate candidate) {
        String endpoint = switch (candidate.capability()) {
            case CHAT -> config.endpoints().chat();
            case EMBEDDING -> config.endpoints().embedding();
            case RERANKING -> config.endpoints().rerank();
        };
        if (endpoint == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Provider '" + id() + "' missing endpoint for " + candidate.capability());
        }

        // 特殊 Client：百炼 Embedding 有非标准 API（批量分片、text_type 路由）
        if (candidate.capability() == LlmCapability.EMBEDDING
                && endpoint.contains("/services/embeddings/")) {
            return new BaiLianEmbeddingClient(config.url(), endpoint, config.apiKey(), candidate);
        }

        return switch (candidate.capability()) {
            case CHAT -> new GenericChatClient(config.url(), endpoint, config.apiKey(), candidate);
            case EMBEDDING -> new GenericEmbeddingClient(config.url(), endpoint, config.apiKey(), candidate);
            case RERANKING -> new GenericRerankClient(config.url(), endpoint, config.apiKey(), candidate);
        };
    }
}
```

**多供应商注册器**（为没有对应 `@Component` Bean 的 YAML 条目创建 `GenericOpenAiProvider`）：

```java
package com.smart.rag.infrastructure.llm.provider.generic;

import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.Configuration;

/**
 * 通用 Provider 注册器
 * <p>
 * 扫描 YAML 中 {@code app.llm.providers} 的所有条目，
 * 为没有对应 {@code @Component} {@link LlmProvider} Bean 的 id
 * 创建 {@link GenericOpenAiProvider} 并注册为独立 Bean。
 */
@Configuration
public class GenericOpenAiProviderRegistrar implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (ProviderConfig config : loadGenericConfigs()) {
            GenericBeanDefinition bd = new GenericBeanDefinition();
            bd.setBeanClass(GenericOpenAiProvider.class);
            bd.getConstructorArgumentValues().addIndexedArgumentValue(0, config);
            registry.registerBeanDefinition("llmProvider_" + config.id(), bd);
        }
    }

    private List<ProviderConfig> loadGenericConfigs() {
        // 从 Environment 读取 app.llm.providers（Map<String, ProviderConfig>）
        // 过滤：排除有对应 @Component LlmProvider Bean 的 id
        ...
    }
}
```


### 8.2 特殊 Client：BaiLianEmbeddingClient

> **设计决策**：百炼 Embedding API 有非标准逻辑（批量分片、text_type 路由、零向量缓存），
> 封装为独立的 `BaiLianEmbeddingClient`，由 `GenericOpenAiProvider` 在 endpoint 匹配时自动创建。
> 不需要独立的百炼 Provider。

```java
package com.smart.rag.infrastructure.llm.provider.bailian;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractEmbeddingClient;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;

/**
 * 百炼 Embedding 客户端 — 封装非标准 API 逻辑
 * <p>
 * 从已有 {@code DashScopeEmbeddingModel} 迁移而来。
 * 批量分片、text_type 路由、零向量缓存等特殊逻辑全部保留。
 * 重试/熔断由外层 {@code ResilientEmbeddingClient} 统一处理。
 * <p>
 * 由 {@code GenericOpenAiProvider.createClient()} 在 endpoint 匹配时自动创建，
 * 不需要独立的 Provider 类。
 */
public class BaiLianEmbeddingClient extends AbstractEmbeddingClient {

    private final String url;
    private final String endpoint;
    private final String apiKey;
    private final RestClient restClient;

    public BaiLianEmbeddingClient(String url, String endpoint, String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.url = url;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
            .baseUrl(url)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }

    @Override
    public float[] embed(String text, EmbeddingType type) {
        // 从已有 DashScopeEmbeddingModel.embedWithTextType() 迁移
        // text_type 路由：QUERY → "query"，DOCUMENT → "document"
        ...
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        // 从已有 DashScopeEmbeddingModel 批量分片逻辑迁移
        // 百炼 API 单次最多 25 条，超过自动分片
        ...
    }

    @Override
    public int dimensions() {
        return candidate().dimension() != null ? candidate().dimension() : 1536;
    }

    /** 供 PgVectorStore 注入（向后兼容） */
    public EmbeddingModel asEmbeddingModel() {
        // 返回适配器，桥接 EmbeddingCapable → EmbeddingModel
        ...
    }
}
```


### 8.3 特殊 Client：BaiLianRerankClient

```java
package com.smart.rag.infrastructure.llm.provider.bailian;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractRerankClient;

/**
 * 百炼 Rerank 客户端 — 调用 /api/v1/services/rerank 非标准端点
 * <p>
 * 从已有 {@code BailianRerankPostProcessor} 迁移而来，
 * REST 调用逻辑复用，重试/熔断由 {@code ResilientRerankClient} 统一处理。
 * 由 {@code GenericOpenAiProvider.createClient()} 在 endpoint 匹配时自动创建。
 */
public class BaiLianRerankClient extends AbstractRerankClient {

    private final RestClient restClient;

    public BaiLianRerankClient(String url, String endpoint, String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.restClient = RestClient.builder()
            .baseUrl(url)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        // 调用百炼 rerank API（与已有 BailianRerankPostProcessor 逻辑一致）
        ...
    }
}
```
## 9. Registry — 统一发现 + 注册 + 装饰

```java
package com.smart.rag.infrastructure.llm.registry;

/**
 * LLM 客户端注册表
 * <p>
 * 启动时遍历 {@code LlmProperties} 中的 {@code ModelGroup}，
 * 按 {@code candidate.provider} 查找 {@code LlmProvider}，
 * 通过 Provider 创建原始客户端，统一包装 Resilient 装饰器。
 * <p>
 * <b>核心变化</b>：不再遍历 Provider 收集 Client，而是遍历 ModelGroup 的 candidates。
 * 供应商与模型解耦——Provider 只是工厂，ModelGroup 决定"用哪些模型"。
 * <p>
 * 注册表示例：
 * <pre>
 * candidateId        │ provider  │ Client 类型              │ Capability
 * ───────────────────┼───────────┼──────────────────────────┼──────────────
 * qwen-plus          │ bailian   │ GenericChatClient        │ CHAT
 * deepseek-v4-flash  │ deepseek  │ GenericChatClient        │ CHAT
 * qwen3-local        │ ollama    │ GenericChatClient        │ CHAT
 * qwen3-max          │ bailian   │ GenericChatClient        │ CHAT
 * qwen-emb-8b        │ bailian   │ BaiLianEmbeddingClient   │ EMBEDDING
 * qwen3-rerank       │ bailian   │ BaiLianRerankClient      │ RERANKING
 * </pre>
 */
@Component
public class LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    /** 按能力的默认客户端（default-model → ResilientClient） */
    private final Map<LlmCapability, CapabilityClient> defaultClients = new EnumMap<>(LlmCapability.class);
    /** 深度思考模型（chat 组专用，可为 null） */
    private final CapabilityClient deepThinkingClient;
    /** candidateId → ResilientClient 索引 */
    private final Map<String, CapabilityClient> clientsById = new LinkedHashMap<>();
    /** capability → 按 priority 排序的 ResilientClient 列表（即 Fallback Chain） */
    private final Map<LlmCapability, List<CapabilityClient>> fallbackChains = new EnumMap<>(LlmCapability.class);
    /** 异常可降级判定（注入 RetryPolicy / FallbackExecutor 共用） */
    private final FallbackEligibility fallbackEligibility;

    public LlmClientRegistry(
            LlmProperties properties,
            List<LlmProvider> providers,
            LlmCircuitBreakerAdapterRegistry circuitBreakers,
            ProbeHandler probeHandler,
            FallbackEligibility fallbackEligibility) {

        // 1. 构建 provider id → LlmProvider 索引
        Map<String, LlmProvider> providerMap = providers.stream()
            .collect(Collectors.toMap(LlmProvider::id, p -> p));

        // 2. 引用校验（fail-fast）
        validateCandidateReferences(properties, providerMap.keySet());

        // 3. 遍历每个能力组的 candidates
        for (LlmCapability cap : LlmCapability.values()) {
            ModelGroup group = properties.modelGroup(cap);
            if (group == null) continue;

            List<CapabilityClient> chain = new ArrayList<>();
            for (ModelCandidate candidate : group.orderedCandidates()) {
                LlmProvider provider = providerMap.get(candidate.provider());
                if (!provider.config().isAvailable()) {
                    log.warn("Provider '{}' unavailable, skipping candidate '{}'",
                        candidate.provider(), candidate.id());
                    continue;
                }

                // 通过 Provider 创建原始 Client
                CapabilityClient raw = provider.createClient(candidate);

                // 包装 Resilient 装饰器
                CapabilityClient wrapped = wrapWithResilience(
                    raw, cap, properties.resilience(), circuitBreakers, probeHandler);

                clientsById.put(candidate.id(), wrapped);
                chain.add(wrapped);
            }
            fallbackChains.put(cap, Collections.unmodifiableList(chain));
        }

        // 4. 设置 default-model 和 deep-thinking-model
        resolveDefaultModels(properties);
        this.deepThinkingClient = resolveDeepThinkingModel(properties);
    }

    // ====== 调用方 API ======

    /** 获取指定能力的默认客户端 */
    public <T extends CapabilityClient> T getDefault(LlmCapability cap, Class<T> type) {
        return type.cast(defaultClients.get(cap));
    }

    /** 获取深度思考模型（仅 chat 能力） */
    public ChatCapable getDeepThinkingModel() {
        return (ChatCapable) deepThinkingClient;
    }

    /** 获取指定候选 id 的客户端（不存在时返回 Optional.empty） */
    public <T extends CapabilityClient> Optional<T> get(String candidateId, Class<T> type) {
        CapabilityClient client = clientsById.get(candidateId);
        if (client == null) {
            return Optional.empty();
        }
        return Optional.of(type.cast(client));
    }

    /** 获取指定能力的 Fallback Chain（按 priority 排序） */
    public <T extends CapabilityClient> List<T> getFallbackChain(LlmCapability cap, Class<T> type) {
        return fallbackChains.getOrDefault(cap, List.of()).stream()
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    // ====== 内部方法 ======

    private void resolveDefaultModels(LlmProperties properties) {
        for (LlmCapability cap : LlmCapability.values()) {
            ModelGroup group = properties.modelGroup(cap);
            if (group == null || group.defaultModel() == null) continue;
            CapabilityClient client = clientsById.get(group.defaultModel());
            if (client == null) {
                throw new RemoteException(LLM_CONFIG_ERROR,
                    cap + ".default-model references unknown candidate: " + group.defaultModel());
            }
            defaultClients.put(cap, client);
        }
    }

    private CapabilityClient resolveDeepThinkingModel(LlmProperties properties) {
        ModelGroup chatGroup = properties.chat();
        if (chatGroup == null || chatGroup.deepThinkingModel() == null) return null;
        return clientsById.get(chatGroup.deepThinkingModel());
    }

    private CapabilityClient wrapWithResilience(
            CapabilityClient raw, LlmCapability cap,
            ResilienceConfig resilience,
            LlmCircuitBreakerAdapterRegistry circuitBreakers,
            ProbeHandler probeHandler) {

        RetryPolicy retryPolicy = new RetryPolicy(
            resilience.resolveRetry(cap), fallbackEligibility);
        CircuitBreaker circuitBreaker = circuitBreakers.getOrCreate(raw.candidateId());

        return switch (cap) {
            case CHAT -> new ResilientChatClient(
                (ChatCapable) raw, circuitBreaker, retryPolicy, probeHandler);
            case EMBEDDING -> new ResilientEmbeddingClient(
                (EmbeddingCapable) raw, circuitBreaker, retryPolicy);
            case RERANKING -> new ResilientRerankClient(
                (RerankCapable) raw, circuitBreaker, retryPolicy);
        };
    }

    private void validateCandidateReferences(LlmProperties properties, Set<String> providerIds) {
        // 见 §10.4 配置校验
    }

    /** 热刷新：重建索引（保留已有 Resilient 包装的引用，避免中断在飞请求） */
    public synchronized void refresh() {
        // 重新遍历 providers + modelGroups，重建 snapshot
        ...
    }
}
```
## 10. 配置体系

### 10.1 设计原则

- **供应商与模型解耦**：供应商只关心连接信息（url、api-key、endpoints），模型独立声明并引用供应商
- **按能力分组**：模型按 CHAT / EMBEDDING / RERANKING 分组管理，每组有 default-model 和候选列表
- **candidates = Fallback Chain**：候选列表按 priority 排序，不需要单独的 fallback 配置
- **命名槽位**：`default-model` 和 `deep-thinking-model` 是命名槽位，调用方按场景选择

### 10.2 完整 YAML 结构

```yaml
app:
  llm:
    # ==================== 供应商定义（只关心连接） ====================
    providers:
      bailian:
        url: https://dashscope.aliyuncs.com
        api-key: ${BAILIAN_API_KEY:}
        endpoints:
          chat: /compatible-mode/v1/chat/completions
          embedding: /api/v1/services/embeddings/text-embedding/text-embedding
          rerank: /api/v1/services/rerank/text-rerank/text-rerank

      deepseek:
        url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY:}
        endpoints:
          chat: /chat/completions

      zhipu:
        url: https://open.bigmodel.cn/api/paas/v4
        api-key: ${ZHIPU_API_KEY:}
        endpoints:
          chat: /chat/completions

      minimax:
        url: https://api.minimax.chat
        api-key: ${MINIMAX_API_KEY:}
        endpoints:
          chat: /v1/text/chatcompletion_v2

      siliconflow:
        url: https://api.siliconflow.cn/v1
        api-key: ${SILICONFLOW_API_KEY:}
        endpoints:
          embedding: /embeddings
          rerank: /rerank

      ollama:
        url: http://10.0.0.50:11434
        # api-key 不配置 → isAvailable() 仍返回 true（ollama 不需要 key）
        endpoints:
          chat: /api/chat

    # ==================== Chat 模型组 ====================
    chat:
      default-model: qwen3-max
      deep-thinking-model: qwen3-max
      candidates:
        - id: qwen-plus
          provider: bailian
          model: qwen-plus-latest
          priority: 1
        - id: deepseek-v4-flash
          provider: deepseek
          model: deepseek-v4-flash
          supports-streaming: true
          priority: 2
        - id: qwen3-local
          provider: ollama
          model: qwen3:8b-fp16
          priority: 3
        - id: qwen3-max
          provider: bailian
          model: qwen3-max
          supports-thinking: true
          priority: 4

    # ==================== Embedding 模型组 ====================
    embedding:
      default-model: qwen-emb-8b
      candidates:
        - id: qwen-emb-8b
          provider: bailian
          model: Qwen/Qwen3-Embedding-8B
          dimension: 1536
          priority: 1
        - id: bge-large-zh
          provider: siliconflow
          model: BAAI/bge-large-zh-v1.5
          dimension: 1024
          priority: 2

    # ==================== Rerank 模型组 ====================
    rerank:
      default-model: qwen3-rerank
      candidates:
        - id: qwen3-rerank
          provider: bailian
          model: qwen3-rerank
          priority: 1
        - id: qwen3-rerank-sf
          provider: siliconflow
          model: Qwen/Qwen3-Rerank
          priority: 2

    # ==================== 弹性策略配置 ====================
    resilience:
      retry:
        maxAttempts: 3
        baseDelayMs: 500
        maxDelayMs: 5000
        multiplier: 2.0

      circuitBreaker:
        failureThreshold: 5
        openDurationMs: 30000
        halfOpenMaxCalls: 2

      probe:
        probeTimeoutMs: 3000
        enabled: true

      # 按能力类型覆盖全局重试参数（可选，未覆盖的使用全局值）
      retry-overrides:
        EMBEDDING:
          maxAttempts: 5
          baseDelayMs: 300
        RERANKING:
          maxAttempts: 3
          baseDelayMs: 500
```

### 10.3 配置 Properties 类

```java
package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import java.util.Map;

/**
 * LLM 统一配置（映射 {@code app.llm} YAML 节点）
 * <p>
 * 供应商与模型解耦：供应商只关心连接，模型按能力分组并引用供应商。
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
    /** 供应商配置（Map<供应商id, 连接配置>） */
    Map<String, ProviderConfig> providers,
    /** Chat 模型组 */
    ModelGroup chat,
    /** Embedding 模型组 */
    ModelGroup embedding,
    /** Rerank 模型组 */
    ModelGroup rerank,
    /** 弹性策略配置 */
    ResilienceConfig resilience
) {
    /** 按能力获取模型组 */
    public ModelGroup modelGroup(LlmCapability cap) {
        return switch (cap) {
            case CHAT -> chat;
            case EMBEDDING -> embedding;
            case RERANKING -> rerank;
        };
    }

    /** 按 id 查找供应商配置，不存在时抛出 LLM_CONFIG_ERROR */
    public ProviderConfig requireProvider(String id) {
        ProviderConfig config = providers.get(id);
        if (config == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Missing provider config: " + id);
        }
        return config;
    }
}

/**
 * 供应商连接配置（只关心"怎么连"，不关心"有哪些模型"）
 * <p>
 * 映射 YAML 中 {@code app.llm.providers.<id>} 节点。
 */
public record ProviderConfig(
    /** 供应商 id（YAML key，如 "bailian"、"deepseek"、"ollama"） */
    String id,
    /** 基地址（如 "https://dashscope.aliyuncs.com"） */
    String url,
    /** API Key（可为 null，如 ollama 不需要 key） */
    String apiKey,
    /** 端点路径配置 */
    EndpointConfig endpoints
) {
    /** 供应商是否可用：apiKey 已配置则检查非空；未配置（null）视为不需要 key（如 ollama），仍然可用 */
    public boolean isAvailable() {
        return apiKey == null || !apiKey.isBlank();
    }
}

/**
 * 供应商端点路径配置
 * <p>
 * 每个端点为完整路径（从 url 之后开始），null 表示该供应商不支持此能力。
 * 拼接方式：{@code url + endpoint}（不做智能去重，配置者有责任保持一致性）。
 */
public record EndpointConfig(
    /** Chat 端点（null 表示不支持 chat） */
    String chat,
    /** Embedding 端点（null 表示不支持 embedding） */
    String embedding,
    /** Rerank 端点（null 表示不支持 rerank） */
    String rerank
) {}

/**
 * 按能力分组的模型配置
 * <p>
 * 映射 YAML 中 {@code app.llm.chat} / {@code app.llm.embedding} / {@code app.llm.rerank} 节点。
 * candidates 列表按 priority 排序即为 Fallback Chain。
 */
public record ModelGroup(
    /** 主模型候选 id（引用 candidates 中的某个 id） */
    String defaultModel,
    /** 深度思考模型候选 id（chat 组专用，可为 null，可指向不同模型） */
    String deepThinkingModel,
    /** 候选列表（按 priority 排序即为 Fallback Chain） */
    List<ModelCandidate> candidates
) {
    /** 按 priority 排序的候选列表 */
    public List<ModelCandidate> orderedCandidates() {
        return candidates.stream()
            .sorted(Comparator.comparingInt(ModelCandidate::priority))
            .toList();
    }

    /** 按 id 查找候选 */
    public Optional<ModelCandidate> findCandidate(String id) {
        return candidates.stream()
            .filter(c -> c.id().equals(id))
            .findFirst();
    }
}

// ====== Resilience 配置（不变） ======

public record ResilienceConfig(
    RetryProperties retry,
    CircuitBreakerProperties circuitBreaker,
    ProbeProperties probe,
    Map<LlmCapability, RetryProperties> retryOverrides
) {
    public RetryProperties resolveRetry(LlmCapability capability) {
        RetryProperties override = retryOverrides.get(capability);
        if (override == null) {
            return retry;
        }
        return retry.mergeWith(override);
    }
}

public record RetryProperties(
    /** 最大重试次数（null 表示未覆盖，使用全局值） */
    Integer maxAttempts,
    /** 基础退避延迟毫秒（null 表示未覆盖） */
    Long baseDelayMs,
    /** 最大退避延迟毫秒（null 表示未覆盖） */
    Long maxDelayMs,
    /** 退避乘数（null 表示未覆盖） */
    Double multiplier
) {
    /**
     * 合并覆盖值：override 中非 null 的字段覆盖 this 的值。
     * <p>
     * 使用 nullable 类型而非 sentinel 值（如 0），避免"用户显式设置 0"与"未覆盖"的歧义。
     */
    public RetryProperties mergeWith(RetryProperties override) {
        return new RetryProperties(
            override.maxAttempts() != null ? override.maxAttempts() : this.maxAttempts,
            override.baseDelayMs() != null ? override.baseDelayMs() : this.baseDelayMs,
            override.maxDelayMs() != null ? override.maxDelayMs() : this.maxDelayMs,
            override.multiplier() != null ? override.multiplier() : this.multiplier
        );
    }

    /** 返回非 null 的值，用于构建 RetryPolicy（配置校验阶段已确保全局值非 null） */
    public int effectiveMaxAttempts() { return maxAttempts != null ? maxAttempts : 3; }
    public long effectiveBaseDelayMs() { return baseDelayMs != null ? baseDelayMs : 500; }
    public long effectiveMaxDelayMs() { return maxDelayMs != null ? maxDelayMs : 5000; }
    public double effectiveMultiplier() { return multiplier != null ? multiplier : 2.0; }
}

public record CircuitBreakerProperties(
    int failureThreshold,
    long openDurationMs,
    int halfOpenMaxCalls
) {}

public record ProbeProperties(
    long probeTimeoutMs,
    boolean enabled
) {}
```

### 10.4 配置校验

> 启动时校验，fail-fast。校验失败抛出 `RemoteException(LLM_CONFIG_ERROR)` 阻止应用启动。

| 校验项 | 规则 | 校验时机 |
|--------|------|---------|
| `candidate.id` 唯一性 | 全局唯一，跨 ModelGroup 不允许重复 | `LlmClientRegistry` 构造时 |
| `candidate.provider` 引用 | 必须在 `providers` Map 中存在对应 id | `LlmClientRegistry` 构造时 |
| `default-model` 引用 | 必须在 candidates 中存在对应 id | `LlmClientRegistry` 构造时 |
| `deep-thinking-model` 引用 | 必须在 candidates 中存在对应 id（如配置） | `LlmClientRegistry` 构造时 |
| `endpoint` 非空 | candidate 引用的 provider 必须有对应能力的 endpoint 配置 | `LlmProvider.createClient()` 时 |
| `priority` | 非负整数 | `ModelCandidate` 构造时 |
| `capability` | 不可为 null（由所属 ModelGroup 决定） | `ModelCandidate` 构造时 |
| `@Component` 自注册 | YAML 中每个 provider id 要么有对应的 `@Component` Bean，要么由 Registrar 创建 | `LlmClientRegistry` 构造时校验 |

> **YAML 绑定注意事项**：
> - `providers` 是 `Map<String, ProviderConfig>`，YAML key 即供应商 id（如 `bailian`、`deepseek`）
> - `resilience.retryOverrides` 的 key 为 `LlmCapability` 枚举名，推荐统一使用大写（`EMBEDDING`、`RERANKING`）
> - YAML 中使用 kebab-case（如 `default-model`、`deep-thinking-model`、`retry-overrides`），Spring Boot 自动映射到 Java camelCase

```java
// LlmClientRegistry 构造时增加引用校验
private void validateCandidateReferences(LlmProperties properties, Set<String> providerIds) {
    for (LlmCapability cap : LlmCapability.values()) {
        ModelGroup group = properties.modelGroup(cap);
        if (group == null) continue;

        // 校验 default-model 引用
        if (group.defaultModel() != null && group.findCandidate(group.defaultModel()).isEmpty()) {
            throw new RemoteException(LLM_CONFIG_ERROR,
                cap + ".default-model references unknown candidate: " + group.defaultModel());
        }

        // 校验 deep-thinking-model 引用
        if (group.deepThinkingModel() != null && group.findCandidate(group.deepThinkingModel()).isEmpty()) {
            throw new RemoteException(LLM_CONFIG_ERROR,
                cap + ".deep-thinking-model references unknown candidate: " + group.deepThinkingModel());
        }

        // 校验 provider 引用
        for (ModelCandidate candidate : group.candidates()) {
            if (!providerIds.contains(candidate.provider())) {
                throw new RemoteException(LLM_CONFIG_ERROR,
                    "Candidate '" + candidate.id() + "' references unknown provider: " + candidate.provider());
            }
        }
    }
}
```

## 11. 异常体系（复用已有层次结构）【已实施】

> **设计决策**：不新建 `LlmException`，所有 LLM 弹性层异常**继承已有的 `RemoteException`（C类）**。
> 已有异常体系按 A/B/C 三类划分，LLM 调用失败属于"第三方服务错误"，天然属于 C 类。
>
> **实施状态**：§11.4 中的异常重构（`ProbeTimeoutException`、`ModelCircuitOpenException` 改为 `extends RemoteException`，
> 新增 `LlmTransientException`、`RemoteErrorCode` 301xxx 段）**已完成**，不在本次设计范围内。
> 本节保留作为架构参考。

### 11.1 异常层次映射

```
AbstractException (RuntimeException, 携带 IErrorCode)
├── ClientException (A类, 1xxxxx) ← 不可降级
│   ├── ContentFilteredException        ← 内容过滤（已有）
│   └── RateLimitExceededException      ← 客户端限流（已有）
│
├── ServiceException (B类, 2xxxxx) ← 不可降级
│   └── ModelNotFoundException          ← 模型配置不存在（已有）
│
└── RemoteException (C类, 3xxxxx) ← 可降级
    ├── ProviderNotFoundException       ← 厂商未配置（已有, 300001）
    │
    └── LLM 弹性层异常 (301xxx)
        ├── LlmTransientException        ← LLM 瞬态错误（301007, 可重试可降级）
        ├── ModelCircuitOpenException     ← 熔断器打开（301002, 已重构为 RemoteException）
        ├── ProbeTimeoutException         ← 首包探测超时（301003, 已重构为 RemoteException）
        │
        └── 直接抛出的 RemoteException
            ├── LLM_ALL_MODELS_FAILED    ← 链耗尽（301001）
            ├── LLM_CONFIG_ERROR         ← 配置校验失败（301004）
            ├── LLM_RATE_LIMITED         ← 供应商 429（301005）
            └── LLM_RESPONSE_TRUNCATED   ← 流式响应超时（301006）
```


### 11.2 新增 `RemoteErrorCode` 条目

```java
// RemoteErrorCode.java — 新增 301xxx 段
LLM_ALL_MODELS_FAILED(301001, "所有模型均不可用"),
LLM_CIRCUIT_BREAKER_OPEN(301002, "模型熔断器已打开，请稍后重试"),
LLM_PROBE_TIMEOUT(301003, "模型首包探测超时"),
LLM_CONFIG_ERROR(301004, "LLM 配置错误"),
LLM_RATE_LIMITED(301005, "供应商限流（429）"),
LLM_RESPONSE_TRUNCATED(301006, "模型响应被截断"),
LLM_TRANSIENT_ERROR(301007, "LLM 瞬态错误（可重试）"),
```

### 11.3 异常与弹性层的交互

| 异常 | 来源 | 可重试? | 可降级? | 触发熔断? |
|------|------|---------|---------|----------|
| `ContentFilteredException` | 供应商返回内容过滤 | ✗ | ✗ | ✗ |
| `ModelNotFoundException` | Registry 查找失败 | ✗ | ✗（B类） | ✗ |
| `ProbeTimeoutException` | ProbeHandler 首包超时 | ✓ | ✓ | ✓ |
| `ModelCircuitOpenException` | CircuitBreaker 熔断中 | ✗（等待冷却） | ✓ | — |
| `RemoteException(LLM_RATE_LIMITED)` | 供应商 429 | ✓ | ✓ | ✓ |
| `RemoteException(LLM_ALL_MODELS_FAILED)` | FallbackExecutor 链耗尽 | ✗ | — | — |
| `IOException` / `SocketTimeoutException` | 网络层 | ✓ | ✓ | ✓ |
| `LlmTransientException` | LLM 瞬态错误（供应商 5xx、超时等） | ✓ | ✓ | ✓ |
> 所有 C 类异常（`RemoteException` 及其子类）均通过 `FallbackEligibility.isEligible()` 返回 `true`，可触发跨模型降级。

### 11.4 `ProbeTimeoutException` / `ModelCircuitOpenException` / `LlmTransientException` — 已纳入 RemoteException 体系

这三个异常**均为 `RemoteException` 子类**，统一在 C 类异常体系内：

```java
// ProbeTimeoutException.java — 已从 RuntimeException 改为 RemoteException
public class ProbeTimeoutException extends RemoteException {
    public ProbeTimeoutException(String modelId) {
        super(RemoteErrorCode.LLM_PROBE_TIMEOUT, "首包探测超时: " + modelId);
    }
}

// ModelCircuitOpenException.java — 已从 RuntimeException 改为 RemoteException
public class ModelCircuitOpenException extends RemoteException {
    public ModelCircuitOpenException(String modelId) {
        super(RemoteErrorCode.LLM_CIRCUIT_BREAKER_OPEN, "模型熔断器已打开: " + modelId);
    }
}

// LlmTransientException.java — LLM 瞬态错误（供应商 5xx、网关超时、限流等）
// 用于区分"值得重试的 LLM 错误"和"不可恢复的配置/业务错误"
public class LlmTransientException extends RemoteException {
    public LlmTransientException(String message) {
        super(RemoteErrorCode.LLM_TRANSIENT_ERROR, message);
    }
    public LlmTransientException(String message, Throwable cause) {
        super(RemoteErrorCode.LLM_TRANSIENT_ERROR, message, cause);
    }
}
```

**兼容性**：
- `StreamRetryHandler` 中的 `instanceof ProbeTimeoutException` / `instanceof ModelCircuitOpenException` 检查不受影响（类本身未变，只是父类变了）
- `FallbackEligibility.isEligible()` 对 `RemoteException` 返回 `true`，这三个异常自动可降级
- `GlobalExceptionHandler` 会将它们作为 `RemoteException` 统一处理（携带 `RemoteErrorCode`，返回 C 类错误码给前端）

**统一后的异常层次**：
```
AbstractException
├── ClientException (A类)     ← 不可降级
├── ServiceException (B类)    ← 不可降级
└── RemoteException (C类)     ← 可降级
    ├── ProviderNotFoundException     (300001)
    │
    └── LLM 弹性层 (301xxx)
        ├── LLM_ALL_MODELS_FAILED    (301001) ← FallbackExecutor 链耗尽
        ├── ModelCircuitOpenException (301002) ← 熔断器打开（已有异常类）
        ├── ProbeTimeoutException     (301003) ← 首包探测超时（已有异常类）
        ├── LLM_CONFIG_ERROR          (301004) ← 配置校验失败（直接抛 RemoteException）
        ├── LLM_RATE_LIMITED          (301005) ← 供应商 429（直接抛 RemoteException）
        ├── LLM_RESPONSE_TRUNCATED    (301006) ← 流式响应超时截断
        └── LlmTransientException     (301007) ← LLM 瞬态错误（已有异常类）
```



## 12. 调用方迁移

### 12.1 迁移对照表

| 调用方 | 迁移前 | 迁移后 | 复杂度 |
|--------|--------|--------|--------|
| `IntentClassifier` | `ChatClientRegistry.get(id)` + 自写 for-loop 2 次 | `registry.get(id, ChatCapable.class).chat(request)` | ★ |
| `LlmJudgeImpl` | 构造注入 `ChatClient` + 自写 for-loop 2 次 | **本次跳过**，维持独立 `@Bean("judgeChatClient")` 不变 | — |
| `DatasetGenerator` | Spring AI `ChatClient.Builder` 直接注入 | `registry.get(id, ChatCapable.class).chat(request)` | ★ |
| `RagConfig`（query rewrite） | `RewriteQueryTransformer` 内部 ChatClient.Builder | 保留框架组件，将其底层 `ChatClient.Builder` 替换为从 `LlmClientRegistry` 获取的 `ChatCapable`（天然是 `ChatModel`），再 `ChatClient.builder(chatCapable).build()` 构造 | ★★ |
| `DashScopeEmbeddingModel` | 已有独立 REST 实现（自己创建 RestClient） | 迁移到新建 `BaiLianEmbeddingClient extends AbstractEmbeddingClient`（由 `GenericOpenAiProvider` 按 endpoint 自动创建），PgVectorStore 继续用已有 `EmbeddingModel`，新 SPI 调用方用 `EmbeddingCapable` | ★ |
| `AnswerRelevanceScorer` | 注入 Spring AI `EmbeddingModel` | 改为注入 `EmbeddingCapable`（从 Registry 获取） | ★ |
| `BailianRerankPostProcessor` | 自己实现 RestClient + 3x 重试 + 线程池 + 降级 | `registry.get("qwen3-rerank", RerankCapable.class).rerank(request)` | ★★ |
| `AgentModeStrategy` | `ChatClientRegistry` + `TokenCountingChatModel` 包装 | 替换为 `LlmClientRegistry.getDefault(CHAT, ChatCapable.class)`，`ChatCapable` 天然是 `ChatModel`，`TokenCountingChatModel` + `ChatClient.Builder` + Advisor 链零改动 | ★★★ |
| `ChatServiceImpl`（核心路径） | 15 个构造参数，自己编排 fallback + circuit breaker + probe | 由 `FallbackExecutor` + `ResilientChatClient` 处理 | ★★★★ |

> **AgentModeStrategy 迁移策略**：`ChatCapable extends ChatModel`（§5.1），
> 因此 `LlmClientRegistry.getDefault(CHAT, ChatCapable.class)` 返回的 `ChatCapable` 实例天然就是 `ChatModel`。
> `TokenCountingChatModel`（装饰器，接受 `ChatModel`）和 `ChatClient.builder(model).build()` 均无需修改。
> 迁移仅需：将 `chatClientRegistry` 替换为 `LlmClientRegistry`，`getDefault()` 返回类型兼容，
> Spring AI 工具调用（`ToolCallAdvisor` + `DefaultToolCallingManager`）、`MessageChatMemoryAdvisor`、
> `AgentGuardrails` 等组件完全不受影响。
> `ResilientChatClient.call(Prompt)` 通过弹性层桥接（§7.7），从 `Prompt.getInstructions()` 提取
> SystemMessage 确保不丢失，Agent 调用自动获得重试/熔断/首包探测保护。


### 12.2 迁移顺序（风险从低到高）

```
Phase 1（低风险，消除散落重试）:
  1. IntentClassifier        ← 最简单，消除自写 retry
  2. DatasetGenerator        ← 最简单

Phase 2（中风险，统一非 Chat 调用）:
  3. DashScopeEmbeddingModel ← 消除绕路 RestClient
  4. BailianRerankPostProcessor ← 消除绕路 RestClient，迁移到 BaiLianRerankClient
  5. RagConfig (query rewrite) ← 保留 RewriteQueryTransformer，替换底层 ChatClient 注入来源

Phase 3（高风险，核心路径）:
  6. AgentModeStrategy       ← 替换 ChatClientRegistry → LlmClientRegistry，Spring AI 组件不变
  7. ChatServiceImpl (streaming) ← 保留旧路径可灰度切换
  8. ChatServiceImpl (blocking)  ← 最后迁移

跳过（本次不迁移）:
  - LlmJudgeImpl             ← 维持独立 @Bean("judgeChatClient")，评估专用隔离
```

### 12.3 ChatServiceImpl 迁移效果

**Before（15 个构造参数）:**

```java
@Service
public class ChatServiceImpl implements ChatService {
    private final ChatClientRegistry registry;
    private final ModelRouter modelRouter;
    private final ModeRouter modeRouter;
    private final StreamRetryHandler streamRetryHandler;
    private final ProbeStreamHandler probeStreamHandler;
    private final ModelCircuitBreakerRegistry circuitBreakers;
    private final FallbackChainResolver fallbackResolver;
    private final StreamRequestFactory streamRequestFactory;
    private final RequestSpecFactory requestSpecFactory;
    // ... 还有 6 个字段
}
```

**After（职责收敛到编排层）:**

```java
@Service
public class ChatServiceImpl implements ChatService {
    private final LlmClientRegistry registry;
    private final ModeRouter modeRouter;
    private final FallbackExecutor fallbackExecutor;

    /**
     * 阻塞式对话（含模型路由 + 跨模型降级）
     * <p>
     * 执行链：
     * 1. 如果指定 modelId → 尝试该模型，失败后降级到 Fallback Chain
     * 2. 如果未指定 → 直接走 Fallback Chain（按 priority 排序）
     * <p>
     * 单模型：FallbackExecutor(single-element chain) → ResilientChatClient → CircuitBreaker → RetryPolicy → 原始 Client
     * 降级：FallbackExecutor(full chain) → 依次尝试每个 ResilientChatClient
     */
    public LlmResponse chat(@Nullable String modelId, ChatRequest request) {
        List<ChatCapable> chain = resolveChain(modelId);
        return fallbackExecutor.execute(chain, client -> client.chat(request));
    }

    /**
     * 流式对话（含模型路由 + 跨模型降级 + 首包探测 + 重试 + 熔断）
     * <p>
     * 执行链：FallbackExecutor.executeStream()
     *   → ResilientChatClient.chatStream()
     *     → CircuitBreaker.executeStream()     (订阅时检查状态，结束时更新计数)
     *       → RetryPolicy.retryStream()         (首包超时/网络异常时重试整个流)
     *         → ProbeHandler.wrap()             (首包超时 → ProbeTimeoutException)
     *           → delegate.chatStream()         (原始流)
     *   → 流报错时 → onErrorResume 切换到 chain 中下一个 ResilientChatClient
     */
    public Flux<String> chatStream(@Nullable String modelId, ChatRequest request) {
        List<ChatCapable> chain = resolveChain(modelId);
        return fallbackExecutor.executeStream(chain, client -> client.chatStream(request));
    }

    /**
     * 模型路由：解析用户指定的模型 → 构建降级链
     * <p>
     * 策略：
     * <ul>
     *   <li>指定 modelId → 精确匹配该模型作为首选，其后追加同能力的 Fallback Chain（去重）</li>
     *   <li>未指定（null / default） → 使用完整的 Fallback Chain（按 priority 排序）</li>
     * </ul>
     */
    private List<ChatCapable> resolveChain(@Nullable String modelId) {
        List<ChatCapable> fullChain = registry.getFallbackChain(LlmCapability.CHAT, ChatCapable.class);
        if (modelId == null || modelId.isBlank()) {
            return fullChain;
        }
        // 尝试精确匹配指定模型
        return registry.get(modelId, ChatCapable.class)
            .map(preferred -> {
                // 指定模型放首位，追加 fullChain 中不重复的其余模型
                List<ChatCapable> chain = new ArrayList<>();
                chain.add(preferred);
                fullChain.stream()
                    .filter(c -> !c.candidateId().equals(preferred.candidateId()))
                    .forEach(chain::add);
                return (List<ChatCapable>) chain;
            })
            .orElse(fullChain); // 指定的 modelId 不存在时降级到完整链
    }
}
```

> **降级时序示例**（流式路径）：
> ```
> 请求 → deepseek:deepseek-v4-flash (priority=10)
>   → CircuitBreaker: CLOSED ✓
>   → RetryPolicy.retryStream()
>     → ProbeHandler: 首包 3s 超时 → ProbeTimeoutException
>     → retryStream: 重试 1/3 → 再次超时
>     → retryStream: 重试 2/3 → 再次超时
>     → retryStream: 重试 3/3 → 耗尽 → ProbeTimeoutException 冒泡
>   → CircuitBreaker: recordFailure() → failures=1（未达阈值 5）
>   → FallbackExecutor.onErrorResume → 切换到下一个
>
> 请求 → minimax:minimax-text-01 (priority=30)
>   → CircuitBreaker: CLOSED ✓
>   → 正常流式输出 → onComplete → recordSuccess()
> ```


---

## 13. 新增供应商接入流程

### 场景 A：OpenAI 兼容 API（90% 的情况）

只需两步：① 在 `providers` 中添加连接配置 ② 在对应能力的 candidates 中添加模型引用。

**A1. 单能力供应商（如纯 Chat）**

```yaml
app:
  llm:
    providers:
      newprovider:
        url: https://api.newprovider.com
        api-key: ${NEWPROVIDER_API_KEY}
        endpoints:
          chat: /v1/chat/completions

    chat:
      default-model: model-a
      candidates:
        - id: model-a
          provider: newprovider
          model: model-name
          supports-streaming: true
          priority: 30
```

启动后自动：`GenericOpenAiProvider` 创建 → `GenericChatClient` → `ResilientChatClient` 包装 → Fallback Chain 包含。

**A2. 混合能力供应商（Chat + Embedding + Rerank）**

一个供应商下声明多种端点，模型按能力分组引用该供应商：

```yaml
app:
  llm:
    providers:
      aggregated:
        url: https://api.aggregated.com
        api-key: ${AGGREGATED_API_KEY}
        endpoints:
          chat: /v1/chat/completions
          embedding: /v1/embeddings
          rerank: /v1/rerank

    chat:
      default-model: gpt-4o
      candidates:
        - id: gpt-4o
          provider: aggregated
          model: gpt-4o
          supports-streaming: true
          priority: 10

    embedding:
      default-model: emb-large
      candidates:
        - id: emb-large
          provider: aggregated
          model: text-embedding-3-large
          dimension: 3072
          priority: 10

    rerank:
      default-model: rerank-v1
      candidates:
        - id: rerank-v1
          provider: aggregated
          model: rerank-v1
          priority: 10
```

**A3. 用户自定义供应商（私有部署 / 内网代理）**

```yaml
app:
  llm:
    providers:
      my-vllm:
        url: http://192.168.1.100:8000
        # api-key 不配置 → ollama/vLLM 等不需要 key 的服务
        endpoints:
          chat: /v1/chat/completions

      my-ollama:
        url: http://10.0.0.50:11434
        endpoints:
          chat: /api/chat

      my-oneapi:
        url: https://oneapi.mycompany.com
        api-key: ${ONEAPI_KEY}
        endpoints:
          chat: /v1/chat/completions
          embedding: /v1/embeddings
          rerank: /v1/rerank

    chat:
      default-model: qwen-local
      candidates:
        - id: qwen-local
          provider: my-vllm
          model: Qwen/Qwen2.5-72B-Instruct
          supports-streaming: true
          priority: 1
        - id: gpt-4o-proxy
          provider: my-oneapi
          model: gpt-4o
          priority: 2

    embedding:
      default-model: bge-m3
      candidates:
        - id: bge-m3
          provider: my-ollama
          model: bge-m3
          dimension: 1024
          priority: 1
```

> **关键设计意图**：OpenAI 兼容供应商完全由 YAML 驱动。
> 用户不需要理解 Provider/Client/Resilience 的内部结构，只需声明：
> - 我的服务地址和端点是什么（`providers.<id>.url` + `endpoints`）
> - 我有哪些模型（`chat/embedding/rerank.candidates`）
> - 每个模型由哪个供应商提供（`candidate.provider` 引用 `providers` 中的 id）
>
> 声明完成后，`GenericOpenAiProvider` 自动创建客户端 → `LlmClientRegistry` 自动注册 → Resilient 自动包装 → Fallback Chain 自动包含。
> 用户自定义的供应商与内置供应商享有完全相同的弹性保护（重试/熔断/降级/首包探测）。

### 场景 B：非标准 API

写 1 个 Provider + 特殊 Client：

```java
// 1. Provider（@Component 自注册，轻量工厂）
@Component
public class XxxProvider implements LlmProvider {
    private final ProviderConfig config;

    public XxxProvider(LlmProperties properties) {
        this.config = properties.requireProvider("xxx");
    }

    @Override public String id() { return config.id(); }
    @Override public ProviderConfig config() { return config; }

    @Override
    public CapabilityClient createClient(ModelCandidate candidate) {
        return switch (candidate.capability()) {
            case EMBEDDING -> new XxxEmbeddingClient(config, candidate);
            default -> new GenericOpenAiProvider(config).createClient(candidate);
        };
    }
}

// 2. 特殊 Client（封装非标准 API 逻辑）
public class XxxEmbeddingClient extends AbstractEmbeddingClient {
    // 特殊 HTTP 调用逻辑
}
```

### 场景 C：全新能力类型

```java
// 1. 扩展枚举
public enum LlmCapability {
    CHAT, EMBEDDING, RERANKING,
    TTS  // 新增
}

// 2. 新增能力契约接口（与 ChatCapable / EmbeddingCapable / RerankCapable 同级）
public interface TtsCapable extends CapabilityClient {
    byte[] synthesize(String text, TtsConfig config);
}

// 3. 新增抽象类
public abstract class AbstractTtsClient implements TtsCapable {
    // 公共字段（candidateId、candidate 等）由基类统一处理
}

// 4. 新增 Resilient 装饰器（只实现接口，不继承抽象类——与 §7.7 设计决策一致）
public class ResilientTtsClient implements TtsCapable { ... }

// 5. 在以下 3 处 switch 中添加 TTS case（框架内部修改，不影响已有调用方）：
//    - LlmClientRegistry.wrapWithResilience() → case TTS -> new ResilientTtsClient(...)
//    - LlmProperties.modelGroup() → case TTS -> tts
//    - GenericOpenAiProvider.createClient() → case TTS -> new GenericTtsClient(...)
// 新增调用方代码（如 TTSService）只需注入 TtsCapable，不接触框架内部。
---

## 14. 目录结构

```
com.smart.rag.infrastructure.llm/
│
├── LlmCapability.java                      # 能力枚举（CHAT / EMBEDDING / RERANKING）
├── ModelCandidate.java                      # 模型候选声明（替代 ModelSpec）
├── CapabilityClient.java                    # 客户端根接口（candidateId / providerId / modelName）
├── ChatCapable.java                         # Chat 能力契约
├── EmbeddingCapable.java                    # Embedding 能力契约
├── RerankCapable.java                       # Rerank 能力契约
├── LlmProvider.java                         # 供应商接口（轻量工厂：id + config + createClient）
├── Message.java                             # 对话消息
├── ChatRequest.java                         # Chat 请求
├── LlmResponse.java                         # Chat 响应
├── EmbeddingType.java                       # 嵌入类型
├── RerankRequest.java                       # 重排请求
├── RerankResult.java                        # 重排结果
│                                          # 异常体系：复用已有 exception/ 包
├── client/                                  # 能力客户端抽象层
│   ├── AbstractChatClient.java
│   ├── AbstractEmbeddingClient.java
│   └── AbstractRerankClient.java
│
├── resilience/                              # 统一弹性层
│   ├── ResilientChatClient.java             # Chat 装饰器（含 emitted 追踪）
│   ├── ResilientEmbeddingClient.java        # Embedding 装饰器
│   ├── ResilientRerankClient.java           # Rerank 装饰器
│   ├── RetryPolicy.java                     # 统一重试策略（复用 FallbackEligibility）
│   ├── FallbackExecutor.java                # 跨模型降级执行器
│   ├── CircuitBreaker.java                  # 熔断器适配器（首包超时 + 异常过滤）
│   ├── LlmCircuitBreakerAdapterRegistry.java # 熔断器注册表
│   └── ProbeHandler.java                    # 首包探测
│
├── provider/                                # 供应商实现
│   ├── generic/                             # 通用 OpenAI 兼容 Provider
│   │   ├── GenericOpenAiProvider.java       # 轻量工厂，按 endpoint 创建 Client
│   │   ├── GenericOpenAiProviderRegistrar.java # BeanDefinitionRegistryPostProcessor
│   │   ├── GenericChatClient.java
│   │   ├── GenericEmbeddingClient.java
│   │   └── GenericRerankClient.java
│   ├── bailian/                             # 百炼特殊 Client（由 GenericOpenAiProvider 按 endpoint 自动创建）
│   │   ├── BaiLianEmbeddingClient.java     # 百炼 Embedding（批量分片 + text_type 路由）
│   │   └── BaiLianRerankClient.java        # 百炼 Rerank（非标准端点）
│
├── config/                                  # 配置（供应商与模型解耦）
│   ├── LlmProperties.java                  # @ConfigurationProperties（providers + chat/embedding/rerank ModelGroups）
│   ├── ProviderConfig.java                  # 供应商连接配置（id / url / apiKey / endpoints）
│   ├── EndpointConfig.java                  # 端点路径配置（chat / embedding / rerank）
│   ├── ModelGroup.java                      # 按能力分组（default-model / deep-thinking-model / candidates）
│   ├── ResilienceConfig.java
│   ├── RetryProperties.java
│   ├── CircuitBreakerProperties.java
│   └── ProbeProperties.java
│
└── registry/
    └── LlmClientRegistry.java               # 统一注册表（遍历 ModelGroup → Provider 创建 Client → Resilient 包装）
```

---

## 15. 分阶段实施路线

### Phase 1: 接口定义 + 弹性层（零破坏）

新增文件，不修改任何现有代码：

- `LlmCapability.java`
- `ModelCandidate.java`
- `CapabilityClient.java`
- `ChatCapable.java`
- `EmbeddingCapable.java`
- `RerankCapable.java`
- `LlmProvider.java`
- `Message.java`
- `ChatRequest.java`
- `LlmResponse.java`
- `RemoteErrorCode.java`（修改已有类，新增 301xxx LLM 弹性层错误码）【已完成】
- `RerankRequest.java`
- `RerankResult.java`
- `LlmTransientException.java`（LLM 瞬态异常，extends RemoteException）【已完成】
- `AbstractChatClient.java`
- `AbstractEmbeddingClient.java`
- `AbstractRerankClient.java`
- `RetryPolicy.java`（集成已有 `FallbackEligibility`）
- `FallbackExecutor.java`（集成已有 `FallbackEligibility`）
- `CircuitBreaker.java`（适配器，包装已有 `ModelCircuitBreakerRegistry`）
- `LlmCircuitBreakerAdapterRegistry.java`（包装已有 `ModelCircuitBreakerRegistry`）
- `ProbeHandler.java`（包装已有 `ProbeStreamHandler` + `SharedProbeRegistry`）
- `EmbeddingType.java`
- 配置 Properties 类

> **已有基础设施（不修改，直接复用）**：
> - `ModelCircuitBreakerRegistry` — 三态熔断器（CLOSED→OPEN→HALF_OPEN）
> - `ProbeStreamHandler` — 首包超时检测（Flux.create + timer）
> - `SharedProbeRegistry` — 探测去重（ConcurrentHashMap + CompletableFuture）
> - `FallbackEligibility` — 异常可降级判定（已修复：RemoteException(C类) 可降级）
> - `ModelHealthCache` — Redis 健康缓存（可选）

> **已重构/新增的异常（纳入 RemoteException 体系）【已完成，不在本次设计范围内】**：
> - `ProbeTimeoutException` — 已从 `RuntimeException` 改为 `extends RemoteException`（RemoteErrorCode.LLM_PROBE_TIMEOUT）
> - `ModelCircuitOpenException` — 已从 `RuntimeException` 改为 `extends RemoteException`（RemoteErrorCode.LLM_CIRCUIT_BREAKER_OPEN）
> - `LlmTransientException` — 新增，`extends RemoteException`（RemoteErrorCode.LLM_TRANSIENT_ERROR），用于 LLM 瞬态错误（限流、服务过载等）
> - `RemoteErrorCode` — 新增 301xxx 段（7 个 LLM 弹性层错误码：301001-301007）

### Phase 2: Provider 实现（逐个迁移）

新增 Provider 实现，取代现有 `ModelProvider`：
- `GenericOpenAiProvider.java` + `GenericOpenAiProviderRegistrar.java` + `GenericChatClient.java` + `GenericEmbeddingClient.java`（RestClient，非 Spring AI）+ `GenericRerankClient.java`
- `BaiLianEmbeddingClient.java`（百炼 Embedding 非标准 API，由 `GenericOpenAiProvider` 按 endpoint 自动创建，不需要独立 Provider）+ `BaiLianRerankClient.java`（百炼 Rerank 非标准 API，从 `BailianRerankPostProcessor` 迁移）

> DeepSeek / ZhiPu / MiniMax 均为 OpenAI 兼容 API，由 `GenericOpenAiProvider` 自动处理，
> 不需要自定义 Provider 实现（零代码配置）。

### Phase 3: 调用方迁移（逐个替换）

按风险从低到高顺序迁移（见 12.2 节）。

### Phase 4: 清理旧代码

删除：
- 旧 `ModelProvider` 接口和实现
- `AbstractModelProvider`
- `ChatClientRegistry`
- `OkHttpSseModelStreamClient`（流式统一到 GenericChatClient 内部）
- `ModelStreamRequest` / `ModelStreamRequestFactory`
- 各调用点散落的自写重试逻辑
- `DashScopeEmbeddingModel` 中的旧重试逻辑（迁移后由 `ResilientEmbeddingClient` 统一处理，适配器层只保留委托代码）
- `AgentModeStrategy` 中的 `ChatClientRegistry` 依赖（替换为 `LlmClientRegistry`）
---

## 16. 风险与缓解措施

| 风险 | 缓解措施 |
|------|---------|
| 核心聊天路径迁移回归 | Phase 3 最后迁移，保留旧路径可灰度切换 |
| `TokenCountingChatModel` 包装（Agent guardrails） | `ChatCapable extends ChatModel`，`TokenCountingChatModel` 接受任意 `ChatModel`，零改动 |
| `AgentModeStrategy` 工具调用深度耦合 Spring AI | `ChatCapable` 天然是 `ChatModel`，`ToolCallAdvisor` + `DefaultToolCallingManager` + `MessageChatMemoryAdvisor` 无需修改。仅替换 `ChatClientRegistry` → `LlmClientRegistry`。`ResilientChatClient.call(Prompt)` 通过弹性层桥接，Agent 获得重试/熔断保护 |
| `ChatRequestSpecFactory` 的 Advisor 链 | 保持在编排层，CapabilityClient 只负责传输 |
| `DashScopeEmbeddingModel` 多实现 `EmbeddingCapable` 后 PgVectorStore 兼容性 | 保留 `@Primary implements EmbeddingModel`，PgVectorStore 注入不变，行为不变 |
| Embedding 批量分片逻辑 | 已有 `DashScopeEmbeddingModel.callBatch()` 中实现，迁移后保留，由 `ResilientEmbeddingClient` 包装重试 |
| Evaluation Profile 独立 ChatClient | `LlmClientRegistry` 支持条件注册，evaluation 专用模型按 Profile 过滤 |
| 旧 `compositeId`（`provider/model` 格式）对应 `get(compositeId, type)` 查询 | 新 `candidateId`（YAML candidate.id，全局唯一）对应一个 client 实例，能力由 `capability()` 声明（类型系统强制一对一） |
| 两 Registry 共存期（Phase 2-3） | 本地开发阶段可容忍。旧 `ChatClientRegistry` 用 `"provider/model"` 格式 ID，新 `LlmClientRegistry` 用 `"provider:model"` 格式 ID，互不冲突。熔断器共享底层 `ModelCircuitBreakerRegistry`，状态一致 |
| HTTP 连接池碎片化 | 每个 `GenericOpenAiProvider` 实例创建独立 RestClient。5 个供应商 = 5 个连接池，本地开发阶段可接受。生产化时考虑注入共享 `RestClient.Builder` |
| Token 使用量追踪 | `LlmResponse.TokenUsage` 由调用方（如 `ChatServiceImpl`）回传到 `ChatUsageTracker`，与现有追踪系统集成方式不变 |
| LlmJudge 隔离性 | **本次不迁移**，维持独立 `@Bean("judgeChatClient")`，评估专用模型不受聊天路径弹性策略影响 |

---

## 17. 附录：当前调用点映射

| # | 当前文件 | 当前调用模式 | 重构后入口 |
|---|---------|------------|-----------|
| 1 | `AbstractModeStrategy:50` | `ChatClient.call().chatResponse()` | `registry.get(id, ChatCapable.class).chat()` |
| 2 | `AbstractModeStrategy:63` | `OkHttpSseModelStreamClient.stream()` | `registry.get(id, ChatCapable.class).chatStream()` |
| 3 | `MultiTurnModeStrategy:77` | `OkHttpSseModelStreamClient.stream()` | `registry.get(id, ChatCapable.class).chatStream()` |
| 4 | `IntentClassifier:83` | `ChatClient.prompt().call().content()` | `registry.get(id, ChatCapable.class).chat()` |
| 5 | `AgentModeStrategy:134` | `ChatClientRegistry.getChatModel()` + `TokenCountingChatModel` | `LlmClientRegistry.getDefault(CHAT, ChatCapable.class)`，`ChatCapable` 天然是 `ChatModel`，Advisor 链不变 |
| 6 | `RagConfig:58` | `RewriteQueryTransformer` | 保留框架组件，替换底层 ChatClient 注入来源 |
| 7 | `DashScopeEmbeddingModel:206` | `RestClient.post()` | `LlmClientRegistry.getDefault(EMBEDDING, EmbeddingCapable.class).embed()` |
| 8 | `BailianRerankPostProcessor:114` | `RestClient.post()` | `LlmClientRegistry.getDefault(RERANKING, RerankCapable.class).rerank()` |
| 9 | `LlmJudgeImpl:49` | `ChatClient.prompt().call().content()` | **本次不迁移**，维持独立 `@Bean("judgeChatClient")` |
| 10 | `DatasetGenerator:171` | `ChatClient.prompt().call().content()` | `registry.get(id, ChatCapable.class).chat()` |
| 11 | `DeepSeekModelProvider:81` | `RestClient.get().uri('/models')` | `LlmProvider.fetchRemoteModels()` + YAML 静态声明 |
| 12 | `MiniMaxModelProvider:82` | `RestClient.get().uri('/v1/models')` | `LlmProvider.fetchRemoteModels()` + YAML 静态声明 |

---

## 18. 资源生命周期管理

### 18.1 问题

当前 `BailianRerankPostProcessor`（已迁移为 `BaiLianRerankClient`）实现 `DisposableBean` 关闭线程池，`OkHttpClient` 有连接池需要关闭。
新设计中 `GenericOpenAiProvider` 等 Provider 持有 RestClient/HTTP 客户端。

### 18.2 方案

§4.4 的 `LlmProvider` 接口已定义 `default void close() {}` 方法。持有资源的 Provider 覆写此方法：

实现示例：
- `GenericOpenAiProvider` — 实现 `DisposableBean`，`destroy()` 调用 `close()` 关闭 RestClient 连接池
- `BaiLianRerankClient` — 内部线程池在 `close()` 中优雅关闭；`DashScopeEmbeddingModel` 委托给已有资源管理

### 18.3 共享 HTTP 基础设施（生产化建议）

当前每个 `GenericOpenAiProvider` 创建独立 RestClient。生产化时建议注入共享的 `RestClient.Builder`：

```java
// 共享连接池配置
@Bean
RestClient.Builder sharedRestClientBuilder() {
    return RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build()));
}
```

各 Provider 通过构造函数注入共享 Builder，按需设置 `baseUrl` 和 `headers`。

---

## 19. 可观测性预留

### 19.1 Metrics 挂钩点

`Resilient` 装饰器是统一的 metrics 采集点，建议在以下位置预留 Micrometer 指标：

| 装饰器 | 指标 | 类型 | 标签 |
|--------|------|------|------|
| `ResilientChatClient` | `llm.chat.latency` | Timer | `candidateId`, `result` |
| `ResilientChatClient` | `llm.chat.tokens` | Counter | `candidateId`, `type` (prompt/completion) |
| `ResilientEmbeddingClient` | `llm.embed.latency` | Timer | `candidateId` |
| `ResilientRerankClient` | `llm.rerank.latency` | Timer | `candidateId` |
| `CircuitBreaker` | `llm.circuit.state` | Gauge | `candidateId` |
| `FallbackExecutor` | `llm.fallback.invocations` | Counter | `capability`, `from`, `to` |

### 19.2 实现方式

```java
// ResilientChatClient.chat() 中预留 metrics 挂钩
@Override
public LlmResponse chat(ChatRequest request) {
    Timer.Sample sample = Timer.start(registry);
    try {
        LlmResponse response = circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.chat(request)
            )
        );
        sample.stop(Timer.builder("llm.chat.latency")
            .tag("candidateId", candidateId())
            .tag("result", "success")
            .register(registry));
        return response;
    } catch (Exception e) {
        sample.stop(Timer.builder("llm.chat.latency")
            .tag("candidateId", candidateId())
            .tag("result", "error")
            .register(registry));
        throw e;
    }
}
```

> **实施建议**：Phase 1 先搭建接口 + 弹性层，Metrics 作为 Phase 4（可观测性增强）加入，不影响核心架构。
