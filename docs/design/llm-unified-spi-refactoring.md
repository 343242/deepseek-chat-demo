# LLM 调用层统一 SPI 重构方案

> **版本**: v1.0  
> **日期**: 2026-06-08  
> **状态**: 设计方案（待评审）

---

## 1. 背景与动机

### 1.1 现状问题

当前项目的 LLM 调用点散落在 12 个位置，存在三条核心问题：

**问题一：四条并行的传输路径**

| 调用点 | 传输层 | 重试/熔断 |
|--------|--------|----------|
| 聊天（阻塞） | Spring AI `ChatClient.call()` | 由 `ChatServiceImpl` 编排 |
| 聊天（流式） | `OkHttpSseModelStreamClient`（原生 OkHttp SSE） | `StreamRetryHandler` |
| 意图分类 | Spring AI `ChatClient.prompt().call()` | 自写 for-loop 2 次 |
| Judge 评估 | Spring AI `ChatClient.prompt().call()` | 自写 for-loop 2 次 |
| 向量嵌入 | `RestClient`（DashScope 原生 API） | 自写 3 次指数退避 |
| 重排序 | `RestClient`（Bailian `/reranks`） | 自写 3 次指数退避 + 专用线程池 |

**问题二：嵌入/重排绕过 Provider 体系**

`DashScopeEmbeddingModel` 和 `BailianRerankPostProcessor` 直接用 `RestClient` 调用 LLM API，完全不走 `ModelProvider` 体系，导致：
- 无法统一管理供应商健康状态
- 无法统一施加重试/熔断策略
- 新增供应商时需要在多个位置分散配置

**问题三：重试策略碎片化**

6 个调用点各自实现重试，逻辑不一致（2 次/3 次、有无退避、有无熔断），无法保证弹性行为的统一性。

### 1.2 保留的设计模式

以下**设计模式**经过验证是合理的，本次重构保留其核心思想，但**实现类被新架构替代**：

- **SPI 模式** — 原 `ModelProvider` 接口 + `AbstractModelProvider` 模板方法的 SPI 思想保留，由 `LlmProvider` + `AbstractXxxClient` 接替
- **注册表模式** — 原 `ProviderRegistry` 自动发现 + `ModelRouter` 路由的思想保留，由 `LlmClientRegistry` 接替
- **两阶段降级算法** — 原 `StreamRetryHandler`（同模型重试 → 跨模型降级）核心算法保留，由 `RetryPolicy` + `FallbackExecutor` 接替
- **熔断器** — 原 `ModelCircuitBreakerRegistry` 三态熔断器保留，由 `CircuitBreakerRegistry` 统一管理
- **首包探测** — 原 `ProbeStreamHandler` 首包探测保留，由 `ProbeHandler` 在 `ResilientChatClient` 中施加

---

## 2. 设计目标

| 目标 | 度量 |
|------|------|
| **新增供应商零代码** | OpenAI 兼容 API 只需 YAML 配置，不写 Java 代码 |
| **新增能力类型可扩展** | 新增 TTS/STT 等能力只需扩展枚举 + 新增抽象类，已有代码不修改 |
| **重试/熔断统一** | 所有 LLM 调用共享同一套弹性策略配置，不分散在各调用点 |
| **面向接口编程** | 调用方依赖抽象类（`AbstractChatClient` 等），不依赖具体实现 |
| **配置驱动** | 模型声明、Fallback Chain、弹性参数全部 YAML 配置化 |
| **向后兼容** | 分阶段迁移，核心聊天路径最后迁移，可灰度切换 |

---

## 3. 核心架构

### 3.1 三层分离模型

```
┌─────────────────────────────────────────────────────────┐
│                    调用方 (Callers)                       │
│  ChatService · IntentClassifier · LlmJudge · Reranker   │
└──────────────────────────┬──────────────────────────────┘
                           │ 依赖抽象类
┌──────────────────────────▼──────────────────────────────┐
│              CapabilityClient 抽象层                      │
│  AbstractChatClient · AbstractEmbeddingClient            │
│  AbstractRerankClient                                    │
└──────────────────────────┬──────────────────────────────┘
                           │ 实现
┌──────────────────────────▼──────────────────────────────┐
│                 LlmProvider 供应商层                       │
│  每个 Provider 是一个能力容器，按 compositeId 分发客户端     │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐        │
│  │ DeepSeek    │ │ DashScope   │ │ Generic     │        │
│  │ Provider    │ │ Provider    │ │ OpenAI      │        │
│  │             │ │             │ │ Provider    │        │
│  │ chat: ✓     │ │ chat: ✓     │ │ (纯配置)    │        │
│  │ embed: ✗    │ │ embed: ✓    │ │             │        │
│  │ rerank: ✗   │ │ rerank: ✗   │ │             │        │
│  └─────────────┘ └─────────────┘ └─────────────┘        │
└──────────────────────────┬──────────────────────────────┘
                           │ 装饰
┌──────────────────────────▼──────────────────────────────┐
│                 Resilience 弹性层                         │
│  ResilientChatClient · ResilientEmbeddingClient          │
│  ResilientRerankClient                                   │
│  （统一重试策略 · 统一熔断保护 · 统一降级执行器）           │
└──────────────────────────┬──────────────────────────────┘
                           │ 使用
┌──────────────────────────▼──────────────────────────────┐
│               底层 SDK / HTTP 客户端                       │
│  Spring AI ChatModel · RestClient · OkHttp               │
└─────────────────────────────────────────────────────────┘
```

### 3.2 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Provider 和 Client 的关系 | **Provider 是 Client 的容器**（非一对一） | 一个供应商可以有多个模型、多种能力，Provider 按 compositeId 分发 |
| 接口 vs 抽象类 | **CapabilityClient 用接口，三个能力客户端用抽象类** | 接口保证根的灵活性，抽象类提取公共字段（compositeId、modelSpec 等）避免重复 |
| 通用供应商 | **GenericOpenAiProvider 配置驱动** | 90%+ 的供应商是 OpenAI 兼容 API，纯配置即可接入 |
| 弹性层粒度 | **三个类型安全的装饰器，共享同一套配置** | 类型安全（编译期检查）+ 配置统一（YAML 一处定义） |
| Fallback Chain | **按能力类型配置，配置优先 + priority 自动追加** | 灵活可控，未配置的模型按 priority 自动归入降级链 |

---

## 4. 接口与抽象类设计

### 4.1 `LlmCapability` — 能力枚举

```java
package com.smart.rag.infrastructure.llm;

/**
 * 模型能力声明
 * <p>
 * 每个 ModelSpec 通过 capabilities 声明该模型支持的操作。
 * 调用方据此过滤可用客户端，Registry 据此构建按能力分类的索引。
 * <p>
 * 扩展方式：新增枚举值即可，不影响已有代码。
 */
public enum LlmCapability {

    /** 阻塞式对话 */
    CHAT,

    /** 流式对话（SSE） */
    CHAT_STREAM,

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

### 4.2 `ModelSpec` — 模型声明

```java
package com.smart.rag.infrastructure.llm;

import java.util.Map;
import java.util.Set;

/**
 * 模型声明——描述一个供应商下的一个具体模型
 * <p>
 * 直接映射 YAML 配置。新增一个 ModelSpec 就等于新增一个可用模型。
 *
 * <pre>
 * YAML 示例：
 * models:
 *   - modelId: deepseek-v4-flash
 *     displayName: DeepSeek V4 Flash
 *     capabilities: [CHAT, CHAT_STREAM]
 *     priority: 10
 *     params:
 *       temperature: 0.7
 *       maxTokens: 4096
 * </pre>
 */
public record ModelSpec(
    /** 模型在该供应商下的 ID，如 "text-embedding-v4" */
    String modelId,

    /** compositeId，格式 "{providerId}:{modelId}"，如 "dashscope:text-embedding-v4" */
    String compositeId,

    /** 人类可读显示名 */
    String displayName,

    /** 该模型支持的能力集 */
    Set<LlmCapability> capabilities,

    /** 默认调用参数（temperature、maxTokens 等），调用方可以覆盖 */
    Map<String, Object> defaultParams,

    /** 优先级，数字越小越优先，用于 Fallback Chain 排序 */
    int priority,

    /** 是否启用（禁用的模型不会被注册） */
    boolean enabled
) {}
```

### 4.3 `CapabilityClient` — 客户端根接口

```java
package com.smart.rag.infrastructure.llm;

import java.util.Set;

/**
 * 能力客户端根接口
 * <p>
 * 所有 LLM 能力客户端（Chat / Embedding / Rerank）的公共契约。
 * 不定义具体 LLM 调用方法——通过 Registry 的类型查询获取具体能力接口。
 *
 * <pre>
 * 使用示例（推荐）：
 * EmbeddingCapable embed = registry.get("dashscope:text-embedding-v4", EmbeddingCapable.class);
 * float[] vector = embed.embed("查询文本", EmbeddingType.QUERY);
 * </pre>
 */
public interface CapabilityClient {

    /** compositeId，格式 "{providerId}:{modelId}" */
    String compositeId();

    /** 供应商 ID */
    String providerId();

    /** 模型 ID */
    String modelId();

    /** 该客户端支持的能力集 */
    Set<LlmCapability> capabilities();

    /** 该客户端是否可用（API key 有效 + 基础连通性） */
    boolean isAvailable();

    /** 返回模型声明 */
    ModelSpec modelSpec();
}
```

### 4.4 `LlmProvider` — 供应商接口

```java
package com.smart.rag.infrastructure.llm;

import java.util.List;
import java.util.Optional;

/**
 * LLM 供应商接口
 * <p>
 * 一个 Provider 是一个供应商的完整能力集合，是 Client 的容器和工厂。
 * <p>
 * 职责：
 * <ol>
 *   <li>识别自己是谁（id、name）</li>
 *   <li>声明自己有哪些模型（models）</li>
 *   <li>按 compositeId 生产对应的能力客户端</li>
 *   <li>管理供应商级别的共享状态（API key、健康检查）</li>
 * </ol>
 * <p>
 * 新供应商接入方式：
 * <ul>
 *   <li>OpenAI 兼容 API → 不需要实现此接口，由 {@code GenericOpenAiProvider} 自动处理</li>
 *   <li>非标准 API → 实现此接口 + {@code @Component}</li>
 * </ul>
 */
public interface LlmProvider {

    /** 供应商 ID，如 "deepseek" / "dashscope" / "siliconflow" */
    String id();

    /** 供应商显示名 */
    String name();

    /** 该供应商下的所有模型声明 */
    List<ModelSpec> models();

    /** 供应商级别可用性检查（API key 已配置 + 基础连通性） */
    boolean isAvailable();

    /**
     * 按 compositeId 获取能力客户端
     * <p>
     * 返回的是原始客户端（未包装 Resilience），
     * 由 Registry 在注册时统一包装 Resilient 装饰器。
     *
     * @param compositeId 格式 "{providerId}:{modelId}"
     * @return 对应的能力客户端，不存在时返回 empty
     */
    Optional<CapabilityClient> getClient(String compositeId);
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

    /** 温度（覆盖 ModelSpec.defaultParams 中的值） */
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
 * 部分模型（如 DashScope text-embedding-v4）对两者使用不同的编码策略。
 */
public enum EmbeddingType {
    /** 检索查询 */
    QUERY,
    /** 文档索引 */
    DOCUMENT
}
```

### 4.8 `ChatResponse` — Chat 响应

```java
package com.smart.rag.infrastructure.llm;

import java.util.List;
import java.util.Map;

/**
 * 统一 Chat 响应
 */
public record ChatResponse(
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
 */
public record RerankRequest(
    /** 检索查询文本 */
    String query,
    /** 候选文档列表 */
    List<String> documents,
    /** 返回的最大结果数（null 表示全部返回） */
    Integer topN
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
 */
public interface ChatCapable extends CapabilityClient {

    /** 阻塞式对话 */
    ChatResponse chat(ChatRequest request);

    /** 流式对话（SSE） */
    Flux<String> chatStream(ChatRequest request);

    /** 带工具调用的对话（Agent 场景） */
    default ChatResponse chatWithTools(ChatRequest request, List<Object> tools) {
        throw new UnsupportedOperationException(
            "Tool calling not supported by " + compositeId());
    }

    /** 是否支持流式 */
    default boolean supportsStreaming() {
        return capabilities().contains(LlmCapability.CHAT_STREAM);
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
import java.util.List;
import java.util.Set;

/**
 * Chat 客户端抽象基类
 * <p>
 * 实现 {@link ChatCapable} 接口。子类只需实现 {@link #chat} 和 {@link #chatStream}，
 * 其他元信息方法由基类通过 {@link ModelSpec} 统一处理。
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientChatClient} 装饰器在外部施加。
 *
 * <pre>
 * 子类实现示例：
 * - GenericChatClient（OpenAI 兼容 API，Spring AI ChatModel）
 * - DashScopeChatClient（DashScope 专用 API，如需要）
 * </pre>
 */
public abstract class AbstractChatClient implements ChatCapable {

    protected final ModelSpec spec;
    protected final String providerId;

    protected AbstractChatClient(ModelSpec spec, String providerId) {
        this.spec = spec;
        this.providerId = providerId;
    }

    @Override
    public final String compositeId() { return spec.compositeId(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelId() { return spec.modelId(); }

    @Override
    public final Set<LlmCapability> capabilities() { return spec.capabilities(); }

    @Override
    public final ModelSpec modelSpec() { return spec; }

    /**
     * 阻塞式对话
     *
     * @param request 对话请求（包含 prompt、systemPrompt、history 等）
     * @return 统一响应
     */
    public abstract ChatResponse chat(ChatRequest request);

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
    public ChatResponse chatWithTools(ChatRequest request, List<Object> tools) {
        throw new UnsupportedOperationException(
            "Tool calling not supported by " + compositeId());
    }

    /**
     * 是否支持流式
     */
    public boolean supportsStreaming() {
        return capabilities().contains(LlmCapability.CHAT_STREAM);
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
 * - DashScopeEmbeddingClient（DashScope 原生 /api/v1/services/embeddings/text-embedding/text-embedding）
 * </pre>
 */
public abstract class AbstractEmbeddingClient implements EmbeddingCapable {

    protected final ModelSpec spec;
    protected final String providerId;

    protected AbstractEmbeddingClient(ModelSpec spec, String providerId) {
        this.spec = spec;
        this.providerId = providerId;
    }

    @Override
    public final String compositeId() { return spec.compositeId(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelId() { return spec.modelId(); }

    @Override
    public final Set<LlmCapability> capabilities() { return spec.capabilities(); }

    @Override
    public final ModelSpec modelSpec() { return spec; }

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
 * - BailianRerankClient（百炼 /reranks 非标准 API）
 * </pre>
 */
public abstract class AbstractRerankClient implements RerankCapable {

    protected final ModelSpec spec;
    protected final String providerId;

    protected AbstractRerankClient(ModelSpec spec, String providerId) {
        this.spec = spec;
        this.providerId = providerId;
    }

    @Override
    public final String compositeId() { return spec.compositeId(); }

    @Override
    public final String providerId() { return providerId; }

    @Override
    public final String modelId() { return spec.modelId(); }

    @Override
    public final Set<LlmCapability> capabilities() { return spec.capabilities(); }

    @Override
    public final ModelSpec modelSpec() { return spec; }

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

- **重试策略**：所有 LLM 操作共享同一套参数配置（YAML 一处定义）
- **熔断保护**：按 `compositeId` 粒度独立熔断（同一个供应商的 chat 和 embedding 互不影响）
- **装饰器模式**：ResilientClient 包装原始 Client，透明施加弹性行为
- **类型安全**：三个 Resilient 装饰器，分别对应三种能力类型，编译期检查

### 7.2 `RetryPolicy` — 统一重试策略

```java
package com.smart.rag.infrastructure.llm.resilience;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 统一重试策略
 * <p>
 * 所有 LLM 操作共用同一套重试参数，由 {@code app.llm.resilience.retry} 配置。
 * 替换各调用点散落的 for-loop / 自写指数退避。
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
 *         retryableExceptions:
 *           - java.net.SocketTimeoutException
 *           - java.io.IOException
 * </pre>
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double multiplier;
    private final Set<Class<? extends Exception>> retryableExceptions;
    private final FallbackEligibility fallbackEligibility;

    public RetryPolicy(RetryProperties properties, FallbackEligibility fallbackEligibility) {
        this.maxAttempts = properties.maxAttempts();
        this.baseDelayMs = properties.baseDelayMs();
        this.maxDelayMs = properties.maxDelayMs();
        this.multiplier = properties.multiplier();
        this.fallbackEligibility = fallbackEligibility;
        // 从类名字符串解析为 Class 对象
        this.retryableExceptions = properties.retryableExceptions().stream()
            .map(name -> {
                try {
                    @SuppressWarnings("unchecked")
                    Class<? extends Exception> clazz =
                        (Class<? extends Exception>) Class.forName(name);
                    return clazz;
                } catch (ClassNotFoundException e) {
                    throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR, "不可重试的异常类: " + name, e);
                }
            })
            .collect(Collectors.toSet());
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
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                if (!isRetryable(e) || attempt == maxAttempts) {
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
     * </ul>
     *
     * @param streamSupplier 返回 Flux 的可重试操作
     * @return 带重试语义的 Flux
     */
    public <T> Flux<T> retryStream(Supplier<Flux<T>> streamSupplier) {
        return Flux.defer(streamSupplier)
            .retryWhen(Retry.backoff(maxAttempts, Duration.ofMillis(baseDelayMs))
                .maxBackoff(Duration.ofMillis(maxDelayMs))
                .filter(this::isRetryable)
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
     *   <li>已有的 {@link FallbackEligibility} — 过滤用户错误（ContentFilteredException 等），不可重试也不可降级</li>
     *   <li>retryableExceptions 配置 — 只有网络超时、IO 异常等才值得重试</li>
     * </ol>
     */
    private boolean isRetryable(Throwable e) {
        // 第一层：用户错误永远不重试（复用已有 FallbackEligibility）
        if (!fallbackEligibility.isEligible(e)) {
            return false;
        }
        // 第二层：配置的可重试异常类
        return retryableExceptions.stream()
            .anyMatch(clazz -> clazz.isInstance(e));
    }

    /**
     * 判断异常是否可触发跨模型降级
     * <p>
     * 比 isRetryable 更宽松：只要是 FallbackEligible 的异常就可以降级，
     * 即使不在 retryableExceptions 列表中（如 ModelNotFoundException）。
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
                    client.compositeId(), e.getMessage());
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

        return buildStreamChain(chain, action, 0);
    }

    private <T extends CapabilityClient> Flux<String> buildStreamChain(
            List<T> chain,
            Function<T, Flux<String>> action,
            int index) {

        if (index >= chain.size()) {
            return Flux.error(new RemoteException(RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用（流式降级链耗尽）"));
        }

        T client = chain.get(index);
        if (!client.isAvailable()) {
            return buildStreamChain(chain, action, index + 1);
        }

        return action.apply(client)
            .onErrorResume(e -> {
                // 用户错误不降级，直接向下游传播
                if (!fallbackEligibility.isEligible(e)) {
                    return Flux.error(e);
                }
                log.warn("Stream client '{}' failed at index {}, falling back to next: {}",
                    client.compositeId(), index, e.getMessage());
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

import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.ModelCircuitOpenException;

import java.util.function.Supplier;

/**
 * 熔断器适配器 — 包装已有的 {@link ModelCircuitBreakerRegistry}
 * <p>
 * 复用已有三态熔断器实现（CLOSED → OPEN → HALF_OPEN），
 * 新增 {@code execute()} / {@code executeStream()} 高层方法，
 * 供 Resilient 装饰器统一调用。
 * <p>
 * <b>为什么包装而不重写</b>：
 * <ul>
 *   <li>已有实现经过生产验证（synchronized 状态机、Clock 注入可测试、releaseProbe 支持）</li>
 *   <li>已有 {@code ProbeStreamHandler} 和 {@code StreamRetryHandler} 已集成此注册表</li>
 *   <li>新架构只需在已有基础上加 execute/executeStream 语义</li>
 * </ul>
 */
public class CircuitBreaker {

    private final ModelCircuitBreakerRegistry registry;
    private final String compositeId;

    public CircuitBreaker(ModelCircuitBreakerRegistry registry, String compositeId) {
        this.registry = registry;
        this.compositeId = compositeId;
    }

    /**
     * 阻塞式执行（带熔断保护）
     * <p>
     * OPEN → 抛出 {@link ModelCircuitOpenException}（已有异常类型）
     * HALF_OPEN → 放行（由已有 halfOpenMaxProbes 控制并发数）
     */
    public <T> T execute(Supplier<T> action) throws Exception {
        if (!registry.isCallAllowed(compositeId)) {
            throw new ModelCircuitOpenException(compositeId);
        }
        try {
            T result = action.get();
            registry.recordSuccess(compositeId);
            return result;
        } catch (Exception e) {
            registry.recordFailure(compositeId);
            throw e;
        }
    }

    /**
     * 流式执行（带熔断保护）
     * <p>
     * 订阅时检查状态，流结束时更新计数。
     * HALF_OPEN 下的探测流由已有 {@code releaseProbe()} 管理。
     */
    public <T> Flux<T> executeStream(Supplier<Flux<T>> streamSupplier) {
        if (!registry.isCallAllowed(compositeId)) {
            return Flux.error(new ModelCircuitOpenException(compositeId));
        }
        return Flux.defer(streamSupplier)
            .doOnComplete(() -> {
                registry.recordSuccess(compositeId);
                registry.releaseProbe(compositeId);
            })
            .doOnError(__ -> {
                registry.recordFailure(compositeId);
                registry.releaseProbe(compositeId);
            })
            .doOnCancel(() -> {
                // 客户端主动断开不算失败
                registry.releaseProbe(compositeId);
            });
    }

    /** 当前状态（委托给已有实现） */
    public CircuitBreakerState getState() {
        return registry.stateOf(compositeId);
    }
}
```

### 7.5 `CircuitBreakerRegistry` — 熔断器注册表（包装已有）

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;

/**
 * 按 compositeId 管理的熔断器注册表 — 包装已有 {@link ModelCircuitBreakerRegistry}
 * <p>
 * 不创建新的熔断器实例，而是为每个 compositeId 创建 {@link CircuitBreaker} 适配器，
 * 底层委托给已有的 {@link ModelCircuitBreakerRegistry}。
 */
@Component
public class CircuitBreakerRegistry {

    private final ModelCircuitBreakerRegistry delegate;
    private final ConcurrentHashMap<String, CircuitBreaker> adapters = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(ModelCircuitBreakerRegistry delegate) {
        this.delegate = delegate;
    }

    /**
     * 获取或创建指定 compositeId 的熔断器适配器
     */
    public CircuitBreaker getOrCreate(String compositeId) {
        return adapters.computeIfAbsent(compositeId,
            id -> new CircuitBreaker(delegate, id));
    }
}
```


### 7.7 `ProbeHandler` — 首包探测处理器（包装已有基础设施）

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
 *   <li>同一 compositeId 的并发探测共享同一个探测结果，避免重复探测</li>
 *   <li>探测结果（成功/失败 + 延迟）写入 Redis 健康缓存</li>
 * </ul>
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

    public ProbeHandler(ProbeStreamHandler delegate,
                        @Nullable SharedProbeRegistry probeRegistry) {
        this.delegate = delegate;
        this.probeRegistry = probeRegistry;
    }

    /**
     * 包装 Flux，添加首包超时检测 + 探测去重
     *
     * @param compositeId 用于日志、熔断记录、探测去重 key
     * @param raw         原始流式响应
     * @return 带首包探测的 Flux
     */
    public Flux<String> wrap(String compositeId, Flux<String> raw) {
        // 探测去重：如果已有同模型的探测在飞，等待其结果
        if (probeRegistry != null) {
            CompletableFuture<ProbeResult> inFlight = probeRegistry.getInFlight(compositeId);
            if (inFlight != null) {
                return Mono.fromFuture(() -> inFlight)
                    .timeout(Duration.ofSeconds(probeTimeoutSeconds))
                    .flatMapMany(result -> result.success() ? raw : Flux.error(
                        new ProbeTimeoutException(compositeId)));
            }
        }
        // 委托给已有的 ProbeStreamHandler（内部已集成 breakers.recordFailure）
        return delegate.wrapWithProbe(compositeId, raw);
    }
}
```



### 7.8 Resilient 装饰器

> **设计决策**：装饰器**只实现能力接口**（`ChatCapable` / `EmbeddingCapable` / `RerankCapable`），
> **不继承**被装饰者的抽象类。纯组合 + 委托，避免继承+委托的双重关系。
> 调用方通过接口交互，无法区分原始客户端和弹性包装——这正是装饰器的透明性。

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.*;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Set;

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
    @Override public String compositeId() { return delegate.compositeId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelId() { return delegate.modelId(); }
    @Override public Set<LlmCapability> capabilities() { return delegate.capabilities(); }
    @Override public boolean isAvailable() { return delegate.isAvailable(); }
    @Override public ModelSpec modelSpec() { return delegate.modelSpec(); }

    // ======== Chat 操作（带弹性包装） ========

    @Override
    public ChatResponse chat(ChatRequest request) {
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                delegate.chat(request)
            )
        );
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        return circuitBreaker.executeStream(() ->
            retryPolicy.retryStream(() -> {
                Flux<String> raw = delegate.chatStream(request);
                // ProbeHandler 包装：首包超时时抛出 ProbeTimeoutException，
                // 该异常是 retryable 的，retryStream 会重试整个 Flux。
                return probeHandler != null
                    ? probeHandler.wrap(compositeId(), raw)
                    : raw;
            })
        );
    }


    @Override
    public ChatResponse chatWithTools(ChatRequest request, List<Object> tools) {
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
    @Override public String compositeId() { return delegate.compositeId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelId() { return delegate.modelId(); }
    @Override public Set<LlmCapability> capabilities() { return delegate.capabilities(); }
    @Override public boolean isAvailable() { return delegate.isAvailable(); }
    @Override public ModelSpec modelSpec() { return delegate.modelSpec(); }

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
    @Override public String compositeId() { return delegate.compositeId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelId() { return delegate.modelId(); }
    @Override public Set<LlmCapability> capabilities() { return delegate.capabilities(); }
    @Override public boolean isAvailable() { return delegate.isAvailable(); }
    @Override public ModelSpec modelSpec() { return delegate.modelSpec(); }

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

/**
 * 通用 OpenAI 兼容 Provider — 单个供应商实例
 * <p>
 * 每个 YAML 配置中的 `type: openai-compat` 条目对应一个此实例。
 * 不需要为 DeepSeek / MiniMax / SiliconFlow 等分别写实现类。
 * <p>
 * 处理的模型类型：
 * <ul>
 *   <li>CHAT / CHAT_STREAM → Spring AI ChatModel（OpenAI 兼容）</li>
 *   <li>EMBEDDING → Spring AI EmbeddingModel（OpenAI 兼容）</li>
 *   <li>RERANKING → RestClient 调用 /reranks 端点</li>
 * </ul>
 */
public class GenericOpenAiProvider implements LlmProvider {

    private final ProviderConfig config;
    private final Map<String, ModelSpec> models;
    private final Map<String, CapabilityClient> clients;

    public GenericOpenAiProvider(ProviderConfig config) {
        this.config = config;
        this.models = config.models().stream()
            .filter(ModelSpec::enabled)
            .collect(Collectors.toMap(ModelSpec::compositeId, Function.identity()));

        this.clients = new HashMap<>();
        for (ModelSpec model : models.values()) {
            for (LlmCapability cap : model.capabilities()) {
                clients.put(cap + ":" + model.compositeId(),
                    createClient(config, model, cap));
            }
        }
    }

    private CapabilityClient createClient(ProviderConfig config,
                                           ModelSpec model,
                                           LlmCapability cap) {
        return switch (cap) {
            case CHAT, CHAT_STREAM ->
                new GenericChatClient(config.baseUrl(), config.apiKey(), model);
            case EMBEDDING ->
                new GenericEmbeddingClient(config.baseUrl(), config.apiKey(), model);
            case RERANKING ->
                new GenericRerankClient(config.baseUrl(), config.apiKey(), model);
        };
    }

    @Override
    public String id() { return config.id(); }

    @Override
    public String name() { return config.name(); }

    @Override
    public List<ModelSpec> models() {
        return List.copyOf(models.values());
    }

    @Override
    public boolean isAvailable() {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }

    @Override
    public Optional<CapabilityClient> getClient(String compositeId) {
        // 返回第一个匹配的客户端（一个 compositeId 可能有多种能力）
        return clients.values().stream()
            .filter(c -> c.compositeId().equals(compositeId))
            .findFirst();
    }
}
```

**多供应商注册器**（为每个 `type: openai-compat` 配置创建独立 Bean）：

```java
package com.smart.rag.infrastructure.llm.provider.generic;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.Configuration;

/**
 * 通用 OpenAI 兼容 Provider 注册器
 * <p>
 * 扫描 YAML 中所有 {@code type: openai-compat} 的供应商配置，
 * 为每个创建独立的 {@link GenericOpenAiProvider} 实例并注册为独立 Bean。
 * <p>
 * <b>为什么不用 {@code @Bean List<LlmProvider>}</b>：
 * <ul>
 *   <li>{@code @Bean List<LlmProvider>} 注册的是一个 Bean（类型为 List），</li>
 *   <li>当其他 {@code @Component} Provider（如 DashScopeProvider）也存在时，</li>
 *   <li>Spring 注入 {@code List<LlmProvider>} 会合并两者，导致类型冲突或重复注入。</li>
 * </ul>
 * 使用 {@code BeanDefinitionRegistryPostProcessor} 将每个 Provider 注册为独立 Bean，
 * Spring 自动收集所有 {@code LlmProvider} 类型的 Bean 注入 {@code List<LlmProvider>}。
 */
@Configuration
public class GenericOpenAiProviderRegistrar implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        // 读取 YAML 配置（通过 Binder 而非 @ConfigurationProperties，因为此阶段 Bean 尚未就绪）
        // 实际实现中通过 Environment 获取配置，此处简化展示核心逻辑
        for (ProviderConfig config : loadOpenAiCompatConfigs()) {
            GenericBeanDefinition bd = new GenericBeanDefinition();
            bd.setBeanClass(GenericOpenAiProvider.class);
            bd.getConstructorArgumentValues().addIndexedArgumentValue(0, config);
            registry.registerBeanDefinition(
                "llmProvider_" + config.id(), bd);
        }
    }

    private List<ProviderConfig> loadOpenAiCompatConfigs() {
        // 从 Environment 读取 app.llm.providers，过滤 type=openai-compat
        // 实际实现使用 Binder.bind("app.llm", LlmProperties.class)
        ...
    }
}
```

> **替代方案**（更简单，推荐先用）：如果不想引入 `BeanDefinitionRegistryPostProcessor`，
> 可以保留 `@Bean List<LlmProvider>`，但需要在 `LlmClientRegistry` 的构造函数中
> 手动合并 `@Bean` 返回的列表和 `@Component` 注入的单个 Provider：
> ```java
> public LlmClientRegistry(
>         List<LlmProvider> componentProviders,      // @Component 注入
>         @Qualifier("genericProviders") List<LlmProvider> genericProviders,  // @Bean 注入
>         ...) {
>     List<LlmProvider> all = new ArrayList<>(componentProviders);
>     all.addAll(genericProviders);
>     // ... 后续逻辑
> }
> ```


### 8.2 自定义 Provider 示例（DashScope）

```java
package com.smart.rag.infrastructure.llm.provider.dashscope;

/**
 * DashScope Provider — 混合模式
 * <p>
 * - Embedding 模型 → 自定义 DashScopeEmbeddingClient（非标准 REST API）
 * - Chat 模型 → GenericChatClient（DashScope 的 Chat API 是 OpenAI 兼容的）
 */
@Component
public class DashScopeProvider implements LlmProvider {

    private final DashScopeProperties props;
    private final Map<String, CapabilityClient> clients;

    public DashScopeProvider(DashScopeProperties props) {
        this.props = props;
        this.clients = new HashMap<>();

        for (ModelSpec model : props.models()) {
            if (model.modelId().startsWith("text-embedding")) {
                // 非标准 API，需要自定义 Client
                clients.put(model.compositeId(),
                    new DashScopeEmbeddingClient(props, model));
            } else {
                // OpenAI 兼容，复用通用 Client
                clients.put(model.compositeId(),
                    new GenericChatClient(props.baseUrl(), props.apiKey(), model));
            }
        }
    }

    @Override
    public String id() { return "dashscope"; }

    @Override
    public String name() { return "DashScope"; }

    @Override
    public List<ModelSpec> models() { return props.models(); }

    @Override
    public boolean isAvailable() {
        return props.apiKey() != null && !props.apiKey().isBlank();
    }

    @Override
    public Optional<CapabilityClient> getClient(String compositeId) {
        return Optional.ofNullable(clients.get(compositeId));
    }
}
```

```java
package com.smart.rag.infrastructure.llm.provider.dashscope;

/**
 * DashScope Embedding Client — 非标准 REST API
 * <p>
 * DashScope 的 Embedding API 不是 OpenAI 兼容格式，需要自定义实现。
 * 复用现有 DashScopeEmbeddingModel 的 API 调用逻辑，但不包含重试——
 * 由 ResilientEmbeddingClient 装饰器统一处理。
 */
public class DashScopeEmbeddingClient extends AbstractEmbeddingClient {

    private static final String EMBEDDING_PATH =
        "/api/v1/services/embeddings/text-embedding/text-embedding";

    private final RestClient restClient;
    private final DashScopeEmbeddingProperties props;

    public DashScopeEmbeddingClient(DashScopeProperties providerProps,
                                     ModelSpec model) {
        super(model, "dashscope");
        this.props = providerProps.embedding();
        this.restClient = RestClient.builder()
            .baseUrl(providerProps.baseUrl())
            .defaultHeader("Authorization", "Bearer " + providerProps.apiKey())
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    // capabilities() 由基类 AbstractEmbeddingClient 从 ModelSpec 统一提供，无需覆写

    @Override
    public float[] embed(String text, EmbeddingType type) {
        Map<String, Object> requestBody = Map.of(
            "model", modelId(),
            "input", Map.of(
                "texts", List.of(text),
                "text_type", type == EmbeddingType.QUERY ? "query" : "document"
            ),
            "parameters", Map.of(
                "dimension", dimensions()
            )
        );

        Map<String, Object> response = restClient.post()
            .uri(EMBEDDING_PATH)
            .body(requestBody)
            .retrieve()
            .body(Map.class);

        // 解析响应（复用现有逻辑）
        return extractEmbedding(response);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        // DashScope 支持批量，覆写默认逐条调用
        int batchSize = (int) spec.defaultParams()
            .getOrDefault("batchSize", 10);
        List<float[]> results = new ArrayList<>();

        for (List<String> batch : partition(texts, batchSize)) {
            float[][] batchResult = callBatchApi(batch, type);
            Collections.addAll(results, batchResult);
        }
        return results;
    }

    @Override
    public int dimensions() {
        return (int) spec.defaultParams()
            .getOrDefault("dimension", 1024);
    }

    @Override
    public boolean isAvailable() {
        return props.apiKey() != null && !props.apiKey().isBlank();
    }
}
```

### 8.3 自定义 Provider 示例（Bailian Rerank）

```java
package com.smart.rag.infrastructure.llm.provider.bailian;

/**
 * Bailian Rerank Provider — 仅提供 Rerank 能力
 */
@Component
public class BailianProvider implements LlmProvider {

    private final BailianProperties props;
    private final BailianRerankClient rerankClient;

    public BailianProvider(BailianProperties props) {
        this.props = props;
        ModelSpec rerankModel = new ModelSpec(
            props.rerank().model(),
            "bailian:" + props.rerank().model(),
            "Bailian Rerank",
            Set.of(LlmCapability.RERANKING),
            Map.of("timeout", Duration.ofSeconds(15)),
            props.rerank().priority(),
            true
        );
        this.rerankClient = new BailianRerankClient(props, rerankModel);
    }

    @Override
    public String id() { return "bailian"; }

    @Override
    public String name() { return "Bailian"; }

    @Override
    public List<ModelSpec> models() { return List.of(rerankClient.modelSpec()); }

    @Override
    public boolean isAvailable() {
        return props.apiKey() != null && !props.apiKey().isBlank();
    }

    @Override
    public Optional<CapabilityClient> getClient(String compositeId) {
        if (rerankClient.compositeId().equals(compositeId)) {
            return Optional.of(rerankClient);
        }
        return Optional.empty();
    }
}
```

---

## 9. Registry — 统一发现 + 注册 + 装饰

```java
package com.smart.rag.infrastructure.llm.registry;

/**
 * LLM 客户端注册表
 * <p>
 * 启动时扫描所有 {@link LlmProvider} Bean，构建三个维度的索引，
 * 并统一包装 Resilient 装饰器。
 * <p>
 * 注册表示例（以当前供应商配置为例）：
 * <pre>
 * compositeId                        │ Client 类型                 │ Capabilities
 * ───────────────────────────────────┼────────────────────────────┼───────────────
 * deepseek:deepseek-v4-flash         │ GenericChatClient           │ CHAT, CHAT_STREAM
 * deepseek:deepseek-v4-pro           │ GenericChatClient           │ CHAT, CHAT_STREAM
 * zhipu:glm-5.1                      │ GenericChatClient           │ CHAT, CHAT_STREAM
 * zhipu:glm-4-flash                  │ GenericChatClient           │ CHAT, CHAT_STREAM
 * minimax:minimax-text-01            │ GenericChatClient           │ CHAT, CHAT_STREAM
 * dashscope:text-embedding-v4        │ DashScopeEmbeddingClient    │ EMBEDDING
 * dashscope:qwen-max                 │ GenericChatClient           │ CHAT, CHAT_STREAM
 * bailian:qwen3-rerank               │ BailianRerankClient         │ RERANKING
 * </pre>
 */
@Component
public class LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    /** compositeId → 该 ID 下的所有 CapabilityClient（不可变，含 Resilient 包装） */
    private final Map<String, List<CapabilityClient>> clientsByCompositeId;

    /** LlmCapability → 按 priority 排序的客户端列表（不可变） */
    private final Map<LlmCapability, List<CapabilityClient>> clientsByCapability;

    /** LlmCapability → Fallback Chain（不可变，含 Resilient 包装） */
    private final Map<LlmCapability, List<CapabilityClient>> fallbackChains;

    public LlmClientRegistry(
            List<LlmProvider> providers,
            FallbackChainConfig fallbackConfig,
            CircuitBreakerRegistry circuitBreakers,
            RetryPolicy retryPolicy,
            ProbeHandler probeHandler) {

        // ====== 1. 从所有 Provider 收集所有客户端 ======
        List<CapabilityClient> allClients = providers.stream()
            .filter(LlmProvider::isAvailable)
            .peek(p -> log.info("Discovered LLM provider: {} ({})", p.name(), p.id()))
            .flatMap(p -> p.models().stream()
                .filter(ModelSpec::enabled)
                .map(model -> p.getClient(model.compositeId()))
                .filter(Optional::isPresent)
                .map(Optional::get))
            .toList();

        log.info("Total LLM clients registered: {}", allClients.size());

        // ====== 2. 构建 compositeId 索引（原始，用于 wrapWithResilience 输入） ======
        Map<String, List<CapabilityClient>> rawByCompositeId = allClients.stream()
            .collect(Collectors.groupingBy(CapabilityClient::compositeId));

        // ====== 3. 构建能力类型索引（按 priority 排序） ======
        this.clientsByCapability = Collections.unmodifiableMap(
            allClients.stream()
                .flatMap(c -> c.capabilities().stream()
                    .map(cap -> Map.entry(cap, c)))
                .collect(Collectors.groupingBy(
                    Map.Entry::getKey,
                    () -> new EnumMap<>(LlmCapability.class),
                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ))
        );
        clientsByCapability.values().forEach(
            list -> list.sort(Comparator.comparingInt(
                c -> c.modelSpec().priority()))
        );

        // ====== 4. 构建 Fallback Chain（原始引用，包装前） ======
        Map<LlmCapability, List<CapabilityClient>> rawFallbackChains =
            buildFallbackChains(fallbackConfig, clientsByCapability);

        // ====== 5. 统一包装 Resilient 装饰器（构建新 Map，不修改原始） ======
        Map<String, List<CapabilityClient>> wrappedClients =
            wrapWithResilience(rawByCompositeId, circuitBreakers, retryPolicy, probeHandler);
        this.clientsByCompositeId = Collections.unmodifiableMap(wrappedClients);

        // ====== 6. 用包装后的客户端解析 Fallback Chain 引用 ======
        for (Map.Entry<LlmCapability, List<CapabilityClient>> entry : rawFallbackChains.entrySet()) {
            List<CapabilityClient> resolved = entry.getValue().stream()
                .map(c -> wrappedClients.getOrDefault(c.compositeId(), List.of(c))
                    .stream().findFirst().orElse(c))
                .toList();
            resolvedChains.put(entry.getKey(), resolved);
        }
        this.fallbackChains = Collections.unmodifiableMap(resolvedChains);
    }

    // ==================== 对外查询 API ====================

    /**
     * 按 compositeId 获取所有能力客户端（包内可见，非公开 API）
     * <p>
     * <b>调用方应优先使用 {@link #get(String, Class)}</b>，
     * 该方法暴露内部索引结构，仅用于 Registry 内部构建和调试场景。
     */
    List<CapabilityClient> getAll(String compositeId) {
        return clientsByCompositeId.getOrDefault(compositeId, List.of());
    }

    /**
     * 按 compositeId 获取所有匹配指定能力类型的客户端
     *
     * @param compositeId 格式 "{providerId}:{modelId}"
     * @param type        能力类型（如 ChatCapable.class）
     * @return 匹配的客户端列表（可能为空）
     */
    public <T extends CapabilityClient> List<T> getAll(String compositeId, Class<T> type) {
        return clientsByCompositeId.getOrDefault(compositeId, List.of()).stream()
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    /**
     * 按 compositeId + 能力类型精确获取（推荐的公开 API）
     *
     * @param compositeId 格式 "{providerId}:{modelId}"
     * @param type        能力类型（如 EmbeddingCapable.class）
     * @return 匹配的客户端，不存在时返回 empty
     */
    public <T extends CapabilityClient> Optional<T> get(String compositeId, Class<T> type) {
        return clientsByCompositeId.getOrDefault(compositeId, List.of()).stream()
            .filter(type::isInstance)
            .map(type::cast)
            .findFirst();
    }


    /**
     * 获取某个能力的 Fallback Chain（已按优先级排序）
     */
    public List<CapabilityClient> getFallbackChain(LlmCapability capability) {
        return fallbackChains.getOrDefault(capability, List.of());
    }

    /**
     * 获取所有 Chat 客户端
     */
    public List<ChatCapable> getChatClients() {
        return getByType(ChatCapable.class, LlmCapability.CHAT);
    }

    /**
     * 获取所有 Embedding 客户端
     */
    public List<EmbeddingCapable> getEmbeddingClients() {
        return getByType(EmbeddingCapable.class, LlmCapability.EMBEDDING);
    }

    /**
     * 获取所有 Rerank 客户端
     */
    public List<RerankCapable> getRerankClients() {
        return getByType(RerankCapable.class, LlmCapability.RERANKING);
    }

    // ==================== 内部方法 ====================

    private <T extends CapabilityClient> List<T> getByType(
            Class<T> type, LlmCapability cap) {
        return clientsByCapability.getOrDefault(cap, List.of()).stream()
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    private Map<LlmCapability, List<CapabilityClient>> buildFallbackChains(
            FallbackChainConfig config,
            Map<LlmCapability, List<CapabilityClient>> allByCapability) {

        Map<LlmCapability, List<CapabilityClient>> chains = new EnumMap<>(LlmCapability.class);

        for (LlmCapability cap : LlmCapability.values()) {
            List<String> configuredOrder = config.getOrder(cap);
            List<CapabilityClient> allAvailable = allByCapability.getOrDefault(cap, List.of());

            if (configuredOrder != null && !configuredOrder.isEmpty()) {
                // 配置优先：按配置顺序排列，配置中不存在的按 priority 追加
                List<CapabilityClient> chain = new ArrayList<>();
                Set<String> added = new HashSet<>();

                for (String compositeId : configuredOrder) {
                    allAvailable.stream()
                        .filter(c -> c.compositeId().equals(compositeId))
                        .filter(CapabilityClient::isAvailable)
                        .findFirst()
                        .ifPresent(client -> {
                            chain.add(client);
                            added.add(compositeId);
                        });
                }
                // 追加未在配置中但可用的客户端
                allAvailable.stream()
                    .filter(c -> !added.contains(c.compositeId()))
                    .filter(CapabilityClient::isAvailable)
                    .forEach(chain::add);

                chains.put(cap, chain);
            } else {
                // 未配置：全部按 priority 排列
                chains.put(cap, allAvailable.stream()
                    .filter(CapabilityClient::isAvailable)
                    .toList());
            }
        }
        return chains;
    }

    private Map<String, List<CapabilityClient>> wrapWithResilience(
            Map<String, List<CapabilityClient>> rawClients,
            CircuitBreakerRegistry circuitBreakers,
            RetryPolicy retryPolicy,
            ProbeHandler probeHandler) {

        Map<String, List<CapabilityClient>> wrapped = new HashMap<>();

        for (Map.Entry<String, List<CapabilityClient>> entry : rawClients.entrySet()) {
            List<CapabilityClient> entryWrapped = new ArrayList<>();
            for (CapabilityClient client : entry.getValue()) {
                CircuitBreaker cb = circuitBreakers.getOrCreate(client.compositeId());

                CapabilityClient resilient = switch (client) {
                    case ChatCapable chat ->
                        new ResilientChatClient(chat, cb, retryPolicy, probeHandler);
                    case EmbeddingCapable embed ->
                        new ResilientEmbeddingClient(embed, cb, retryPolicy);
                    case RerankCapable rerank ->
                        new ResilientRerankClient(rerank, cb, retryPolicy);
                    default -> client;
                };
                entryWrapped.add(resilient);
            }
            wrapped.put(entry.getKey(), entryWrapped);
        }
        return wrapped;
    }
}
```


> **辅助类型定义**：

```java
package com.smart.rag.infrastructure.llm.config;

/**
 * Fallback Chain 配置
 * <p>
 * 映射 YAML 中 {@code app.llm.fallback} 的配置。
 * 每个 {@link LlmCapability} 对应一个有序的 compositeId 列表，
 * 未配置的能力类型返回空列表（由 Registry 按 priority 自动填充）。
 *
 * <pre>
 * YAML 示例：
 * fallback:
 *   CHAT:
 *     - deepseek:deepseek-v4-flash
 *     - minimax:minimax-text-01
 *   EMBEDDING:
 *     - dashscope:text-embedding-v4
 * </pre>
 */
public record FallbackChainConfig(
    Map<LlmCapability, List<String>> chains
) {
    /**
     * 获取指定能力的 Fallback Chain 顺序
     *
     * @param cap 能力类型
     * @return compositeId 列表（按优先级排序），未配置时返回空列表
     */
    public List<String> getOrder(LlmCapability cap) {
        return chains.getOrDefault(cap, List.of());
    }
}
```

---

## 10. 配置体系

### 10.1 完整 YAML 结构

```yaml
app:
  llm:
    # ==================== 供应商定义 ====================
    providers:
      - id: deepseek
        type: openai-compat
        name: DeepSeek
        baseUrl: https://api.deepseek.com/v1
        apiKey: ${DEEPSEEK_API_KEY}
        models:
          - modelId: deepseek-v4-flash
            displayName: DeepSeek V4 Flash
            capabilities: [CHAT, CHAT_STREAM]
            priority: 10
            params:
              temperature: 0.7
              maxTokens: 4096
          - modelId: deepseek-v4-pro
            displayName: DeepSeek V4 Pro
            capabilities: [CHAT, CHAT_STREAM]
            priority: 20
            params:
              temperature: 0.7
              maxTokens: 8192

      - id: zhipu
        type: openai-compat
        name: ZhiPu AI
        baseUrl: https://open.bigmodel.cn/api/paas/v4
        apiKey: ${ZHIPU_API_KEY}
        models:
          - modelId: glm-5.1
            displayName: GLM-5.1
            capabilities: [CHAT, CHAT_STREAM]
            priority: 15
          - modelId: glm-4-flash
            displayName: GLM-4 Flash
            capabilities: [CHAT, CHAT_STREAM]
            priority: 25

      - id: minimax
        type: openai-compat
        name: MiniMax
        baseUrl: https://api.minimax.chat/v1
        apiKey: ${MINIMAX_API_KEY}
        models:
          - modelId: minimax-text-01
            displayName: MiniMax Text 01
            capabilities: [CHAT, CHAT_STREAM]
            priority: 30

      - id: dashscope
        type: custom
        className: com.smart.rag.infrastructure.llm.provider.dashscope.DashScopeProvider
        name: DashScope
        baseUrl: https://dashscope.aliyuncs.com
        apiKey: ${DASHSCOPE_API_KEY}
        models:
          - modelId: text-embedding-v4
            displayName: 通义向量 V4
            capabilities: [EMBEDDING]
            priority: 10
            params:
              dimension: 1024
              batchSize: 10
          - modelId: qwen-max
            displayName: 通义千问 Max
            capabilities: [CHAT, CHAT_STREAM]
            priority: 15

      - id: bailian
        type: custom
        className: com.smart.rag.infrastructure.llm.provider.bailian.BailianProvider
        name: Bailian
        baseUrl: https://dashscope.aliyuncs.com
        apiKey: ${BAILIAN_API_KEY}
        models:
          - modelId: qwen3-rerank
            displayName: Qwen3 Rerank
            capabilities: [RERANKING]
            priority: 10

      - id: siliconflow
        type: openai-compat
        name: SiliconFlow
        baseUrl: https://api.siliconflow.cn/v1
        apiKey: ${SILICONFLOW_API_KEY}
        models:
          - modelId: BAAI/bge-large-zh-v1.5
            displayName: BGE Large 中文
            capabilities: [EMBEDDING]
            priority: 20
          - modelId: Qwen/Qwen3-Rerank
            displayName: Qwen3 Rerank
            capabilities: [RERANKING]
            priority: 20

    # ==================== Fallback Chain 配置 ====================
    fallback:
      CHAT:
        - deepseek:deepseek-v4-flash
        - minimax:minimax-text-01
        - zhipu:glm-4-flash
      EMBEDDING:
        - dashscope:text-embedding-v4
        - siliconflow:BAAI/bge-large-zh-v1.5
      RERANKING:
        - siliconflow:Qwen/Qwen3-Rerank
        - bailian:qwen3-rerank

    # ==================== 弹性策略配置 ====================
    resilience:
      retry:
        maxAttempts: 3
        baseDelayMs: 500
        maxDelayMs: 5000
        multiplier: 2.0
        retryableExceptions:
          - java.net.SocketTimeoutException
          - java.io.IOException
          - com.smart.rag.infrastructure.llm.LlmTransientException

      circuitBreaker:
        failureThreshold: 5
        openDurationMs: 30000
        halfOpenMaxCalls: 2

      probe:
        firstPacketTimeoutMs: 3000
        enabled: true
```

### 10.2 配置 Properties 类

```java
package com.smart.rag.infrastructure.llm.config;

@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
    List<ProviderConfig> providers,
    FallbackChainConfig fallback,
    ResilienceConfig resilience
) {}

public record ProviderConfig(
    String id,
    String type,          // "openai-compat" | "custom"
    String className,     // custom 类型时的全限定类名
    String name,
    String baseUrl,
    String apiKey,
    List<ModelSpec> models
) {}

public record ResilienceConfig(
    RetryProperties retry,
    CircuitBreakerProperties circuitBreaker,
    ProbeProperties probe
) {}

public record RetryProperties(
    int maxAttempts,
    long baseDelayMs,
    long maxDelayMs,
    double multiplier,
    Set<String> retryableExceptions
) {}

public record CircuitBreakerProperties(
    int failureThreshold,
    long openDurationMs,
    int halfOpenMaxCalls
) {}

public record ProbeProperties(
    long firstPacketTimeoutMs,
    boolean enabled
) {}
```

---

## 11. 异常体系（复用已有层次结构）

> **设计决策**：不新建 `LlmException`，所有 LLM 弹性层异常**继承已有的 `RemoteException`（C类）**。
> 已有异常体系按 A/B/C 三类划分，LLM 调用失败属于"第三方服务错误"，天然属于 C 类。

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
        ├── ModelCircuitOpenException   ← 熔断器打开（301002, 已重构为 RemoteException）
        ├── ProbeTimeoutException       ← 首包探测超时（301003, 已重构为 RemoteException）
        │
        └── [FallbackExecutor 抛出]
            └── RemoteException(LLM_ALL_MODELS_FAILED) ← 链耗尽（301001）


### 11.2 新增 `RemoteErrorCode` 条目

```java
// RemoteErrorCode.java — 新增 301xxx 段
LLM_ALL_MODELS_FAILED(301001, "所有模型均不可用"),
LLM_CIRCUIT_BREAKER_OPEN(301002, "模型熔断器已打开，请稍后重试"),
LLM_PROBE_TIMEOUT(301003, "模型首包探测超时"),
LLM_RATE_LIMITED(301004, "模型调用频率超限"),
LLM_CONFIG_ERROR(301005, "LLM 配置错误"),
LLM_PROVIDER_UNAVAILABLE(301006, "模型厂商不可用"),
LLM_RESPONSE_TRUNCATED(301007, "模型响应被截断"),
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

> 所有 C 类异常（`RemoteException` 及其子类）均通过 `FallbackEligibility.isEligible()` 返回 `true`，可触发跨模型降级。

### 11.4 `ProbeTimeoutException` / `ModelCircuitOpenException` — 已纳入 RemoteException 体系

这两个异常**已重构为 `RemoteException` 子类**，统一在 C 类异常体系内：

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
```

**兼容性**：
- `StreamRetryHandler` 中的 `instanceof ProbeTimeoutException` / `instanceof ModelCircuitOpenException` 检查不受影响（类本身未变，只是父类变了）
- `FallbackEligibility.isEligible()` 对 `RemoteException` 返回 `true`，这两个异常自动可降级
- `GlobalExceptionHandler` 会将它们作为 `RemoteException` 统一处理（携带 `RemoteErrorCode`，返回 C 类错误码给前端）

**统一后的异常层次**：
```
AbstractException
├── ClientException (A类)     ← 不可降级
├── ServiceException (B类)    ← 不可降级
└── RemoteException (C类)     ← 可降级
    ├── ProviderNotFoundException     (300001)
    ├── ProbeTimeoutException         (301003) ← 已纳入
    ├── ModelCircuitOpenException     (301002) ← 已纳入
    └── FallbackExecutor 抛出的       (301001) LLM_ALL_MODELS_FAILED
```



---

## 12. 调用方迁移

### 12.1 迁移对照表

| 调用方 | 迁移前 | 迁移后 | 复杂度 |
|--------|--------|--------|--------|
| `IntentClassifier` | `ChatClientRegistry.get(id)` + 自写 for-loop 2 次 | `registry.get(id, ChatCapable.class).chat(request)` | ★ |
| `LlmJudgeImpl` | 构造注入 `ChatClient` + 自写 for-loop 2 次 | `registry.get(id, ChatCapable.class).chat(request)` | ★ |
| `DatasetGenerator` | Spring AI `ChatClient.Builder` 直接注入 | `registry.get(id, ChatCapable.class).chat(request)` | ★ |
| `RagConfig`（query rewrite） | `RewriteQueryTransformer` 内部 ChatClient.Builder | `registry.get(id, ChatCapable.class).chat(request)` | ★★ |
| `DashScopeEmbeddingModel` | 自己实现 RestClient + 3x 重试 + 零向量降级 | `registry.get("dashscope:text-embedding-v4", EmbeddingCapable.class).embed(text, type)` | ★★ |
| `BailianRerankPostProcessor` | 自己实现 RestClient + 3x 重试 + 线程池 + 降级 | `registry.get("bailian:qwen3-rerank", RerankCapable.class).rerank(request)` | ★★ |
| `AgentModeStrategy` | `ChatClientRegistry` + `TokenCountingChatModel` 包装 | `registry.get(id, ChatCapable.class).chatWithTools(request, tools)` | ★★★ |
| `ChatServiceImpl`（核心路径） | 15 个构造参数，自己编排 fallback + circuit breaker + probe | 由 `FallbackExecutor` + `ResilientChatClient` 处理 | ★★★★ |

### 12.2 迁移顺序（风险从低到高）

```
Phase 1（低风险，消除散落重试）:
  1. IntentClassifier        ← 最简单，消除自写 retry
  2. LlmJudgeImpl            ← 最简单
  3. DatasetGenerator        ← 最简单

Phase 2（中风险，统一非 Chat 调用）:
  4. DashScopeEmbeddingModel ← 消除绕路 RestClient
  5. BailianRerankPostProcessor ← 消除绕路 RestClient
  6. RagConfig (query rewrite) ← 中等复杂度

Phase 3（高风险，核心路径）:
  7. ChatServiceImpl (streaming) ← 保留旧路径可灰度切换
  8. ChatServiceImpl (blocking)  ← 最后迁移
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
     * 阻塞式对话（含跨模型降级）
     * <p>
     * 执行链：FallbackExecutor → ResilientChatClient → CircuitBreaker → RetryPolicy → 原始 Client
     */
    public ChatResponse chat(String modelId, ChatRequest request) {
        List<ChatCapable> chain = registry.getFallbackChain(LlmCapability.CHAT);
        return fallbackExecutor.execute(chain, client -> client.chat(request));
    }

    /**
     * 流式对话（含跨模型降级 + 首包探测 + 重试 + 熔断）
     * <p>
     * 执行链：FallbackExecutor.executeStream()
     *   → ResilientChatClient.chatStream()
     *     → CircuitBreaker.executeStream()     (订阅时检查状态，结束时更新计数)
     *       → RetryPolicy.retryStream()         (首包超时/网络异常时重试整个流)
     *         → ProbeHandler.wrap()             (首包超时 → ProbeTimeoutException)
     *           → delegate.chatStream()         (原始流)
     *   → 流报错时 → onErrorResume 切换到 chain 中下一个 ResilientChatClient
     */
    public Flux<String> chatStream(String modelId, ChatRequest request) {
        List<ChatCapable> chain = registry.getFallbackChain(LlmCapability.CHAT);
        return fallbackExecutor.executeStream(chain, client -> client.chatStream(request));
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

**零代码。** 只在 YAML 中添加一段配置：

```yaml
- id: newprovider
  type: openai-compat
  name: New Provider
  baseUrl: https://api.newprovider.com/v1
  apiKey: ${NEWPROVIDER_API_KEY}
  models:
    - modelId: model-name
      displayName: Model Display Name
      capabilities: [CHAT, CHAT_STREAM]
      priority: 30
```

启动后自动：`GenericOpenAiProvider` 注册 → `GenericChatClient` 创建 → `ResilientChatClient` 包装 → Fallback Chain 包含。

### 场景 B：非标准 API

写 1 个 Provider + 1 个 Client：

```java
@Component
public class XxxProvider implements LlmProvider { ... }

public class XxxEmbeddingClient extends AbstractEmbeddingClient { ... }
```

### 场景 C：全新能力类型

```java
// 1. 扩展枚举
public enum LlmCapability {
    CHAT, CHAT_STREAM, EMBEDDING, RERANKING,
    TTS  // 新增
}

// 2. 新增抽象类
public abstract class AbstractTtsClient implements CapabilityClient {
    public abstract byte[] synthesize(String text, TtsConfig config);
}

// 3. 新增 Resilient 装饰器
public class ResilientTtsClient extends AbstractTtsClient { ... }

// 4. Registry 新增 getTtsClients() 方法
// 其他所有代码不动
```

---

## 14. 目录结构

```
com.smart.rag.infrastructure.llm/
│
├── LlmCapability.java                      # 能力枚举（可扩展）
├── ModelSpec.java                           # 模型声明（配置映射）
├── CapabilityClient.java                    # 客户端根接口
├── ChatCapable.java                         # Chat 能力契约
├── EmbeddingCapable.java                    # Embedding 能力契约
├── RerankCapable.java                       # Rerank 能力契约
├── LlmProvider.java                         # 供应商接口
├── Message.java                             # 对话消息
├── ChatRequest.java                         # Chat 请求
├── ChatResponse.java                        # Chat 响应
├── EmbeddingType.java                       # 嵌入类型
├── RerankRequest.java                       # 重排请求
├── RerankResult.java                        # 重排结果
│                                          # 异常体系：复用已有 exception/ 包
│                                          #   RemoteException + RemoteErrorCode (301xxx)
│                                          #   ModelCircuitOpenException (已有 fallback/)
│                                          #   ProbeTimeoutException (已有 fallback/)
├── client/                                  # 能力客户端抽象层
│   ├── AbstractChatClient.java
│   ├── AbstractEmbeddingClient.java
│   └── AbstractRerankClient.java
│
├── resilience/                              # 统一弹性层
│   ├── ResilientChatClient.java             # Chat 装饰器
│   ├── ResilientEmbeddingClient.java        # Embedding 装饰器
│   ├── ResilientRerankClient.java           # Rerank 装饰器
│   ├── RetryPolicy.java                     # 统一重试策略（含 CheckedSupplier，集成 FallbackEligibility）
│   ├── FallbackExecutor.java                # 跨模型降级执行器（集成 FallbackEligibility）
│   ├── CircuitBreaker.java                  # 熔断器适配器（包装已有 ModelCircuitBreakerRegistry）
│   ├── CircuitBreakerRegistry.java          # 熔断器注册表（包装已有 ModelCircuitBreakerRegistry）
│   └── ProbeHandler.java                    # 首包探测（包装已有 ProbeStreamHandler + SharedProbeRegistry）


│
├── provider/                                # 供应商实现
│   ├── generic/                             # 通用 OpenAI 兼容
│   │   ├── GenericOpenAiProvider.java       # 单供应商实例
│   │   ├── GenericOpenAiProviderRegistrar.java # 多供应商注册器
│   │   ├── GenericChatClient.java
│   │   ├── GenericEmbeddingClient.java
│   │   └── GenericRerankClient.java
│   ├── dashscope/
│   │   ├── DashScopeProvider.java
│   │   └── DashScopeEmbeddingClient.java    # 非标准 API
│   └── bailian/
│       ├── BailianProvider.java
│       └── BailianRerankClient.java         # 非标准 API
│
├── config/                                  # 配置
│   ├── LlmProperties.java
│   ├── ProviderConfig.java
│   ├── ResilienceConfig.java
│   ├── RetryProperties.java
│   ├── CircuitBreakerProperties.java
│   ├── ProbeProperties.java
│   └── FallbackChainConfig.java
│
└── registry/
    └── LlmClientRegistry.java               # 统一注册表
```

---

## 15. 分阶段实施路线

### Phase 1: 接口定义 + 弹性层（零破坏）

新增文件，不修改任何现有代码：

- `LlmCapability.java`
- `ModelSpec.java`
- `CapabilityClient.java`
- `ChatCapable.java`
- `EmbeddingCapable.java`
- `RerankCapable.java`
- `LlmProvider.java`
- `Message.java`
- `ChatRequest.java`
- `ChatResponse.java`
- `EmbeddingType.java`
- `RerankRequest.java`
- `RerankResult.java`
- `RemoteErrorCode.java`（新增 301xxx LLM 弹性层错误码）
- `AbstractChatClient.java`
- `AbstractEmbeddingClient.java`
- `AbstractRerankClient.java`
- `RetryPolicy.java`（集成已有 `FallbackEligibility`）
- `FallbackExecutor.java`（集成已有 `FallbackEligibility`）
- `CircuitBreaker.java`（适配器，包装已有 `ModelCircuitBreakerRegistry`）
- `CircuitBreakerRegistry.java`（包装已有 `ModelCircuitBreakerRegistry`）
- `ProbeHandler.java`（包装已有 `ProbeStreamHandler` + `SharedProbeRegistry`）
- `FallbackChainConfig.java`
- 配置 Properties 类

> **已有基础设施（不修改，直接复用）**：
> - `ModelCircuitBreakerRegistry` — 三态熔断器（CLOSED→OPEN→HALF_OPEN）
> - `ProbeStreamHandler` — 首包超时检测（Flux.create + timer）
> - `SharedProbeRegistry` — 探测去重（ConcurrentHashMap + CompletableFuture）
> - `FallbackEligibility` — 异常可降级判定（已修复：RemoteException(C类) 可降级）
> - `ModelHealthCache` — Redis 健康缓存（可选）

> **已重构的异常（纳入 RemoteException 体系）**：
> - `ProbeTimeoutException` — 已从 `RuntimeException` 改为 `extends RemoteException`（RemoteErrorCode.LLM_PROBE_TIMEOUT）
> - `ModelCircuitOpenException` — 已从 `RuntimeException` 改为 `extends RemoteException`（RemoteErrorCode.LLM_CIRCUIT_BREAKER_OPEN）
> - `RemoteErrorCode` — 新增 301xxx 段（7 个 LLM 弹性层错误码）

### Phase 2: Provider 实现（逐个迁移）

每个 Provider 从现有 `ModelProvider` 迁移到 `LlmProvider`：
- `GenericOpenAiProvider.java` + `GenericOpenAiProviderRegistrar.java` + `GenericChatClient.java` + `GenericEmbeddingClient.java` + `GenericRerankClient.java`
- `DashScopeProvider.java` + `DashScopeEmbeddingClient.java`
- `BailianProvider.java` + `BailianRerankClient.java`
- `DeepSeekProvider.java`（过渡期两个共存）
- `ZhipuProvider.java`
- `MiniMaxProvider.java`

### Phase 3: 调用方迁移（逐个替换）

按风险从低到高顺序迁移（见 11.2 节）。

### Phase 4: 清理旧代码

删除：
- 旧 `ModelProvider` 接口和实现
- `AbstractModelProvider`
- `ChatClientRegistry`
- `OkHttpSseModelStreamClient`（流式统一到 GenericChatClient 内部）
- `ModelStreamRequest` / `ModelStreamRequestFactory`
- 各调用点散落的自写重试逻辑
- `DashScopeEmbeddingModel`（被 `DashScopeEmbeddingClient` 替代）
- `BailianRerankPostProcessor`（被 `BailianRerankClient` 替代）

---

## 16. 风险与缓解措施

| 风险 | 缓解措施 |
|------|---------|
| 核心聊天路径迁移回归 | Phase 3 最后迁移，保留旧路径可灰度切换 |
| `TokenCountingChatModel` 包装（Agent guardrails） | Agent 内部使用，不放入 CapabilityClient 层面 |
| `ChatRequestSpecFactory` 的 Advisor 链 | 保持在编排层，CapabilityClient 只负责传输 |
| Embedding 批量分片逻辑 | 放在具体 Client 实现内部（如 `DashScopeEmbeddingClient.embedBatch()`），对外透明 |
| Evaluation Profile 隔离 | `LlmClientRegistry` 支持条件注册，evaluation 专用模型按 Profile 过滤 |
| 注册表启动时构建所有客户端的开销 | 客户端为懒构建（首次查询时创建），非启动时全部实例化 |
| 一个 compositeId 有多种能力时的 `get(compositeId, type)` 查询 | `clientsByCompositeId` 按 compositeId 分组，每组内按类型索引 |

---

## 17. 附录：当前调用点映射

| # | 当前文件 | 当前调用模式 | 重构后入口 |
|---|---------|------------|-----------|
| 1 | `AbstractModeStrategy:50` | `ChatClient.call().chatResponse()` | `registry.get(id, ChatCapable.class).chat()` |
| 2 | `AbstractModeStrategy:63` | `OkHttpSseModelStreamClient.stream()` | `registry.get(id, ChatCapable.class).chatStream()` |
| 3 | `MultiTurnModeStrategy:77` | `OkHttpSseModelStreamClient.stream()` | `registry.get(id, ChatCapable.class).chatStream()` |
| 4 | `IntentClassifier:83` | `ChatClient.prompt().call().content()` | `registry.get(id, ChatCapable.class).chat()` |
| 5 | `AgentModeStrategy:134` | IntentClassifier + AbstractModeStrategy | `registry.get(id, ChatCapable.class).chatWithTools()` |
| 6 | `RagConfig:58` | `RewriteQueryTransformer` | `registry.get(id, ChatCapable.class).chat()` |
| 7 | `DashScopeEmbeddingModel:206` | `RestClient.post()` | `registry.get(compositeId, EmbeddingCapable.class).embed()` |
| 8 | `BailianRerankPostProcessor:114` | `RestClient.post()` | `registry.get(compositeId, RerankCapable.class).rerank()` |
| 9 | `LlmJudgeImpl:49` | `ChatClient.prompt().call().content()` | `registry.get(id, ChatCapable.class).chat()` |
| 10 | `DatasetGenerator:171` | `ChatClient.prompt().call().content()` | `registry.get(id, ChatCapable.class).chat()` |
| 11 | `DeepSeekModelProvider:81` | `RestClient.get().uri('/models')` | `LlmProvider.models()` 声明替代 |
| 12 | `MiniMaxModelProvider:82` | `RestClient.get().uri('/v1/models')` | `LlmProvider.models()` 声明替代 |
