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
> 注：#9 `LlmJudgeImpl` 因评估场景需独立 `@Bean("judgeChatClient")` 隔离，本次设计维持现状不迁移（见 §12.1），列入上表仅为完整性记录。

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
| **OpenAI 兼容供应商零代码** | OpenAI 兼容 API 只需在 `providers` 中添加连接配置 + 在 `capabilities` 对应能力组中添加模型引用，不写 Java 代码。混合 API 供应商（如百炼）通过 Strategy 工厂扩展特定能力（~40 行/能力，见 §8.3）。完全非标准 API 需手写 Provider + Client（见 §13 场景 B） |
| **新增能力类型可扩展** | 新增能力只需扩展枚举 + 新增接口/抽象类/装饰器 + 实现 `CapabilityStrategy @Component`，在 YAML `capabilities` 中添加条目。`LlmConfig` 和 `EndpointConfig` 使用 `Map<LlmCapability, ...>` 驱动，无需修改 Java 代码 |
| **重试/熔断统一** | 所有 LLM 调用共享同一套弹性策略配置，不分散在各调用点 |
| **面向接口编程** | 简单调用方依赖能力接口（`ChatCapable` 等），编排调用方依赖 `LlmClientRegistry` + `FallbackExecutor` |
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
                            │ 简单调用方：依赖能力接口（ChatCapable 等）
                            │ 编排调用方：依赖 LlmClientRegistry + FallbackExecutor
┌───────────────────────────▼──────────────────────────────────┐
│              Orchestration 编排层（调用方持有）                  │
│  FallbackExecutor — 跨模型降级编排                             │
│  （遍历 Fallback Chain，逐个尝试 ResilientClient）             │
│  ※ 不在 Registry 层，由 ChatServiceImpl 等调用方直接持有       │
└───────────────────────────┬──────────────────────────────────┘
                            │ 委托 Chain 中的每个 ResilientClient
┌───────────────────────────▼──────────────────────────────────┐
│                    Resilience 弹性层（装饰器）                  │
│  AbstractResilientClient（公共委托基类，消除重复）              │
│  ├─ ResilientChatClient（含 ToolCallingCapable 自动检测）      │
│  ├─ ResilientEmbeddingClient                                   │
│  └─ ResilientRerankClient                                     │
│  （重试策略 RetryPolicy · 熔断保护 CircuitBreaker              │
│    · 首包探测 ProbeHandler，不含跨模型降级）                    │
└───────────────────────────┬──────────────────────────────────┘
                            │ 委托
┌───────────────────────────▼──────────────────────────────────┐
│               CapabilityClient 能力抽象层（接口 + 抽象类）       │
│  接口：ChatCapable · EmbeddingCapable · RerankCapable         │
│  ├─ ToolCallingCapable（ISP 拆分，Agent 场景按需依赖）         │
│  抽象类：AbstractChatClient · AbstractEmbeddingClient          │
│           AbstractRerankClient                                 │
│  ※ 装饰器与抽象类均实现同一能力接口，装饰器通过委托包装抽象类实例  │
│  适配器：ChatModelAdapter（Spring AI ChatModel 桥接）          │
└───────────────────────────┬──────────────────────────────────┘
                            │ 由 LlmProvider.createClient(candidate) 创建
┌───────────────────────────▼──────────────────────────────────┐
│              LlmProvider 供应商层（轻量工厂接口）               │
│  ┌────────────────────────────┐ ┌────────────────────────────────────┐   │
│  │ GenericOpenAiProvider      │ │ Strategy 工厂扩展                  │   │
│  │ （Registrar 从 YAML 创建）  │ │ （ProviderClientFactory @Component）│   │
│  │                            │ │                                    │   │
│  │ 按 endpoint 创建通用 Client │ │ 如百炼 Embedding/Reranking 使用    │   │
│  │ （OpenAI 兼容，零代码）     │ │ DashScope 原生 API（保留完整特性） │   │
│  └────────────────────────────┘ └────────────────────────────────────┘   │
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
> **运行时调用**：Callers → FallbackExecutor（编排层，跨模型降级） → Resilient 装饰器（单模型重试/熔断/探测） → 原始 CapabilityClient → LLM API。
> 供应商与模型解耦——Provider 只是工厂，ModelGroup 决定"用哪些模型"。
> Callers 只依赖能力接口，通过 `asChatModel()` 按需获取 Spring AI ChatModel 视图（详见 §5.5）。
> **弹性层与编排层职责分离**：ResilientClient 负责单模型重试/熔断，FallbackExecutor 负责跨模型降级——二者正交组合。

### 3.2 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 供应商与模型解耦 | **供应商只关心连接，模型按能力分组并引用供应商** | 关注点分离：ProviderConfig 只有 url/apiKey/endpoints，ModelGroup 有 candidates/default-model |
| LlmProvider 设计 | **轻量工厂接口**（id + config + createClient），不持有模型/clients | Provider 只是"怎么创建客户端"的工厂，模型配置独立管理 |
| candidates = Fallback Chain | **候选列表按 priority 排序即为降级链**，不需要单独的 fallback 配置 | 配置简化，candidates 和 Fallback Chain 是同一个列表 |
| 命名槽位 | **default-model + deep-thinking-model** | 调用方按场景选择模型，deep-thinking-model 可指向不同模型 |
| 通用供应商 | **GenericOpenAiProvider**，由 Registrar 从 YAML 批量创建 | 90%+ 的供应商是 OpenAI 兼容 API，纯配置即可接入 |
| 供应商差异化 | **Strategy 驱动的 ProviderClientFactory**，按供应商 × 能力选择 Client 实现 | 通用 Strategy 内置供应商扩展点：有专用工厂用专用工厂（原生 API，保留 text_type / instruct 等特性），否则走通用路径（OpenAI 兼容）。Provider 层无 switch，OCP 合规。百炼 Embedding / Reranking 通过此机制使用 DashScope 原生 API（见 §8.3） |
| ModelCandidate 类型安全 | **sealed interface + 抽象基类 + 三个子类型**（ChatCandidate / EmbeddingCandidate / RerankCandidate） | 编译期穷举检查（switch + permits），能力字段隔离（supportsThinking 仅在 ChatCandidate），YAML 绑定兼容（POJO getter/setter） |
| 接口 vs 抽象类 | **CapabilityClient 用接口，三个能力客户端用抽象类** | 接口保证根的灵活性，抽象类提取公共字段避免重复 |
| 弹性层粒度 | **`AbstractResilientClient` 公共基类 + 三个类型安全装饰器**（ResilientChatClient 同时实现 ChatCapable + ToolCallingCapable，chat/chatWithTools 走统一弹性路径，详见 §7.7） | DRY + 工具调用纳入弹性保护：chatWithTools() 与 chat() 共享同一重试/熔断机制，避免工具调用绕过弹性层 |
| Spring AI 兼容 | **`ChatModelAdapter` 独立适配器**，通过 `chatCapable.asChatModel()` 按需获取 | ISP/LSP 合规：ChatCapable 不继承 ChatModel，桥接代码集中在 Adapter 一处，不污染能力接口 |
| 重试 vs 降级判定 | **可重试 ≠ 可降级**，由 `RetryPolicy.isRetryable()` 和 `FallbackExecutor` 内的 `fallbackEligibility.isEligible()` 分别判定 | 三层职责严格正交：RetryPolicy 只管重试，FallbackExecutor 只管降级，不共享判定逻辑 |
| 重试策略覆盖 | **全局默认 + 按能力类型可选覆盖** | YAML `resilience.retry-overrides` 按能力覆盖 |
| 弹性与编排分离 | **ResilientClient 负责单模型重试/熔断，FallbackExecutor 负责跨模型降级** | 职责正交：弹性层不感知降级链，编排层不感知重试细节 |
| 工具调用 ISP 拆分 | **`ToolCallingCapable` 独立接口**，不混入 ChatCapable | 只有 Agent 场景需要工具调用，避免非 Agent 调用方依赖不需要的方法 |
| 降级事件解耦 | **FallbackEvent record（Observer 模式）**，FallbackExecutor 在降级切换时发布 | UI/metrics/logging 可消费降级事件，不依赖 FallbackExecutor 内部实现 |
| Registry SRP 拆分 | **LlmClientFactory（创建+包装） + LlmClientRegistry（查询+状态管理）** | 单一职责：创建逻辑变化不影响查询 API，查询逻辑可独立测试 |

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
    RERANKING
    // 新增能力：在此添加枚举值 + 在 YAML capabilities 中添加对应条目 +
    // 实现 CapabilityStrategy @Component（详见 §7.1）
}
```

### 4.2 `ModelCandidate` — 模型候选声明（sealed interface）

> **设计决策：sealed interface + 抽象基类**
>
> <b>为什么选择 sealed interface 而非继承</b>：
> 1. **编译期穷举检查**：`switch(ModelCandidate mc)` 配合 `sealed` + `permits`，
>    编译器确保所有子类型都被处理——新增能力类型时未处理的分支会编译失败（OCP + 类型安全）
> 2. **能力字段隔离**：`ChatCandidate.supportsThinking()` 返回 `boolean`（非 `Boolean`），
>    `EmbeddingCandidate.dimension()` 返回 `int`（非 `Integer`）——编译期保证能力字段不混用
> 3. **YAML 绑定兼容**：抽象基类 `AbstractModelCandidate` 保留 POJO getter/setter，
>    Spring Boot `@ConfigurationProperties` 绑定 YAML 到具体子类（`ChatCandidate` / `EmbeddingCandidate` 等），
>    每个子类有无参构造器 + setter，与 Spring Boot 绑定机制兼容
> 4. **`enabled` 默认值**：抽象基类字段声明处 `private boolean enabled = true`，
>    YAML 不配置时默认启用——这是选择 POJO 抽象基类而非 record 的核心原因
>
> <b>YAML 绑定策略（两阶段绑定）</b>：
>
> Spring Boot `@ConfigurationProperties` 的 Binder 无法直接实例化 `abstract sealed class`。
> 因此采用**两阶段绑定**：
>
> **阶段 1 — YAML → RawCandidate（POJO 绑定层）**：
> `ModelGroup` 的 `candidates` 字段在绑定阶段声明为 `List<RawCandidate>`（非 sealed 的普通 POJO），
> Spring Boot Binder 可直接实例化并注入 setter。`RawCandidate` 包含所有候选的公共字段
> （id / provider / model / priority / enabled / params）以及所有能力特定字段的扁平集合
> （supportsThinking / supportsStreaming / dimension），未配置的字段为默认值（boolean→false, int→0）。
>
> **阶段 2 — RawCandidate → sealed subclass（域模型转换层）**：
> `ModelGroup` 提供工厂方法 `ModelGroup.fromRaw(capability, rawCandidates, ...)`，
> 按所属能力组的 `LlmCapability` 将每个 `RawCandidate` 转换为对应的 sealed 子类：
> - `CHAT` → `ChatCandidate`（提取 supportsThinking / supportsStreaming）
> - `EMBEDDING` → `EmbeddingCandidate`（提取 dimension）
> - `RERANKING` → `RerankCandidate`（无额外字段）
>
> 转换在 `GenericOpenAiProviderRegistrar` 或 `@PostConstruct` 中完成，
> 转换后 `ModelGroup.candidates()` 返回 `List<ModelCandidate>`（sealed interface）。
> 域模型（sealed subclass）与绑定层（RawCandidate）完全解耦，
> sealed interface 的编译期穷举检查仅作用于域模型层。
>
> **为什么不用 `@JsonTypeInfo`**：`@ConfigurationProperties` 使用 Spring Boot Binder（非 Jackson），
> `@JsonTypeInfo` 不生效。自定义 `Converter<ModelCandidate>` 需要访问所属能力组上下文
> （同一个 `RawCandidate` 在 chat 组应转为 `ChatCandidate`，在 embedding 组应转为 `EmbeddingCandidate`），
> 而 `Converter` 无上下文感知能力。两阶段绑定是唯一可行方案。

```java
package com.smart.rag.infrastructure.llm;

import java.util.Map;

/**
 * 模型候选声明——sealed interface
 * <p>
 * 每个候选声明且仅声明一种能力（由所属 {@code ModelGroup} 决定）。
 * 三种子类型：{@link ChatCandidate}、{@link EmbeddingCandidate}、{@link RerankCandidate}。
 * <p>
 * <b>公共方法</b>：所有子类型都实现 {@code id()}、{@code provider()}、{@code model()}、
 * {@code priority()}、{@code capability()}、{@code enabled()}、{@code params()}。
 * <p>
 * <b>能力特定方法</b>：
 * <ul>
 *   <li>{@code supportsThinking()} / {@code supportsStreaming()} — 仅 {@code ChatCandidate} 返回有意义的值</li>
 *   <li>{@code dimension()} — 仅 {@code EmbeddingCandidate} 返回有意义的值</li>
 * </ul>
 * 基类默认返回安全值（false / 0），子类覆写为实际值——避免调用方强制转型。
 */
public sealed interface ModelCandidate
    permits AbstractModelCandidate {

    /** 候选唯一标识（用于 default-model / deep-thinking-model 引用） */
    String id();

    /** 引用的供应商 id */
    String provider();

    /** 发送给 LLM API 的原始模型名 */
    String model();

    /** 优先级，数字越小越优先 */
    int priority();

    /** 该候选声明的能力 */
    LlmCapability capability();

    /** 是否启用（默认 true） */
    boolean enabled();

    /** 默认调用参数 */
    Map<String, Object> params();

    // ====== 能力特定方法（基类默认返回安全值，子类覆写） ======

    /** 是否支持深度思考（仅 ChatCandidate 返回 true/false，其他返回 false） */
    default boolean supportsThinking() { return false; }

    /** 向量维度（仅 EmbeddingCandidate 返回 > 0，其他返回 0） */
    default int dimension() { return 0; }

    /** 是否支持流式输出（仅 ChatCandidate 返回 true/false，其他返回 false） */
    default boolean supportsStreaming() { return false; }
}

/**
 * 模型候选抽象基类——实现 sealed interface，提供 YAML 绑定支持
 * <p>
 * 保留 POJO getter/setter 供 Spring Boot {@code @ConfigurationProperties} 绑定。
 * 子类（{@code ChatCandidate}、{@code EmbeddingCandidate}、{@code RerankCandidate}）
 * 添加能力特定字段并覆写对应的 default 方法。
 * <p>
 * <b>为什么用 POJO 而非 record</b>：{@code enabled} 字段需要默认值 {@code true}——
 * YAML 不配置时 Spring Boot 会将 {@code boolean} 初始化为 {@code false}，
 * 与"默认启用"语义矛盾。POJO 可在字段声明处直接赋默认值。
 */
public abstract sealed class AbstractModelCandidate implements ModelCandidate
    permits ChatCandidate, EmbeddingCandidate, RerankCandidate {

    private String id;
    private String provider;
    private String model;
    private int priority;
    private LlmCapability capability;
    private Map<String, Object> params = Map.of();
    private boolean enabled = true;

    // ====== sealed interface 实现（委托给字段） ======

    @Override public String id() { return id; }
    @Override public String provider() { return provider; }
    @Override public String model() { return model; }
    @Override public int priority() { return priority; }
    @Override public LlmCapability capability() { return capability; }
    @Override public boolean enabled() { return enabled; }
    @Override public Map<String, Object> params() { return params; }

    // ====== POJO getter/setter（Spring Boot @ConfigurationProperties 绑定需要） ======

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public LlmCapability getCapability() { return capability; }
    public void setCapability(LlmCapability capability) { this.capability = capability; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

/**
 * Chat 候选——支持深度思考和流式输出
 * <p>
 * YAML 示例：
 * <pre>
 * - id: qwen3-max
 *   provider: bailian
 *   model: qwen3-max
 *   supports-thinking: true
 *   supports-streaming: true
 *   priority: 4
 * </pre>
 */
public final class ChatCandidate extends AbstractModelCandidate {

    private boolean supportsThinking;
    private boolean supportsStreaming;

    @Override public boolean supportsThinking() { return supportsThinking; }
    @Override public boolean supportsStreaming() { return supportsStreaming; }

    public boolean isSupportsThinking() { return supportsThinking; }
    public void setSupportsThinking(boolean supportsThinking) { this.supportsThinking = supportsThinking; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
}

/**
 * Embedding 候选——包含向量维度
 * <p>
 * YAML 示例：
 * <pre>
 * - id: qwen-emb-8b
 *   provider: bailian
 *   model: Qwen/Qwen3-Embedding-8B
 *   dimension: 1536
 *   priority: 1
 * </pre>
 */
public final class EmbeddingCandidate extends AbstractModelCandidate {

    private int dimension;

    @Override public int dimension() { return dimension; }

    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
}

/**
 * Rerank 候选——无额外字段
 * <p>
 * YAML 示例：
 * <pre>
 * - id: qwen3-rerank
 *   provider: bailian
 *   model: qwen3-rerank
 *   priority: 1
 * </pre>
 */
public final class RerankCandidate extends AbstractModelCandidate {
    // 无额外字段，所有公共方法继承自 AbstractModelCandidate
}
```

> **与已有 ModelCandidate 的关系**：
> 当前代码库中 `com.smart.rag.infrastructure.fallback.ModelCandidate` 仅包含
> `id, provider, model, priority, enabled, supportsThinking` 字段。
> 本方案将其重构为 sealed interface + 三个子类型，包路径迁移至 `infrastructure.llm`。
> 旧 `fallback.ModelCandidate` 在迁移完成后删除。

### 4.3 `CapabilityClient` — 客户端根接口

```java
package com.smart.rag.infrastructure.llm;

/**
 * 能力客户端根接口
 * <p>
 * 所有 LLM 能力客户端（Chat / Embedding / Rerank）的公共契约。
 * 不定义具体 LLM 调用方法——通过 Registry 的类型查询获取具体能力接口。
 * <p>
 * <b>设计决策</b>：接口不暴露 {@code ModelCandidate} 引用。
 * {@code ModelCandidate} 是创建客户端的输入参数，不应成为接口契约的一部分——
 * 避免接口与数据模型的循环耦合，也使客户端可以在测试中脱离 ModelCandidate 独立使用。
 * 实现类通过构造器接收 ModelCandidate，将其元数据提取为不可变字段。
 *
 * <pre>
 * 使用示例：
 * ChatCapable chat = registry.getDefault(LlmCapability.CHAT, ChatCapable.class);
 * LlmResponse resp = chat.chat(ChatRequest.of("你好"));
 * </pre>
 */
public interface CapabilityClient extends AutoCloseable {

    /** 候选唯一标识（对应 YAML candidate.id，如 "qwen3-max"） */
    String candidateId();

    /** 供应商 ID（对应 YAML candidate.provider，如 "bailian"） */
    String providerId();

    /** 发送给 LLM API 的原始模型名（对应 YAML candidate.model） */
    String modelName();

    /** 该客户端声明的能力（一对一） */
    LlmCapability capability();

    /** 该客户端是否可用（供应商 API key 有效；连通性由熔断器状态叠加判断，见 ResilientClient.isAvailable()） */
    boolean isAvailable();

    /** 释放资源（大多数 Client 共享 Provider 的 HTTP 连接池，默认空实现） */
    @Override
    default void close() {}
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
 *   <li>混合 API 供应商（如百炼）→ {@code GenericOpenAiProvider} + Strategy 工厂扩展（见 §7.8 / §8.3）</li>
 *   <li>完全非标准 API → 手写 {@code @Component} LlmProvider（见 §13 场景 B）</li>
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
     * Provider 委托 {@link CapabilityStrategy} 完成客户端创建：
     * <ol>
     *   <li>Provider 从 {@code config()} 中取出 endpoint 配置（endpoint 是供应商 YAML 属性，
     *       语义上属于"怎么连"的一部分，由 Provider 持有配置所有权）</li>
     *   <li>Strategy 接收已解析的 baseUrl + endpoint + apiKey，负责实例化具体的 CapabilityClient</li>
     * </ol>
     * 这种拆分的原因：endpoint 配置在 YAML 中是 Provider 的属性（{@code providers.<id>.endpoints}），
     * 因此 Provider 自然持有它；但"如何使用 endpoint 创建客户端"是能力特定的逻辑，
     * 由 Strategy 决定（如百炼 Embedding 走原生 API 而非 OpenAI 兼容端点）。
     * <p>
     * 创建的是原始客户端（未包装 Resilience），
     * 由 {@code LlmClientRegistry} 在注册时统一包装 Resilient 装饰器。
     *
     * @param candidate 模型候选声明
     * @return 对应的能力客户端实例
     */
    CapabilityClient createClient(ModelCandidate candidate);

}
```

### 4.5 `MessageInformation` — 对话消息

```java
package com.smart.rag.infrastructure.llm;

import java.util.Map;
import java.util.Objects;

/**
 * 对话消息（供应商无关的 SPI 层消息载体）
 * <p>
 * <b>为什么用 class 而非 record</b>：{@code metadata} 不参与 equals/hashCode——
 * 它是附加调试信息（如 tool_calls 列表），不应影响消息的身份判定（如对话历史去重）。
 * Java record 的契约是"所有组件参与值语义"，自定义排除会违反契约。
 * 使用普通 class 可以自然地定义选择性 equals/hashCode。
 * <p>
 * <b>为什么命名为 MessageInformation 而非 Message</b>：项目中已存在多个同名类型：
 * <ul>
 *   <li>{@code conversation.entity.Message} — DB 持久化实体（MyBatis-Plus）</li>
 *   <li>{@code org.springframework.ai.chat.messages.Message} — Spring AI 框架类型</li>
 * </ul>
 * 命名为 {@code MessageInformation} 避免全限定名冲突和 import 歧义。
 * <p>
 * <b>Agent 场景</b>：{@code toolCallId} 用于匹配工具调用的请求-响应配对，
 * 不可丢弃。{@code metadata} 透传 tool_calls 列表等结构化数据。
 */
public final class MessageInformation {

    private final String role;
    private final String content;
    /** 工具调用 ID（仅 role=tool 时非空），用于 Agent 场景的请求-响应配对 */
    private final String toolCallId;
    /** 附加元数据（如 tool_calls 列表、name 等），不参与 equals/hashCode */
    private final Map<String, Object> metadata;

    private MessageInformation(String role, String content, String toolCallId, Map<String, Object> metadata) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.metadata = metadata != null ? metadata : Map.of();
    }

    // ====== 工厂方法 ======

    public static MessageInformation user(String content) {
        return new MessageInformation("user", content, null, Map.of());
    }

    public static MessageInformation assistant(String content) {
        return new MessageInformation("assistant", content, null, Map.of());
    }

    public static MessageInformation system(String content) {
        return new MessageInformation("system", content, null, Map.of());
    }

    /** 带附加元数据的 assistant 消息（携带 tool_calls 声明等） */
    public static MessageInformation assistant(String content, Map<String, Object> metadata) {
        return new MessageInformation("assistant", content, null, metadata);
    }

    /**
     * 工具响应消息（保留 toolCallId 用于 Agent 请求-响应配对）
     */
    public static MessageInformation tool(String toolCallId, String content) {
        return new MessageInformation("tool", content, toolCallId, Map.of());
    }

    /** 通用工厂：按 role 和 content 创建（用于 Spring AI → SPI 消息桥接） */
    public static MessageInformation of(String role, String content) {
        return new MessageInformation(role, content, null, Map.of());
    }

    // ====== 访问器 ======

    public String role() { return role; }
    public String content() { return content; }
    public String toolCallId() { return toolCallId; }
    public Map<String, Object> metadata() { return metadata; }

    // ====== equals/hashCode：仅比较身份字段，排除 metadata ======

    /**
     * 仅比较 role、content、toolCallId，排除 metadata。
     * <p>
     * 语义：消息的"身份"由 role + content + toolCallId 决定。
     * metadata 是附加结构化数据（如 tool_calls 列表），同一条消息携带或不携带 metadata
     * 视为"同一条消息的两种展示形式"，而非"两条不同的消息"。
     * 这与 {@code RedisChatMemoryRepository} 的序列化语义一致。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageInformation other)) return false;
        return Objects.equals(role, other.role)
            && Objects.equals(content, other.content)
            && Objects.equals(toolCallId, other.toolCallId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content, toolCallId);
    }

    @Override
    public String toString() {
        return "MessageInformation{role='" + role + "', content='" +
            (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) +
            "', toolCallId='" + toolCallId + "'}";
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
    List<MessageInformation> history,

    /** 温度（覆盖 ModelCandidate.params 中的值） */
    Double temperature,

    /** 最大 token 数 */
    Integer maxTokens,

    /** Top-P 采样 */
    Double topP,

    /** 额外参数，透传给底层 SDK */
    Map<String, Object> extraParams
) {
    /** 最简请求：仅用户输入 */
    public static ChatRequest of(String input) {
        return new ChatRequest(input, null, List.of(),
            null, null, null, Map.of());
    }

    /** 带 System Prompt 的请求 */
    public static ChatRequest withSystem(String systemPrompt, String input) {
        return new ChatRequest(input, systemPrompt, List.of(),
            null, null, null, Map.of());
    }

    /**
     * Builder — 构建多参数 ChatRequest
     * <p>
     * 替代 {@code new ChatRequest(input, null, List.of(), null, null, null, Map.of())}
     * 这种可读性差的 7 参数构造。
     * <p>
     * <b>超参来源</b>：{@code temperature}、{@code maxTokens}、{@code topP} 等超参
     * 优先从 {@link ModelCandidate#params()}（YAML 配置）获取默认值，
     * 调用方可通过 Builder 覆盖（如特殊场景需要更低温度）。
     * {@code null} 表示"使用模型默认值"——不硬编码魔法数字。
     * <p>
     * 使用示例：
     * <pre>
     * // 从 ModelCandidate.params 获取默认超参（YAML 配置驱动）
     * ModelCandidate candidate = ...;
     * Map&lt;String, Object&gt; defaults = candidate.params();
     *
     * // 仅覆盖需要特殊处理的超参，其余沿用模型默认值
     * ChatRequest req = ChatRequest.builder(userInput)
     *     .systemPrompt(systemPrompt)
     *     .history(previousMessages)
     *     .temperature((Double) defaults.getOrDefault("temperature", null))
     *     .maxTokens((Integer) defaults.getOrDefault("maxTokens", null))
     *     .build();
     *
     * // 最简场景：不设置超参，全部使用模型默认值
     * ChatRequest req = ChatRequest.of(userInput);
     * </pre>
     */
    public static Builder builder(String input) {
        return new Builder(input);
    }

    /**
     * 从 ModelCandidate.params 填充默认超参的便捷工厂
     * <p>
     * 将 {@code candidate.params()} 中的 temperature/maxTokens/topP 映射到 Builder，
     * 调用方可在此基础上链式覆盖。
     */
    public static Builder fromDefaults(String input, ModelCandidate candidate) {
        Map<String, Object> defaults = candidate.params();
        return builder(input)
            .temperature((Double) defaults.get("temperature"))
            .maxTokens((Integer) defaults.get("maxTokens"))
            .topP((Double) defaults.get("topP"));
    }

    public static class Builder {
        private final String input;
        private String systemPrompt;
        private List<MessageInformation> history = List.of();
        private Double temperature;   // null = 使用模型默认值（ModelCandidate.params）
        private Integer maxTokens;    // null = 使用模型默认值
        private Double topP;          // null = 使用模型默认值
        private Map<String, Object> extraParams = Map.of();

        private Builder(String input) { this.input = input; }

        public Builder systemPrompt(String sp) { this.systemPrompt = sp; return this; }
        public Builder history(List<MessageInformation> h) { this.history = h; return this; }
        /** 覆盖模型默认温度（null = 使用 ModelCandidate.params 中的值） */
        public Builder temperature(Double t) { this.temperature = t; return this; }
        /** 覆盖模型默认 maxTokens（null = 使用 ModelCandidate.params 中的值） */
        public Builder maxTokens(Integer mt) { this.maxTokens = mt; return this; }
        /** 覆盖模型默认 topP（null = 使用 ModelCandidate.params 中的值） */
        public Builder topP(Double tp) { this.topP = tp; return this; }
        public Builder extraParams(Map<String, Object> ep) { this.extraParams = ep; return this; }

        public ChatRequest build() {
            return new ChatRequest(input, systemPrompt, history,
                temperature, maxTokens, topP, extraParams);
        }
    }
}

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
 * {@code ChatCapable} 不继承 Spring AI {@code ChatModel}（ISP/LSP 合规），
 * 桥接由 {@link com.smart.rag.infrastructure.llm.adapter.ChatModelAdapter} 独立完成：
 * Adapter 的 {@code call(Prompt)} 返回 Spring AI 的 {@code ChatResponse}，
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

/**
 * Chat 能力契约
 * <p>
 * 定义 Chat 场景的核心操作。AbstractChatClient 和 ResilientChatClient 均实现此接口。
 * 调用方通过此接口与 Chat 客户端交互，无需关心是否经过弹性包装。
 * <p>
 * <b>不继承 Spring AI ChatModel</b>：ChatCapable 保持纯净的能力契约，
 * 不引入 Spring AI 的 Prompt/ChatResponse 类型依赖（ISP）。
 * 需要 ChatModel 的场景通过 {@link #asChatModel()} 获取适配器视图（详见 §5.5）。
 * <p>
 * <b>工具调用已拆分</b>：{@code chatWithTools} 不在本接口中，
 * 而是在独立的 {@link ToolCallingCapable} 接口中（详见 §5.4），只有 Agent 场景需要实现。
 */
public interface ChatCapable extends CapabilityClient {

    /** 阻塞式对话 */
    LlmResponse chat(ChatRequest request);

    /** 流式对话（SSE） */
    Flux<String> chatStream(ChatRequest request);

    /** 是否支持流式（由 ModelCandidate.supportsStreaming 声明，未声明时默认 false） */
    boolean supportsStreaming();

    /**
     * 获取 Spring AI ChatModel 适配器视图
     * <p>
     * 返回一个 {@code ChatModel} 实现，将 {@code call(Prompt)} 桥接到 {@link #chat(ChatRequest)}，
     * 将 {@code stream(Prompt)} 桥接到 {@link #chatStream(ChatRequest)}。
     * 桥接代码集中在适配器中，不污染能力接口。
     * <p>
     * 用途：{@code ChatClient.builder(chatCapable.asChatModel()).build()}
     */
    default org.springframework.ai.chat.model.ChatModel asChatModel() {
        return new ChatModelAdapter(this);
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

### 5.4 `ToolCallingCapable` — 工具调用能力（ISP 拆分）

```java
package com.smart.rag.infrastructure.llm;

import java.util.List;

/**
 * 工具调用能力契约（ISP 拆分）
 * <p>
 * 从 ChatCapable 中独立出来，只有支持工具调用的 Chat 客户端才需要实现此接口。
 * AgentModeStrategy 等需要工具调用的调用方显式依赖此接口。
 * <p>
 * 获取方式：
 * <pre>
 * ChatCapable client = registry.get("qwen3-max", ChatCapable.class);
 * if (client instanceof ToolCallingCapable tc) {
 *     LlmResponse resp = tc.chatWithTools(request, tools);
 * }
 * </pre>
 */
public interface ToolCallingCapable extends ChatCapable {

    /** 带工具调用的对话（Agent 场景） */
    LlmResponse chatWithTools(ChatRequest request, List<Object> tools);
}
```

### 5.5 `ChatModelAdapter` — Spring AI ChatModel 适配器

```java
package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.*;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatGenerationMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;

/**
 * Spring AI ChatModel 适配器
 * <p>
 * 将任何 {@code ChatCapable} 实例适配为 Spring AI {@code ChatModel}。
 * 这是 ChatCapable 与 ChatModel 之间桥接代码的<strong>唯一存放位置</strong>。
 * <p>
 * <b>设计原则</b>：
 * <ul>
 *   <li>ISP — ChatCapable 不被迫继承 ChatModel 的所有方法</li>
 *   <li>LSP — 适配器是独立的 ChatModel 实现，不影响 ChatCapable 的契约</li>
 *   <li>SRP — 桥接逻辑（Prompt→ChatRequest、LlmResponse→ChatResponse）集中在此</li>
 * </ul>
 * <p>
 * 使用方式：{@code chatCapable.asChatModel()} 或 {@code new ChatModelAdapter(chatCapable)}
 */
public class ChatModelAdapter implements ChatModel {

    private final ChatCapable delegate;

    public ChatModelAdapter(ChatCapable delegate) {
        this.delegate = delegate;
    }

    /** 返回被适配的 ChatCapable 实例 */
    public ChatCapable delegate() { return delegate; }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        LlmResponse llmResp = delegate.chat(request);
        return wrapAsChatResponse(llmResp);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatRequest request = extractChatRequest(prompt);
        return delegate.chatStream(request)
            .map(chunk -> new ChatResponse(
                List.of(new Generation(new AssistantMessage(chunk)))));
    }

    // ======== 桥接工具方法 ========

    private ChatResponse wrapAsChatResponse(LlmResponse llmResp) {
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
     * 从 Spring AI {@code Prompt} 提取 {@code ChatRequest}，保留 SystemMessage 和对话历史。
     * <p>
     * {@code Prompt.getContents()} 仅返回用户消息文本，会丢失 SystemMessage 和历史。
     * 此方法从 {@code Prompt.getInstructions()} 中提取完整消息列表：
     * <ul>
     *   <li>SystemMessage → {@code ChatRequest.systemPrompt}</li>
     *   <li>其他消息 → {@code ChatRequest.history}（保留多轮对话上下文，不含最后一条 UserMessage）</li>
     *   <li>最后一条 UserMessage 文本 → {@code ChatRequest.input}</li>
     * </ul>
     * <p>
     * <b>消息去重约束</b>：对话历史严格按 User/Assistant/Tool 交叉排列，
     * 最后一条 UserMessage 仅作为 {@code input} 传递，不重复出现在 {@code history} 中。
     * 这避免了模型收到重复的用户消息。
     */
    private ChatRequest extractChatRequest(Prompt prompt) {
        String systemPrompt = null;
        List<MessageInformation> history = List.of();
        String userContent = prompt.getContents();

        if (prompt.getInstructions() != null && !prompt.getInstructions().isEmpty()) {
            var instructions = prompt.getInstructions();
            // 提取 SystemMessage
            for (var msg : instructions) {
                if (msg instanceof org.springframework.ai.chat.messages.SystemMessage sm) {
                    systemPrompt = sm.getText();
                    break;
                }
            }
            // 构建 history：排除 SystemMessage 和最后一条 UserMessage
            // history 保留 User/Assistant/Tool 的交叉排列，最后一条 UserMessage 作为 input 传递
            var nonSystemMessages = instructions.stream()
                .filter(m -> !(m instanceof org.springframework.ai.chat.messages.SystemMessage))
                .toList();

            if (!nonSystemMessages.isEmpty()) {
                // 找到最后一条 UserMessage 的位置，排除它（它作为 input 传递）
                int lastUserIdx = -1;
                for (int i = nonSystemMessages.size() - 1; i >= 0; i--) {
                    if (nonSystemMessages.get(i) instanceof org.springframework.ai.chat.messages.UserMessage) {
                        lastUserIdx = i;
                        break;
                    }
                }
                // O(n) 构建 history：跳过 lastUserIdx 处的 UserMessage（它作为 input 传递）
                var builder = new java.util.ArrayList<MessageInformation>(nonSystemMessages.size() - 1);
                for (int i = 0; i < nonSystemMessages.size(); i++) {
                    if (i == excludeIdx) continue;
                    var m = nonSystemMessages.get(i);
                    builder.add(MessageInformation.of(
                        m.getMessageType().name().toLowerCase(), m.getText()));
                }
                history = Collections.unmodifiableList(builder);
            }
        }

        return new ChatRequest(userContent, systemPrompt, history,
            null, null, null, Map.of());
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
 * 其他元信息方法由基类通过构造器注入的 {@link ModelCandidate} 统一处理。
 * <p>
 * <b>不包含重试/熔断逻辑</b>——由 {@code ResilientChatClient} 装饰器在外部施加。
 * <p>
 * <b>不包含 Spring AI ChatModel 桥接代码</b>——ChatCapable 不继承 ChatModel，
 * 桥接逻辑集中在独立的 {@code ChatModelAdapter} 中（详见 §5.5）。
 * <p>
 * <b>工具调用</b>：需要支持工具调用的子类额外实现 {@link ToolCallingCapable}（详见 §5.4）。
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

    /**
     * 默认可用。子类可覆写为实际的 API key 检查或连通性探测。
     * ResilientChatClient 会在此基础上叠加熔断器状态判断。
     */
    @Override
    public boolean isAvailable() { return true; }

    @Override
    public boolean supportsStreaming() {
        return candidate.supportsStreaming();
    }

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
 * - GenericEmbeddingClient（纯 OpenAI 兼容 Embedding API，无供应商特判）
 * - BailianEmbeddingClient（DashScope 原生 API，支持 text_type / instruct / sparse embedding）
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
 * - GenericRerankClient（纯 OpenAI 兼容 Rerank API，如 Cohere / Jina 格式）
 * - BailianRerankClient（DashScope 原生 API，支持 instruct / 服务端 top_n 截断）
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
 * <b>可重试 ≠ 可降级</b>——两个正交概念分别判定：
 * <ul>
 *   <li>{@link #isRetryable(Throwable)} — 同模型重试判定：瞬态错误（网络超时、5xx、限流 429、首包超时）</li>
 *   <li>{@link #isFallbackEligible(Throwable)} — 跨模型降级判定：可重试错误 + 熔断器打开 + 认证失败等</li>
 * </ul>
 * 语义区别示例：
 * <ul>
 *   <li>认证失败（A 类）→ 不可重试（同一模型重试无意义），但可降级（换模型可能成功）</li>
 *   <li>响应格式错误（B 类）→ 不可重试（同一模型重试无意义），但可降级（换模型可能正常）</li>
 *   <li>熔断器打开 → 不可重试（重试无意义，等待冷却），但可降级（换模型绕过熔断）</li>
 *   <li>内容过滤 → 不可重试（请求本身有问题），不可降级（换模型结果相同）</li>
 * </ul>
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
    public RetryPolicy(RetryConfig properties) {
        this.maxAttempts = properties.effectiveMaxAttempts();
        this.baseDelayMs = properties.effectiveBaseDelayMs();
        this.maxDelayMs = properties.effectiveMaxDelayMs();
        this.multiplier = properties.effectiveMultiplier();
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
     * <p>
     * <b>异常包装策略</b>：
     * <ul>
     *   <li>不可重试异常 → 直接抛出原始异常（如 ContentFilteredException、UnsupportedOperationException）</li>
     *   <li>可重试但重试耗尽 → 包装为 {@link LlmTransientException} 抛出，
     *       使 {@code FallbackExecutor} 和 {@code CircuitBreaker} 通过统一的异常类型识别瞬态失败</li>
     * </ul>
     *
     * @param action 可重试的操作（允许抛出 checked exception）
     * @return 操作结果
     * @throws Exception 不可重试异常原样抛出；可重试异常重试耗尽后包装为 LlmTransientException
     */
    public <T> T executeWithBackoff(CheckedSupplier<T> action) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                if (!isRetryable(e)) {
                    // 不可重试异常（认证失败、内容过滤等）→ 直接抛出，不包装
                    throw e;
                }
                lastException = e;
                if (attempt == maxAttempts - 1) {
                    // 可重试但重试耗尽 → 包装为 LlmTransientException，
                    // 使 FallbackExecutor/CircuitBreaker 通过统一异常类型识别瞬态失败
                    throw new LlmTransientException(
                        "LLM call failed after " + maxAttempts + " attempts: " + e.getMessage(), e);
                }
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
     * 判断异常是否可重试（同模型）
     * <p>
     * 仅瞬态错误可重试——同一模型稍后可能恢复：
     * <ul>
     *   <li>网络超时 / IOException / SocketTimeoutException</li>
     *   <li>供应商 5xx / 网关超时 → {@code LlmTransientException}</li>
     *   <li>限流 429 → {@code RemoteException(LLM_RATE_LIMITED)}</li>
     *   <li>首包探测超时 → {@link ProbeTimeoutException}</li>
     * </ul>
     * <b>不可重试</b>（即使同一模型重试也无意义）：
     * <ul>
     *   <li>熔断器打开 → {@link ModelCircuitOpenException}（等待冷却，非重试可解）</li>
     *   <li>认证失败 → A/B 类异常（请求本身有问题）</li>
     *   <li>内容过滤 → 请求本身违规（换时间重试结果相同）</li>
     *   <li>编程错误 → {@link UnsupportedOperationException}（如对非流式模型调用 chatStream）</li>
     * </ul>
     */
    private boolean isRetryable(Throwable e) {
        if (e instanceof ModelCircuitOpenException) {
            return false;
        }
        if (e instanceof UnsupportedOperationException) {
            return false; // 编程错误：调用方应在调用前检查 supportsStreaming()
        }
        // 仅 C 类瞬态错误可重试（网络超时、5xx、429 限流、探测超时等）
        // 注意：降级判定由 FallbackExecutor 独立负责，RetryPolicy 不感知降级语义
        return e instanceof LlmTransientException
            || e instanceof java.io.IOException
            || e instanceof ProbeTimeoutException;
    }

}
```

### 7.3 `FallbackExecutor` — 跨模型降级执行器

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.fallback.FallbackEligibility;

import java.util.List;

/**
 * 跨模型 Fallback 降级执行器
 * <p>
 * 按 Fallback Chain 顺序尝试，单个客户端失败后自动降级到下一个。
 * 集成已有的 {@link FallbackEligibility} 过滤用户错误（不可降级的异常直接终止）。
 * <p>
 * <b>三层职责严格正交</b>：
 * <pre>
 *   Layer 1 — ResilientClient（单模型弹性）：重试 + 熔断 + 探测，不感知降级
 *   Layer 2 — FallbackExecutor（跨模型编排）：降级链遍历，不感知重试细节
 *   Layer 3 — Caller（业务层）：组装 chain + 调用
 * </pre>
 * <b>责任链语义</b>：Fallback Chain 本质上是一条责任链——每个节点（ResilientClient）
 * 独立决定"成功"或"失败"，失败时传递给下一个节点。阻塞式 {@code execute()} 使用
 * for-loop 遍历（等价于 Chain of Responsibility），流式 {@code executeStream()} 使用
 * 迭代构造 {@code Flux.defer() + onErrorResume} 实现惰性责任链（从链尾向链首构建，
 * 每个节点是平坦的 defer+onErrorResume，不累积操作符深度）。
 * <b>降级事件（Observer 模式）</b>：每次降级切换时发布 {@link FallbackEvent}，
 * 供 UI 层提示用户、metrics 采集、日志追踪消费。调用方可通过
 * {@code Flux.doOnNext(event -> ...)} 或 Spring EventListener 订阅。
 * <p>
 * <b>关键约束</b>：传入 {@code action} 的 client 必须是已包装 Resilience 的客户端。
 * {@code fallbackEligibility.isEligible(e)} 是降级的唯一判定入口，
 * 与 {@code RetryPolicy.isRetryable(e)} 完全独立，不共享判定逻辑。
 * Registry 返回的 Fallback Chain 已包含 Resilient 包装。
 */
public class FallbackExecutor {

    private static final Logger log = LoggerFactory.getLogger(FallbackExecutor.class);

    /**
     * 受检异常兼容的函数式接口
     * <p>
     * {@link java.util.function.Function} 不允许抛出 checked exception，
     * 而 LLM 调用的 {@code chat()} / {@code chatStream()} 可能抛出
     * {@link java.io.IOException} 等受检异常。
     * 此接口替代 {@code Function} 作为 {@code execute()} 的参数类型。
     */
    @FunctionalInterface
    public interface CheckedFunction<T, R> {
        R apply(T t) throws Exception;
    }

    private final FallbackEligibility fallbackEligibility;
    /** 降级事件发布器（Observer 模式），可选 */
    @Nullable
    private final java.util.function.Consumer<FallbackEvent> eventPublisher;

    public FallbackExecutor(FallbackEligibility fallbackEligibility) {
        this(fallbackEligibility, null);
    }

    public FallbackExecutor(FallbackEligibility fallbackEligibility,
                            @Nullable java.util.function.Consumer<FallbackEvent> eventPublisher) {
        this.fallbackEligibility = fallbackEligibility;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 执行 Fallback Chain（阻塞式，责任链语义）
     *
     * @param chain  按优先级排序的客户端列表
     * @param action 对单个客户端执行的操作
     * @return 第一个成功的结果
     * @throws RemoteException 所有客户端都失败时抛出（RemoteErrorCode.LLM_ALL_MODELS_FAILED）
     */
    public <T extends CapabilityClient, R> R execute(
            List<T> chain,
            CheckedFunction<T, R> action) throws Exception {

        // 入口预过滤：跳过全部已禁用的候选，避免误报 LLM_ALL_MODELS_FAILED
        List<T> available = chain.stream()
            .filter(CapabilityClient::isAvailable)
            .toList();
        if (available.isEmpty()) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Fallback chain is empty — all candidates are disabled");
        }

        Exception lastException = null;
        for (int i = 0; i < available.size(); i++) {
            T client = available.get(i);
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
                // 发布降级事件（Observer 模式）
                if (eventPublisher != null && i + 1 < available.size()) {
                    eventPublisher.accept(new FallbackEvent(
                        client.capability(), client.candidateId(),
                        available.get(i + 1).candidateId(), e));
                }
            }
        }
        throw new RemoteException(RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用", lastException);
    }

    /**
     * 执行 Fallback Chain（流式，泛型 {@code <R>}，责任链语义）
     * <p>
     * 泛型 {@code R} 使本方法不限于 {@code Flux<String>}——未来流式响应可携带
     * 结构化数据（如 {@code Flux<LlmStreamChunk>} 含 token 计数、finish reason），
     * 无需修改 FallbackExecutor（OCP 合规）。
     * <p>
     * <b>调用层次（弹性层与编排层职责正交）</b>：
     * <pre>
     *   FallbackExecutor.executeStream(chain, c -> c.chatStream(req))
     *     │
     *     ├─ 订阅 client_0 的 chatStream()
     *     │    │  ← client_0 是 ResilientChatClient，内部已编排：
     *     │    │     circuitBreaker → retryPolicy → probeHandler → delegate
     *     │    │  ← FallbackExecutor 不知道也不关心这个内部结构
     *     │    │
     *     │    ├─ 流正常 → 直接输出，不尝试下一个 client
     *     │    └─ 流报错（ResilientClient 内部重试+熔断均耗尽后抛出最终异常）
     *     │         → FallbackExecutor 只看到一个不可恢复的异常，切换到 client_1
     *     │
     *     └─ 所有 client 均报错 → Flux.error(RemoteException.LLM_ALL_MODELS_FAILED)
     * </pre>
     * <b>流式降级语义：从头开始用新模型重新生成</b>
     * <p>
     * 切换发生在 {@code onErrorResume} 信号层面。当模型 A 在输出部分内容后失败：
     * <ul>
     *   <li>已发送给下游的数据片段（SSE chunks）不会回滚——这是 Flux 信号模型的固有特性</li>
     *   <li>新模型 B 从头开始生成完整响应，不续接 A 的不完整片段</li>
     * </ul>
     * <p>
     * <b>⚠ 调用方须知</b>：若模型 A 已发出 N 个 token 后失败，调用方收到的将是
     * 「A 的不完整片段 + B 的完整输出」的拼接，内容可能不连贯。
     * 调用方应在 UI 层提示用户"因模型切换，前文可能不完整"——
     * 通过 {@link FallbackEvent} 可获知降级发生的具体模型和异常原因。
     *
     * @param chain  按优先级排序的客户端列表
     * @param action 对单个客户端执行的操作，返回 Flux&lt;R&gt;。
     *               FallbackExecutor 不感知 action 内部结构。
     * @return 带降级语义的 Flux&lt;R&gt;
     */
    public <T extends CapabilityClient, R> Flux<R> executeStream(
            List<T> chain,
            Function<T, Flux<R>> action) {

        // 预过滤不可用客户端
        List<T> available = chain.stream()
            .filter(CapabilityClient::isAvailable)
            .toList();
        if (available.isEmpty()) {
            return Flux.error(new RemoteException(
                RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用"));
        }
        return buildStreamChain(available, action);
    }

    /**
     * 构建流式降级链（迭代构造，避免递归 onErrorResume 在长链下累积操作符深度）
     * <p>
     * 从链尾向链首迭代构建：每个 {@code Flux.defer()} 仅在前一个 Flux 失败时才订阅下一个，
     * 保持惰性语义。与递归 {@code onErrorResume} 的区别：
     * <ul>
     *   <li>递归：每个降级层嵌套一层 {@code onErrorResume}，链深 = 降级层数</li>
     *   <li>迭代：每个降级层是一个 {@code defer + onErrorResume} 平坦节点，
     *       总操作符深度固定为 2（不随链长增长）</li>
     * </ul>
     */
    private <T extends CapabilityClient, R> Flux<R> buildStreamChain(
            List<T> chain,
            Function<T, Flux<R>> action) {

        // 从链尾向链首迭代构建，最终 result 是链首节点
        Flux<R> result = Flux.error(new RemoteException(
            RemoteErrorCode.LLM_ALL_MODELS_FAILED, "所有模型均不可用（流式降级链耗尽）"));

        for (int i = chain.size() - 1; i >= 0; i--) {
            final int index = i;
            final T client = chain.get(i);
            final Flux<R> downstream = result;

            result = Flux.defer(() -> action.apply(client))
                .doOnError(e -> {
                    // 发布降级事件（Observer 模式），供 UI/metrics/logging 消费
                    if (eventPublisher != null && fallbackEligibility.isEligible(e)
                            && index + 1 < chain.size()) {
                        eventPublisher.accept(new FallbackEvent(
                            client.capability(), client.candidateId(),
                            chain.get(index + 1).candidateId(), e));
                    }
                })
                .onErrorResume(e -> {
                    // 用户错误不降级，直接向下游传播
                    if (!fallbackEligibility.isEligible(e)) {
                        return Flux.error(e);
                    }
                    log.warn("Stream client '{}' failed at index {}, falling back to next: {}",
                        client.candidateId(), index, e.getMessage());
                    return downstream;
                });
        }
        return result;
    }
}
```

#### `FallbackEvent` — 降级事件（Observer 模式）

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.LlmCapability;

/**
 * 降级事件 — 记录一次模型切换
 * <p>
 * 发布时机：阻塞式 {@code execute()} 和流式 {@code executeStream()} 在切换到下一个模型时发布。
 * 消费方：
 * <ul>
 *   <li>UI 层：提示用户"因模型切换，前文可能不完整"</li>
 *   <li>Metrics：采集 {@code llm.fallback.invocations} 计数器（标签：capability, from, to）</li>
 *   <li>日志：记录降级链路追踪</li>
 * </ul>
 */
public record FallbackEvent(
    /** 发生降级的能力类型 */
    LlmCapability capability,
    /** 失败的模型 candidateId */
    String fromCandidateId,
    /** 降级目标模型 candidateId */
    String toCandidateId,
    /** 触发降级的异常 */
    Exception cause
) {}
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
                if (!(e instanceof ProbeTimeoutException) && isInfraFailure(e)) {
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
     * 命名为 {@code isInfraFailure} 而非 {@code isRetryable}——避免与
     * {@link RetryPolicy#isRetryable(Throwable)} 同名异义的维护混淆。
     * RetryPolicy.isRetryable 判定"是否值得同模型重试"，
     * 本方法判定"是否计入熔断器失败计数"——语义不同，命名应体现差异。
     * <p>
     * 排除 {@link ProbeTimeoutException}（已由 ProbeStreamHandler 处理）。
     */
    private boolean isInfraFailure(Throwable e) {
        if (e instanceof ProbeTimeoutException) {
            return false;
        }
        return fallbackEligibility.isEligible(e);
    }

    /** 当前状态（委托给已有实现） */
    public CircuitBreakerState getState() {
        return registry.stateOf(candidateId);
    }

    /**
     * 探测成功回调 — 仅在 HALF_OPEN 状态下记录成功（触发 HALF_OPEN → CLOSED 转换）。
     * <p>
     * 在 CLOSED 状态下为空操作（no-op），不影响熔断计数。
     * 由 {@link ProbeHandler#wrap} 在首包到达时通过 {@code onProbeSuccess} 回调调用。
     * <p>
     * <b>与 {@code doOnComplete → recordSuccess} 的关系</b>：
     * <ul>
     *   <li>{@code recordProbeSuccess} 在首包到达时触发，用于快速恢复 HALF_OPEN</li>
     *   <li>{@code doOnComplete → recordSuccess} 在流正常结束时触发，用于 CLOSED 状态的持续健康记录</li>
     *   <li>两者在 HALF_OPEN 下可能重复调用 {@code registry.recordSuccess()}，
     *       但已有 {@code ModelCircuitBreakerRegistry.recordSuccess()} 是幂等的（仅改变状态，不累加计数）</li>
     * </ul>
     */
    public void recordProbeSuccess() {
        if (registry.stateOf(candidateId) == CircuitBreakerState.HALF_OPEN) {
            registry.recordSuccess(candidateId);
            log.info("Circuit breaker for '{}' recovered: HALF_OPEN → CLOSED (probe success)", candidateId);
        }
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
 */
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
 * </ul>
 * <p>
 * <b>探测成功路径（HALF_OPEN → CLOSED 恢复）</b>：
 * <ul>
 *   <li>首包到达 → 已有 ProbeStreamHandler 内部取消超时 timer → 返回带首包的 Flux</li>
 *   <li>ProbeHandler 在 {@code doOnNext(first)} 中调用 {@code onProbeSuccess} 回调</li>
 *   <li>回调委托给 {@link CircuitBreaker#recordProbeSuccess()} —— 仅在 HALF_OPEN 状态下
 *       调用底层 {@code registry.recordSuccess(candidateId)}，将熔断器转回 CLOSED</li>
 *   <li>正常 CLOSED 状态下的调用为空操作（no-op），不影响熔断计数</li>
 * </ul>
 * <p>
 * <b>探测失败路径</b>：
 * <ul>
 *   <li>首包超时 → ProbeStreamHandler 调用 breakers.recordFailure() + 抛出 ProbeTimeoutException</li>
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
     * 包装 Flux，添加首包超时检测 + 探测去重 + 成功回调
     * <p>
     * 探测去重语义：
     * <ul>
     *   <li>已有探测成功 → 跳过探测，直接发流（模型已验证可用，无需重复探测）</li>
     *   <li>已有探测失败 → 跳过探测，直接发流（探测失败可能是并发条件导致，
     *       当前请求让下游 retryStream 处理，而非立即报错）</li>
     *   <li>无在飞探测 → 正常委托给 ProbeStreamHandler 进行首包探测</li>
     * </ul>
     *
     * @param candidateId    用于日志、熔断记录、探测去重 key
     * @param raw            原始流式响应
     * @param onProbeSuccess 首包到达后的成功回调（用于 HALF_OPEN → CLOSED 状态转换），
     *                       为 null 时不添加回调（非 CHAT 能力无探测场景）
     * @return 带首包探测的 Flux
     */
    public Flux<String> wrap(String candidateId, Flux<String> raw,
                              @Nullable Runnable onProbeSuccess) {
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
        Flux<String> probed = delegate.wrapWithProbe(candidateId, raw);

        // 首包到达时触发成功回调（HALF_OPEN → CLOSED 状态转换）
        if (onProbeSuccess != null) {
            AtomicBoolean notified = new AtomicBoolean(false);
            return probed.doOnNext(first -> {
                if (notified.compareAndSet(false, true)) {
                    onProbeSuccess.run();
                }
            });
        }
        return probed;
    }
}
```



### 7.7 Resilient 装饰器

> **设计决策**：
> 1. 装饰器**只实现能力接口**（`ChatCapable` / `EmbeddingCapable` / `RerankCapable`），不继承被装饰者的抽象类。
>    调用方通过接口交互，无法区分原始客户端和弹性包装——这正是装饰器的透明性。
> 2. 三个 Resilient 装饰器共享 `AbstractResilientClient<T>` 基类，消除 `CapabilityClient` 委托的 DRY 违反。
> 3. **`ResilientChatClient` 同时实现 `ChatCapable` 和 `ToolCallingCapable`**。
>    工具调用已是主流大模型标配能力，`chatWithTools()` 与 `chat()` 走统一的弹性保护路径（重试 + 熔断）。
>    当底层 delegate 不支持工具调用时，`chatWithTools()` 抛出 `UnsupportedOperationException`——
>    这是能力缺失的正确语义，而非 LSP 违反。
> 4. Spring AI `ChatModel` 桥接代码集中在 `ChatModelAdapter`（§5.5），Resilient 装饰器不感知 Spring AI。

```java
package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.*;
import reactor.core.publisher.Flux;

/**
 * AbstractResilientClient — 弹性装饰器公共基类
 * <p>
 * 消除三个 Resilient 装饰器中 {@code CapabilityClient} 委托的 DRY 违反。
 * 泛型 {@code <T>} 约束为 {@code CapabilityClient} 的子接口（如 {@code ChatCapable}），
 * 子类通过 {@code extends AbstractResilientClient<ChatCapable>} 获得统一的委托实现。
 */
abstract class AbstractResilientClient<T extends CapabilityClient> implements CapabilityClient {

    protected final T delegate;
    protected final CircuitBreaker circuitBreaker;
    protected final RetryPolicy retryPolicy;

    protected AbstractResilientClient(T delegate, CircuitBreaker circuitBreaker, RetryPolicy retryPolicy) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retryPolicy = retryPolicy;
    }

    @Override public String candidateId() { return delegate.candidateId(); }
    @Override public String providerId() { return delegate.providerId(); }
    @Override public String modelName() { return delegate.modelName(); }
    @Override public LlmCapability capability() { return delegate.capability(); }
    @Override public boolean isAvailable() {
        return delegate.isAvailable()
            && circuitBreaker.getState() != CircuitBreakerState.OPEN;
    }

    @Override public void close() { delegate.close(); }
}

/**
 * ResilientChatClient — Chat 能力的弹性装饰器（实现 ChatCapable + ToolCallingCapable）
 * <p>
 * 工具调用已是主流大模型标配能力，chatWithTools() 与 chat() 共享同一弹性保护路径。
 * 当底层 delegate 不支持 {@code ToolCallingCapable} 时，{@code chatWithTools()} 抛出
 * {@code UnsupportedOperationException}——这是能力缺失的正确语义。
 * <p>
 * 重试/熔断保护由 {@code AbstractResilientClient} 统一提供，chat 和 chatWithTools 两条路径
 * 使用同一 {@code CircuitBreaker} + {@code RetryPolicy} 实例。
 * <p>
 * 策略矩阵：
 * <pre>
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ chat         │ 指数退避重试   │ ✓            │ ✗（阻塞式）   │
 * │ chatStream   │ 指数退避重试   │ ✓            │ ✓ ProbeHandler│
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 * </pre>
 */
public class ResilientChatClient extends AbstractResilientClient<ChatCapable>
        implements ChatCapable, ToolCallingCapable {

    private final ProbeHandler probeHandler;

    public ResilientChatClient(ChatCapable delegate,
                                CircuitBreaker circuitBreaker,
                                RetryPolicy retryPolicy,
                                ProbeHandler probeHandler) {
        super(delegate, circuitBreaker, retryPolicy);
        this.probeHandler = probeHandler;
    }

    /** 底层 delegate 是否支持工具调用（Registry 层用于过滤） */
    public boolean delegateSupportsToolCalling() {
        return delegate instanceof ToolCallingCapable;
    }

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
                    ? probeHandler.wrap(candidateId(), raw, circuitBreaker::recordProbeSuccess)
                    : raw;
            })
        );
    }

    // ======== Tool Calling 操作（统一弹性路径，不绕过 Resilience） ========

    @Override
    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        if (!(delegate instanceof ToolCallingCapable tc)) {
            throw new UnsupportedOperationException(
                "Delegate '" + candidateId() + "' does not support tool calling");
        }
        return circuitBreaker.execute(() ->
            retryPolicy.executeWithBackoff(() ->
                tc.chatWithTools(request, tools)
            )
        );
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }
}
```

> **工具调用统一弹性路径**：工具调用已是现代大模型的标配能力，`ResilientChatClient`
> 同时实现 `ChatCapable` 和 `ToolCallingCapable`，`chat()` 与 `chatWithTools()`
> 享有完全相同的弹性保护（重试/熔断）。不再存在绕过 Resilience 的独立路径。
> Registry 的 `getToolCallingClient()` 返回 `ResilientChatClient` 本身（非原始 delegate），
> 调用方无需感知装饰器存在。
>
> **三个装饰器**各有独立类型。Chat 装饰器同时实现 `ChatCapable` + `ToolCallingCapable`，
> Embedding / Rerank 装饰器仅实现对应的能力接口。

```java
package com.smart.rag.infrastructure.llm.resilience;

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
public class ResilientEmbeddingClient extends AbstractResilientClient<EmbeddingCapable> implements EmbeddingCapable {

    public ResilientEmbeddingClient(EmbeddingCapable delegate,
                                      CircuitBreaker circuitBreaker,
                                      RetryPolicy retryPolicy) {
        super(delegate, circuitBreaker, retryPolicy);
    }

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
package com.smart.rag.infrastructure.llm.resilience;

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
public class ResilientRerankClient extends AbstractResilientClient<RerankCapable> implements RerankCapable {

    public ResilientRerankClient(RerankCapable delegate,
                                   CircuitBreaker circuitBreaker,
                                   RetryPolicy retryPolicy) {
        super(delegate, circuitBreaker, retryPolicy);
    }

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

### 7.8 Capability 策略（消除 switch 扩展瓶颈）

**问题**：`GenericOpenAiProvider.createClient()`、`LlmClientRegistry.wrapWithResilience()`、`LlmConfig.modelGroup()` 三处均使用 `switch(capability)` 硬编码能力分支。每新增一个能力（如 TTS、IMAGE）需同时改 3+ 处，违反 OCP。

**方案**：引入 `CapabilityStrategy` 接口，每种能力注册独立策略，新增能力只需添加一个策略实现。

```java
package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.*;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.config.ResilienceConfig;
import com.smart.rag.infrastructure.llm.resilience.*;

/**
 * 能力策略 — 封装特定能力的客户端创建与 Resilient 包装逻辑。
 * <p>
 * 每种 {@link LlmCapability} 注册一个实现，
 * 新增能力只需添加策略 Bean，无需修改 Provider 或 Registry 中的 switch。
 * <p>
 * 注册方式：{@code @Component} + Spring 自动收集，或手动注册到
 * {@link CapabilityStrategyRegistry}。
 */
public interface CapabilityStrategy {

    /** 此策略负责的能力类型 */
    LlmCapability capability();

    /**
     * 端点选择：从 ProviderConfig 中取出此能力对应的 endpoint。
     * <p>
     * 由 {@link LlmProvider#createClient(ModelCandidate)} 内部调用——
     * Provider 持有 {@code ProviderConfig}（包含 url + EndpointConfig），
     * 通过 Strategy 解析出具体能力的端点路径，再传回 {@code createClient()}。
     */
    String resolveEndpoint(ProviderConfig config);

    /**
     * 客户端创建：基于 Provider 解析后的连接参数 + candidate 构建原始 CapabilityClient。
     * <p>
     * 调用方是 {@link GenericOpenAiProvider#createClient(ModelCandidate)}：
     * Provider 从自身 {@code config()} 中解析 {@code baseUrl}、{@code apiKey}、{@code endpoint}，
     * 然后传递给此方法。Strategy 不感知 ProviderConfig，只消费已解析的参数——
     * 这保持了 {@link LlmProvider#createClient(ModelCandidate)} 签名的简洁性。
     */
    CapabilityClient createClient(String baseUrl, String endpoint,
                                  String apiKey, ModelCandidate candidate);

    /** Resilient 包装：将原始 Client 包装为带重试/熔断的装饰器 */
    CapabilityClient wrapWithResilience(CapabilityClient raw,
                                        CircuitBreaker circuitBreaker,
                                        RetryPolicy retryPolicy,
                                        @Nullable ProbeHandler probeHandler);
}
```

**三个内置实现**：

```java
/** CHAT 策略 — ChatCapable + ToolCallingCapable 自动检测 */
@Component
public class ChatCapabilityStrategy implements CapabilityStrategy {

    @Override public LlmCapability capability() { return LlmCapability.CHAT; }

    @Override
    public String resolveEndpoint(ProviderConfig config) {
        return config.endpoints().get(capability());
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        return new GenericChatClient(baseUrl, endpoint, apiKey, candidate);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe) {
        // supportsToolCalling 由 ResilientChatClient 内部通过
        // delegate instanceof ToolCallingCapable 自动检测，无需外部传入
        return new ResilientChatClient((ChatCapable) raw, cb, retry, probe);
    }
}

/** RERANKING 策略 */
@Component
public class RerankCapabilityStrategy implements CapabilityStrategy {

    @Override public LlmCapability capability() { return LlmCapability.RERANKING; }

    @Override
    public String resolveEndpoint(ProviderConfig config) {
        return config.endpoints().get(capability());
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        return new GenericRerankClient(baseUrl, endpoint, apiKey, candidate);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe) {
        return new ResilientRerankClient((RerankCapable) raw, cb, retry);
    }
}
```

**供应商差异化扩展点**（`ProviderClientFactory`）：

> 当供应商的某个能力使用原生 API 比 OpenAI 兼容 API 更优时，
> 实现此接口并注册为 `@Component`，CapabilityStrategy 自动发现并委托。
> 未注册工厂的能力 → Strategy 使用通用 Client（`GenericXxxClient`）。

```java
/**
 * 供应商专用客户端工厂 — CapabilityStrategy 的扩展点
 * <p>
 * 用于供应商（如百炼）的特定能力需要使用原生 API 而非 OpenAI 兼容 API 的场景。
 * 通过 {@code @Component} 自注册，Strategy 在 {@code createClient()} 时自动发现。
 * <p>
 * <b>查找键</b>：{@code providerId() + ":" + capability()} 组合唯一确定一个工厂。
 * 同一供应商的不同能力可以注册不同的工厂（如百炼 Embedding 和 Reranking 各自独立）。
 */
public interface ProviderClientFactory {
    /** 此工厂负责的供应商 id（对应 YAML providers key） */
    String providerId();

    /** 此工厂负责的能力类型 */
    LlmCapability capability();

    /**
     * 创建专用客户端（使用原生 API）
     *
     * @param apiKey    供应商 API Key
     * @param candidate 模型候选声明
     * @return 专用能力客户端（已实现对应的 CapabilityClient 子接口）
     */
    CapabilityClient create(String apiKey, ModelCandidate candidate);
}
```

**内置策略的 createClient() 改造**（以 Embedding 为例）：

```java
@Component
public class EmbeddingCapabilityStrategy implements CapabilityStrategy {

    /** 供应商专用工厂索引（key = "providerId:capability"） */
    private final Map<String, ProviderClientFactory> providerFactories;

    public EmbeddingCapabilityStrategy(List<ProviderClientFactory> factories) {
        this.providerFactories = factories.stream()
            .filter(f -> f.capability() == LlmCapability.EMBEDDING)
            .collect(Collectors.toUnmodifiableMap(
                f -> f.providerId() + ":" + f.capability(),
                Function.identity()));
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        // 有专用工厂 → 委托（原生 API，保留 text_type / instruct 等特性）
        ProviderClientFactory factory = providerFactories.get(
            candidate.provider() + ":" + capability());
        if (factory != null) {
            return factory.create(apiKey, candidate);
        }
        // 通用路径 → OpenAI 兼容 Client
        return new GenericEmbeddingClient(baseUrl, endpoint, apiKey, candidate);
    }

    // ... 其他方法不变
}
```

**策略注册表**（替代 Registry 中的 switch）：

```java
/**
 * 能力策略注册表 — 自动收集所有 CapabilityStrategy Bean
 * <p>
 * 新增能力只需添加 {@code @Component} 策略实现，
 * 无需修改 Registry、Provider 或 Properties 中的任何 switch。
 */
@Component
public class CapabilityStrategyRegistry {

    private final Map<LlmCapability, CapabilityStrategy> strategies;

    public CapabilityStrategyRegistry(List<CapabilityStrategy> strategyList) {
        this.strategies = strategyList.stream()
            .collect(Collectors.toUnmodifiableMap(
                CapabilityStrategy::capability, Function.identity()));
    }

    public CapabilityStrategy get(LlmCapability cap) {
        CapabilityStrategy s = strategies.get(cap);
        if (s == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No CapabilityStrategy registered for " + cap);
        }
        return s;
    }

    public boolean hasStrategy(LlmCapability cap) {
        return strategies.containsKey(cap);
    }
}
```

> **扩展示例**：
> - **新增能力**：(1) 在 `LlmCapability` 添加枚举值，(2) 在 YAML `capabilities` 和 `endpoints` 中添加对应条目，(3) 实现 `CapabilityStrategy @Component`。系统自动注册——无需修改任何现有代码（详见 §7.1）。
> - **新增供应商差异化 Client**：(1) 实现 `ProviderClientFactory @Component`（声明 providerId + capability + create），(2) 实现专用 Client。Strategy 自动发现工厂并委托，无需修改 Strategy 或 Provider 代码（详见 §8.3）。

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
 * 按 {@code candidate.capability()} 通过 {@link CapabilityStrategy} 创建对应客户端。
 * <b>供应商差异化由 Strategy 层处理</b>——CapabilityStrategy 内置 {@code ProviderClientFactory}
 * 扩展点，有专用工厂时委托工厂（如百炼 Embedding 使用原生 API），否则走通用 Client（OpenAI 兼容）。
 * Provider 层无 switch，无硬编码分支。
 */
public class GenericOpenAiProvider implements LlmProvider {

    private final String id;
    private final ProviderConfig config;
    private final CapabilityStrategyRegistry strategyRegistry;

    /** 唯一构造函数。id 从 YAML Map key 传入；strategyRegistry 由 Spring 自动注入。 */
    public GenericOpenAiProvider(String id, ProviderConfig config,
                                  CapabilityStrategyRegistry strategyRegistry) {
        this.id = id;
        this.config = config;
        this.strategyRegistry = strategyRegistry;
    }

    @Override public String id() { return id; }
    @Override public ProviderConfig config() { return config; }

    @Override
    public CapabilityClient createClient(ModelCandidate candidate) {
        CapabilityStrategy strategy = strategyRegistry.get(candidate.capability());
        String endpoint = strategy.resolveEndpoint(config);
        if (endpoint == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Provider '" + id() + "' missing endpoint for " + candidate.capability());
        }
        // 统一由 CapabilityStrategy 创建客户端，无硬编码分支
        return strategy.createClient(config.url(), endpoint, config.apiKey(), candidate);
    }
}

**多供应商注册器**（为没有对应 `@Component` Bean 的 YAML 条目创建 `GenericOpenAiProvider`）：

```java
package com.smart.rag.infrastructure.llm.provider.generic;

import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.TypeReference;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 通用 Provider 注册器
 * <p>
 * 扫描 YAML 中 {@code app.llm.providers} 的所有条目，
 * 为没有对应 {@code @Component} {@link LlmProvider} Bean 的 id
 * 创建 {@link GenericOpenAiProvider} 并注册为独立 Bean。
 * <p>
 * <b>Spring 生命周期与冲突策略</b>：
 * {@code BeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry()}
 * 在常规 Bean 定义加载前执行——此时 {@code @Component} 标注的 Provider 尚未扫描。
 * 因此 {@code loadGenericConfigs()} 使用约定式过滤：检查 Registry 中是否已存在
 * 同名 Bean 定义（如 XML 或其他 PostProcessor 提前注册的）。
 * <p>
 * 执行顺序保证：{@code @Component} Bean 由 ClassPathBeanDefinitionScanner 扫描，
 * 其 Bean 定义在 {@code postProcessBeanDefinitionRegistry()} 返回后才被处理；
 * 若两者 id 冲突，{@code @Component} 的 Bean 定义后注册、后处理，
 * Spring 容器按"后注册覆盖先注册"策略，{@code @Component} 实例优先。
 * <b>建议</b>：保持 YAML id 与 {@code @Component} Provider 的 {@code id()} 互斥，
 * 在文档中明确约定，避免依赖覆盖行为。
 */
@Configuration
public class GenericOpenAiProviderRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private Environment environment;
    private BeanDefinitionRegistry registry;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        this.registry = registry;
        for (var entry : loadGenericConfigs().entrySet()) {
            GenericBeanDefinition bd = new GenericBeanDefinition();
            bd.setBeanClass(GenericOpenAiProvider.class);
            bd.getConstructorArgumentValues().addIndexedArgumentValue(0, entry.getKey());
            bd.getConstructorArgumentValues().addIndexedArgumentValue(1, entry.getValue());
            // 第 3 个参数 CapabilityStrategyRegistry 由 Spring 自动装配注入
            bd.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            registry.registerBeanDefinition("llmProvider_" + entry.getKey(), bd);
        }
    }

    private Map<String, ProviderConfig> loadGenericConfigs() {
        // 1. 从 Environment 绑定 app.llm.providers
        Map<String, ProviderConfig> allProviders = Binder.get(environment)
            .bind("app.llm.providers", new TypeReference<Map<String, ProviderConfig>>() {})
            .orElse(Map.of());

        // 2. 过滤：排除已有 Bean 定义的供应商（如 @Component 标注的自定义 Provider）
        return allProviders.entrySet().stream()
            .filter(entry -> {
                String beanName = "llmProvider_" + entry.getKey();
                String altBeanName = entry.getKey() + "Provider";
                if (registry.containsBeanDefinition(beanName) || registry.containsBeanDefinition(altBeanName)) {
                    log.warn("Skipping YAML provider '{}': a @Component LlmProvider with the same id " +
                             "already exists. If unintentional, ensure YAML provider id and " +
                             "@Component LlmProvider.id() are mutually exclusive.", entry.getKey());
                    return false;
                }
                return true;
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
```


### 8.2 通用 Client 定义

> **设计决策**：三个通用 Client 分别继承对应的抽象基类，封装 OpenAI 兼容 API 的 HTTP 调用。
> 由 `CapabilityStrategy.createClient()` 按 endpoint 统一创建，不需要 Spring Bean 注册。

```java
package com.smart.rag.infrastructure.llm.client.generic;

/**
 * 通用 Chat 客户端 — OpenAI 兼容 /chat/completions API。
 * <p>
 * 支持 ToolCallingCapable（工具调用），由 {@code GenericOpenAiProvider} 创建。
 */
public class GenericChatClient extends AbstractChatClient implements ToolCallingCapable {

    private final RestClient httpClient;

    public GenericChatClient(String baseUrl, String endpoint,
                              String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.httpClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }

    @Override
    public LlmResponse chat(ChatRequest request) {
        // POST endpoint，序列化 OpenAI 兼容请求体，反序列化响应
        ...
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        // SSE 流式请求，使用 OkHttp 或 WebClient
        ...
    }

    @Override
    public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
        // POST endpoint，携带 tools 参数
        ...
    }
}

/**
 * 通用 Embedding 客户端 — 纯 OpenAI 兼容 /embeddings API。
 * <p>
 * 适用于所有标准 OpenAI 兼容 Embedding 端点（DeepSeek、SiliconFlow 等）。
 * 百炼等需要原生 API 特性（text_type / instruct）的供应商，
 * 由 {@code CapabilityStrategy} 通过 {@code ProviderClientFactory} 委托给专用 Client（见 §8.3）。
 */
public class GenericEmbeddingClient extends AbstractEmbeddingClient {

    /** OpenAI 兼容端点保守默认批量大小（v3/v4 限制为 10，v1/v2 为 25） */
    private static final int DEFAULT_BATCH_SIZE = 10;
    private final RestClient httpClient;

    public GenericEmbeddingClient(String baseUrl, String endpoint,
                                   String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.httpClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }

    @Override
    public float[] embed(String text, EmbeddingType type) {
        // POST /embeddings, input 为单条字符串
        // OpenAI 兼容端点不支持 text_type，忽略 type 参数
        ...
    }

    /**
     * 批量嵌入：分片后每片一次 HTTP 调用（input 为字符串数组）。
     * <p>
     * OpenAI 兼容 API 支持 {@code input: ["text1", "text2", ...]} 数组输入，
     * 响应 {@code data[]} 按 {@code index} 与输入一一对应。
     * 分片大小由 {@code DEFAULT_BATCH_SIZE} 控制（保守默认 10）。
     */
    @Override
    public List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += DEFAULT_BATCH_SIZE) {
            List<String> shard = texts.subList(i, Math.min(i + DEFAULT_BATCH_SIZE, texts.size()));
            // 一次 HTTP 调用，input = ["text1", "text2", ...]
            List<float[]> shardResult = callEmbeddingsApi(shard);
            result.addAll(shardResult);
        }
        return result;
    }
}

/**
 * 通用 Rerank 客户端 — 兼容 Cohere / Jina rerank API。
 */
public class GenericRerankClient extends AbstractRerankClient {

    private final RestClient httpClient;

    public GenericRerankClient(String baseUrl, String endpoint,
                                String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.httpClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        // POST endpoint，序列化 rerank 请求体，反序列化响应
        ...
    }
}
```


### 8.3 供应商差异化：Strategy 驱动的 Client 选择

> **设计决策**：部分供应商（如百炼）同时提供 OpenAI 兼容 API 和原生 API，
> 原生 API 在特定能力上具有不可替代的优势。为避免在 Provider 层引入 `switch(capability)`，
> 差异化逻辑下沉到 `CapabilityStrategy` 层——通过 `ProviderClientFactory` 扩展点实现。

**机制**：`CapabilityStrategy.createClient()` 在创建 Client 时，
先检查是否有该供应商的专用工厂（`ProviderClientFactory`），有则委托专用工厂（使用原生 API），
否则走通用路径（`GenericXxxClient`，OpenAI 兼容）。Provider 层完全无感知。

**百炼的能力矩阵**：

| 能力 | API 风格 | Client | 理由 |
|------|---------|--------|------|
| Chat | OpenAI 兼容 | `GenericChatClient` | `/compatible-mode/v1/chat/completions` 完全兼容 |
| Embedding | **DashScope 原生** | `BailianEmbeddingClient` | 原生 API 支持 `text_type`（query/document 自动推断）、`instruct`、`output_type`（sparse embedding），OpenAI 兼容端点不支持这些参数 |
| Reranking | **DashScope 原生** | `BailianRerankClient` | 原生 API 支持 `instruct`（排序策略引导）、`return_documents`，OpenAI 兼容端点的参数支持有限 |

**百炼 Embedding 专用工厂**（`@Component` 自注册）：

```java
@Component
public class BailianEmbeddingClientFactory implements ProviderClientFactory {

    @Override public String providerId() { return "bailian"; }
    @Override public LlmCapability capability() { return LlmCapability.EMBEDDING; }

    @Override
    public CapabilityClient create(String apiKey, ModelCandidate candidate) {
        // 复用现有 DashScopeEmbeddingModel 核心逻辑，适配 EmbeddingCapable 接口
        return new BailianEmbeddingClient(apiKey, candidate);
    }
}
```

`BailianEmbeddingClient` 使用 DashScope 原生 API（`POST /api/v1/services/embeddings/text-embedding/text-embedding`）：
- **text_type**：自动推断 `query` / `document`（从 `EmbeddingType` 映射），提升非对称检索精度
- **instruct**：任务说明引导，通常带来 1%-5% 效果提升
- **批量大小**：`MAX_BATCH_SIZE = 10`（text-embedding-v3/v4 限制），一次 HTTP 调用传入文本数组
- **sparse embedding**：可选，`output_type = "dense&sparse"` 返回离散向量（适用于混合检索）

**百炼 Reranking 专用工厂**（`@Component` 自注册）：

```java
@Component
public class BailianRerankClientFactory implements ProviderClientFactory {

    @Override public String providerId() { return "bailian"; }
    @Override public LlmCapability capability() { return LlmCapability.RERANKING; }

    @Override
    public CapabilityClient create(String apiKey, ModelCandidate candidate) {
        // 复用现有 BailianRerankPostProcessor 核心逻辑，适配 RerankCapable 接口
        return new BailianRerankClient(apiKey, candidate);
    }
}
```

`BailianRerankClient` 使用 DashScope 原生 API（`POST /api/v1/services/rerank/text-rerank/text-rerank`）：
- **instruct**：排序策略引导（如 `"Retrieve semantically similar text."` 用于语义相似度场景）
- **top_n**：服务端截断（减少网络传输），覆写 `RerankCapable.rerank(request, topN)` 默认实现
- **return_documents**：可选，返回排序后的文档内容

**YAML 配置**：百炼的 `endpoints` 只需声明 `chat`，embedding 和 reranking 由工厂内部管理（见 §10.2）。

**扩展新供应商时的决策树**：
```
该供应商是纯 OpenAI 兼容？
├── 是 → 零代码，YAML 配置即可（GenericOpenAiProvider + GenericXxxClient）
└── 否 → 某个能力需要原生 API 特性？
    ├── 是 → 写 ProviderClientFactory（~40 行）+ 专用 Client（~80 行），@Component 自注册
    └── 全部非标准 → 写 @Component LlmProvider（见 §13 场景 B）
```

**消除的魔法值**：
- ❌ `endpoint.contains("/services/embeddings/")` 硬编码判断
- ❌ `DEFAULT_BATCH_SIZE = 25`（v3/v4 实际限制为 10）
- ❌ `text_type` → `encoding_format` 错误映射（OpenAI 兼容端点不支持 text_type）
- ❌ `/rerank` 端点路径错误（百炼实际为 `/reranks` 或原生 `/text-rerank`）
- ✅ Strategy 工厂按供应商 × 能力精确选择 Client，通用 Client 保持纯 OpenAI 兼容
## 9. Registry — 统一查询 + 快照管理（SRP 拆分）

> **SRP 拆分**：原 `LlmClientRegistry` 职责过多（创建 + 包装 + 注册 + 查询 + 禁用 + 刷新 + 销毁）。
> 拆分为两个类：
> - **`LlmClientFactory`**（新建）：负责 `buildSnapshot()` + `wrapWithResilience()` + `validateCandidateReferences()`——
>   纯粹的客户端创建与 Resilient 包装逻辑，无状态，可独立测试
> - **`LlmClientRegistry`**（保留）：负责查询（`getDefault` / `get` / `getFallbackChain` / `getChainFor`）+ 
>   快照管理（`disableCandidate` / `enableCandidate` / `refresh` / `destroy`）——
>   持有 `AtomicReference<RegistrySnapshot>`，委托 `LlmClientFactory` 构建快照
>
> 这确保了 `LlmClientRegistry` 的单一职责：**查询与状态管理**。
> 创建逻辑的变化（新增能力类型、新增 Provider）不影响 Registry 的查询 API。

```java
package com.smart.rag.infrastructure.llm.registry;

/**
 * 不可变注册表快照 — 所有读操作基于此对象，无锁安全。
 *
 * @param clientsById      candidateId → ResilientClient（不可变 Map）
 * @param fallbackChains   capability → 按 priority 排序的客户端列表（不可变）
 * @param defaultClients   capability → 默认客户端（不可变）
 * @param deepThinking     深度思考模型（chat 组专用，可为 null）
 * @param disabledSet      运行时动态禁用的 candidateId 集合（不可变）
 */
record RegistrySnapshot(
    Map<String, CapabilityClient> clientsById,
    Map<LlmCapability, List<CapabilityClient>> fallbackChains,
    Map<LlmCapability, CapabilityClient> defaultClients,
    @Nullable CapabilityClient deepThinking,
    Set<String> disabledSet
) {
    static final RegistrySnapshot EMPTY = new RegistrySnapshot(
        Map.of(), Map.of(), Map.of(), null, Set.of());

    boolean isDisabled(String candidateId) {
        return disabledSet.contains(candidateId);
    }
}

/**
 * LLM 客户端工厂 — 无状态的客户端创建与 Resilient 包装（SRP 拆分自 LlmClientRegistry）
 * <p>
 * 负责三个纯函数：{@code buildSnapshot()} + {@code wrapWithResilience()} + {@code validateCandidateReferences()}。
 * 不持有可变状态，可独立测试。{@code LlmClientRegistry} 委托本类构建快照。
 */
@Component
class LlmClientFactory {

    private final CapabilityStrategyRegistry strategyRegistry;

    public LlmClientFactory(CapabilityStrategyRegistry strategyRegistry) {
        this.strategyRegistry = strategyRegistry;
    }

    /** 构建完整快照（遍历所有能力组 → 创建客户端 → 包装 Resilient → 组装不可变快照） */
    RegistrySnapshot buildSnapshot(
            LlmConfig properties,
            Map<String, LlmProvider> providerMap,
            LlmCircuitBreakerAdapterRegistry cbRegistry,
            ProbeHandler probeHandler,
            Set<String> disabled) {

        Map<String, CapabilityClient> clientsById = new LinkedHashMap<>();
        Map<LlmCapability, List<CapabilityClient>> fallbackChains = new EnumMap<>(LlmCapability.class);
        Map<LlmCapability, CapabilityClient> defaultClients = new EnumMap<>(LlmCapability.class);

        for (LlmCapability cap : LlmCapability.values()) {
            if (!strategyRegistry.hasStrategy(cap)) continue;
            ModelGroup group = properties.modelGroup(cap);
            if (group == null) continue;

            CapabilityStrategy strategy = strategyRegistry.get(cap);
            List<CapabilityClient> chain = new ArrayList<>();

            for (ModelCandidate candidate : group.orderedCandidates()) {
                if (!candidate.enabled()) {
                    log.info("Candidate '{}' disabled by config (enabled=false)", candidate.id());
                    continue;
                }
                LlmProvider provider = providerMap.get(candidate.provider());
                if (!provider.config().isAvailable()) {
                    log.warn("Provider '{}' unavailable, skipping candidate '{}'",
                        candidate.provider(), candidate.id());
                    continue;
                }

                CapabilityClient raw = provider.createClient(candidate);
                CapabilityClient wrapped = wrapWithResilience(
                    raw, strategy, properties.resilience(), cbRegistry, probeHandler);

                clientsById.put(candidate.id(), wrapped);
                chain.add(wrapped);
            }
            fallbackChains.put(cap, Collections.unmodifiableList(chain));
        }

        // default-model 解析
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

        // deep-thinking-model 解析（带 fallback：候选不存在或被禁用时回退到 chat default-model）
        CapabilityClient deepThinking = null;
        ModelGroup chatGroup = properties.capabilities().get(LlmCapability.CHAT);
        if (chatGroup != null && chatGroup.deepThinkingModel() != null) {
            deepThinking = clientsById.get(chatGroup.deepThinkingModel());
            if (deepThinking == null) {
                // deep-thinking-model 引用的候选不存在（被禁用或创建失败），回退到 chat default-model
                log.warn("deep-thinking-model '{}' not found in clients, falling back to default-model",
                    chatGroup.deepThinkingModel());
                deepThinking = defaultClients.get(LlmCapability.CHAT);
            }
        }

        return new RegistrySnapshot(
            Collections.unmodifiableMap(clientsById),
            Collections.unmodifiableMap(fallbackChains),
            Collections.unmodifiableMap(defaultClients),
            deepThinking,
            Set.copyOf(disabled));
    }

    /** 策略驱动的 Resilient 包装 — 无 switch 分支 */
    CapabilityClient wrapWithResilience(
            CapabilityClient raw,
            CapabilityStrategy strategy,
            ResilienceConfig resilience,
            LlmCircuitBreakerAdapterRegistry cbRegistry,
            ProbeHandler probeHandler) {

        RetryPolicy retryPolicy = new RetryPolicy(resilience.resolveRetryConfig(strategy.capability()));
        CircuitBreaker cb = cbRegistry.getOrCreate(raw.candidateId());
        return strategy.wrapWithResilience(raw, cb, retryPolicy, probeHandler);
    }

    /** 配置引用校验（fail-fast，§10.4） */
    void validateCandidateReferences(LlmConfig properties, Set<String> providerIds) {
        // 见 §10.4 配置校验
    }
}

/**
 * LLM 客户端注册表
 * <p>
 * 启动时遍历 {@code LlmConfig} 中的 {@code ModelGroup}，
 * 按 {@code candidate.provider} 查找 {@code LlmProvider}，
 * 通过 Provider 创建原始客户端，统一包装 Resilient 装饰器。
 * <p>
 * <b>核心设计</b>：
 * <ul>
 *   <li>不可变快照：{@code AtomicReference<RegistrySnapshot>} 保证读操作无锁、线程安全</li>
 *   <li>策略驱动：通过 {@link CapabilityStrategyRegistry} 消除所有 switch 分支</li>
 *   <li>声明式 + 运行时禁用：YAML {@code enabled: false} 声明式；
 *       {@code disableCandidate()} 运行时动态禁用（通过快照重建）</li>
 * 注册表示例：
 * <pre>
 * candidateId        | provider  | Client 类型              | Capability
 * -------------------|-----------|--------------------------|--------------
 * qwen-plus          | bailian   | GenericChatClient        | CHAT
 * deepseek-v4-flash  | deepseek  | GenericChatClient        | CHAT
 * qwen3-local        | ollama    | GenericChatClient        | CHAT
 * qwen3-max          | bailian   | GenericChatClient        | CHAT
 * qwen-emb-8b        | bailian   | BailianEmbeddingClient   | EMBEDDING  ← Strategy 工厂创建
 * qwen3-rerank       | bailian   | BailianRerankClient      | RERANKING  ← Strategy 工厂创建
 * </pre>
 */
@Component
public class LlmClientRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    /** 不可变快照 — 所有读操作通过 snapshot.xxx() 无锁访问 */
    private final AtomicReference<RegistrySnapshot> snapshotRef;
    /** 弹性层组件（跨快照复用，不随 refresh 重建） */
    private final LlmCircuitBreakerAdapterRegistry circuitBreakers;
    private final CapabilityStrategyRegistry strategyRegistry;
    private final LlmClientFactory factory;

    public LlmClientRegistry(
            LlmConfig properties,
            List<LlmProvider> providers,
            LlmCircuitBreakerAdapterRegistry circuitBreakers,
            ProbeHandler probeHandler,
            CapabilityStrategyRegistry strategyRegistry,
            LlmClientFactory factory) {

        this.circuitBreakers = circuitBreakers;
        this.strategyRegistry = strategyRegistry;
        this.factory = factory;

        // 1. 构建 provider id → LlmProvider 索引
        Map<String, LlmProvider> providerMap = providers.stream()
            .collect(Collectors.toMap(LlmProvider::id, p -> p));

        // 2. 引用校验（fail-fast）— 委托 LlmClientFactory
        factory.validateCandidateReferences(properties, providerMap.keySet());

        // 3. 构建首个快照 — 委托 LlmClientFactory
        RegistrySnapshot initial = factory.buildSnapshot(
            properties, providerMap, circuitBreakers, probeHandler, Set.of());
        this.snapshotRef = new AtomicReference<>(initial);

        log.info("LlmClientRegistry initialized: {} candidates, {} capabilities",
            initial.clientsById().size(), initial.fallbackChains().size());
    }

    // ====== 调用方 API（全部基于快照，无锁） ======

    /** 获取指定能力的默认客户端 */
    public <T extends CapabilityClient> T getDefault(LlmCapability cap, Class<T> type) {
        return type.cast(snapshotRef.get().defaultClients().get(cap));
    }

    /** 获取深度思考模型（仅 chat 能力），未配置时返回 Optional.empty() */
    public Optional<ChatCapable> getDeepThinkingModel() {
        CapabilityClient deep = snapshotRef.get().deepThinking();
        return Optional.ofNullable((ChatCapable) deep);
    }

    /**
     * 获取支持工具调用的 Chat 客户端（仅 CHAT 能力）。
     * <p>
     * 返回的 {@code ToolCallingCapable} 引用即为 {@link ResilientChatClient} 本身，
     * {@code chatWithTools()} 调用享有完整的弹性保护（重试/熔断），不绕过 Resilience 层。
     *
     * @param candidateId 候选模型 id
     * @return 支持工具调用的弹性客户端，不存在或不支持时返回 Optional.empty()
     */
    public Optional<ToolCallingCapable> getToolCallingClient(String candidateId) {
        return get(candidateId, ChatCapable.class)
            .filter(c -> c instanceof ResilientChatClient rcc && rcc.delegateSupportsToolCalling())
            .map(c -> (ToolCallingCapable) c);
    }

    /**
     * 获取 Chat Fallback Chain 中第一个支持工具调用的客户端。
     * <p>
     * 返回的 {@code ToolCallingCapable} 引用即为 {@link ResilientChatClient} 本身，
     * 享有完整的弹性保护（重试/熔断）。
     *
     * @return 第一个支持工具调用的弹性客户端，无可用时返回 Optional.empty()
     */
    public Optional<ToolCallingCapable> getFirstToolCallingClient() {
        return getFallbackChain(LlmCapability.CHAT, ChatCapable.class).stream()
            .filter(c -> c instanceof ResilientChatClient rcc && rcc.delegateSupportsToolCalling())
            .map(c -> (ToolCallingCapable) c)
            .findFirst();
    }

    /** 获取指定候选 id 的客户端（不存在或已禁用时返回 Optional.empty） */
    public <T extends CapabilityClient> Optional<T> get(String candidateId, Class<T> type) {
        RegistrySnapshot snap = snapshotRef.get();
        if (snap.isDisabled(candidateId)) {
            return Optional.empty();
        }
        CapabilityClient client = snap.clientsById().get(candidateId);
        return client == null ? Optional.empty() : Optional.of(type.cast(client));
    }

    /** 获取指定能力的 Fallback Chain（排除已禁用候选） */
    public <T extends CapabilityClient> List<T> getFallbackChain(LlmCapability cap, Class<T> type) {
        RegistrySnapshot snap = snapshotRef.get();
        return snap.fallbackChains().getOrDefault(cap, List.of()).stream()
            .filter(c -> !snap.isDisabled(c.candidateId()))
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    /**
     * 获取带首选模型的降级链
     * <p>
     * 封装“指定模型放首位 + 去重 + 追加 Fallback Chain”逻辑，
     * 供 ChatServiceImpl、IntentClassifier 等编排调用方复用。
     */
    public <T extends CapabilityClient> List<T> getChainFor(
            LlmCapability cap, Class<T> type, @Nullable String preferredId) {
        List<T> fullChain = getFallbackChain(cap, type);
        if (preferredId == null || preferredId.isBlank()) {
            return fullChain;
        }
        return get(preferredId, type)
            .map(preferred -> {
                List<T> chain = new ArrayList<>(fullChain.size() + 1);
                chain.add(preferred);
                fullChain.stream()
                    .filter(c -> !c.candidateId().equals(preferred.candidateId()))
                    .forEach(chain::add);
                return (List<T>) chain;
            })
            .orElse(fullChain);
    }

    // ====== 运行时禁用 API（通过快照重建实现） ======

    /**
     * 运行时禁用指定候选模型。
     * <p>
     * 通过重建不可变快照实现，不影响在飞请求（旧快照的引用仍有效）。
     * 禁用状态跨 {@code refresh()} 持久化。
     *
     * @return true 如果状态变更（之前未禁用）
     */
    public boolean disableCandidate(String candidateId) {
        while (true) {
            RegistrySnapshot prev = snapshotRef.get();
            if (prev.isDisabled(candidateId)) return false;

            Set<String> newDisabled = new HashSet<>(prev.disabledSet());
            newDisabled.add(candidateId);

            RegistrySnapshot next = new RegistrySnapshot(
                prev.clientsById(), prev.fallbackChains(),
                prev.defaultClients(), prev.deepThinking(), Set.copyOf(newDisabled));
            if (snapshotRef.compareAndSet(prev, next)) {
                log.warn("Candidate '{}' disabled at runtime", candidateId);
                return true;
            }
            // CAS 失败——其他线程已修改快照，重试
        }
    }

    /**
     * 运行时重新启用指定候选模型。
     *
     * @return true 如果状态变更（之前已禁用）
     */
    public boolean enableCandidate(String candidateId) {
        while (true) {
            RegistrySnapshot prev = snapshotRef.get();
            if (!prev.isDisabled(candidateId)) return false;

            Set<String> newDisabled = new HashSet<>(prev.disabledSet());
            newDisabled.remove(candidateId);

            RegistrySnapshot next = new RegistrySnapshot(
                prev.clientsById(), prev.fallbackChains(),
                prev.defaultClients(), prev.deepThinking(), Set.copyOf(newDisabled));
            if (snapshotRef.compareAndSet(prev, next)) {
                log.info("Candidate '{}' re-enabled at runtime", candidateId);
                return true;
            }
            // CAS 失败——其他线程已修改快照，重试
        }
    }

    /** 获取客户端健康状态（供监控端点使用） */
    public Map<String, String> getClientHealth() {
        RegistrySnapshot snap = snapshotRef.get();
        return snap.clientsById().keySet().stream()
            .collect(Collectors.toMap(
                Function.identity(),
                id -> snap.isDisabled(id) ? "DISABLED" : "ACTIVE"
            ));
    }

    // ====== 生命周期 ======

    /**
     * 热刷新：重建快照（从当前快照继承禁用集），关闭已移除的旧客户端。
     * <p>
     * <b>触发机制</b>：由 Spring {@code @EventListener(EnvironmentChangeEvent.class)} 自动触发，
     * 或通过管理端点手动调用。刷新是全量的——每次重建所有 Client + Resilient 包装。
     * <p>
     * <b>进行中请求的安全性</b>：使用 {@code AtomicReference<RegistrySnapshot>} 的 CAS 语义保证：
     * <ul>
     *   <li>进行中的 FallbackExecutor 调用持有旧 Snapshot 引用，
     *       其 Fallback Chain 中的 Client 在旧 Snapshot 中仍然可达（不被 GC）</li>
     *   <li>新请求通过 {@code getDefault()} / {@code getFallbackChain()} 获取新 Snapshot</li>
     *   <li>新旧 Snapshot 完全隔离，不存在中间状态</li>
     * </ul>
     * <p>
     * <b>旧客户端资源释放</b>：
     * <ul>
     *   <li>新 Snapshot 中不存在的 Client（被 YAML 移除）立即调用 {@code close()}</li>
     *   <li>新 Snapshot 中仍存在的 Client（同一 candidateId）保持不变——不重建，不关闭</li>
     *   <li>被旧 Snapshot 中进行中请求引用的 Client 不会提前释放——
     *       GC 在所有引用断开后自然回收（旧 Snapshot → Client 引用链随请求结束而断开）</li>
     * </ul>
     * <p>
     * <b>熔断器状态保留</b>：刷新不重置 {@code CircuitBreaker} 状态——
     * 同一 candidateId 的熔断器在刷新前后共享底层 {@code ModelCircuitBreakerRegistry} 条目，
     * HALF_OPEN/OPEN 状态跨刷新保留。
     */
    public void refresh(LlmConfig properties, List<LlmProvider> providers,
                        ProbeHandler probeHandler) {
        Map<String, LlmProvider> providerMap = providers.stream()
            .collect(Collectors.toMap(LlmProvider::id, p -> p));
        factory.validateCandidateReferences(properties, providerMap.keySet());

        Set<String> currentDisabled = snapshotRef.get().disabledSet();
        RegistrySnapshot fresh = factory.buildSnapshot(
            properties, providerMap, circuitBreakers, probeHandler, currentDisabled);

        // 替换快照并清理不再使用的旧客户端
        RegistrySnapshot prev = snapshotRef.getAndSet(fresh);
        Set<String> freshIds = fresh.clientsById().keySet();
        for (var entry : prev.clientsById().entrySet()) {
            if (!freshIds.contains(entry.getKey())) {
                try { entry.getValue().close(); } catch (Exception e) {
                    log.warn("Error closing removed client '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        }

        log.info("LlmClientRegistry refreshed: {} candidates, {} disabled, {} removed",
            fresh.clientsById().size(), fresh.disabledSet().size(),
            prev.clientsById().size() - freshIds.size());
    }

    @Override
    public void destroy() {
        RegistrySnapshot snap = snapshotRef.getAndSet(RegistrySnapshot.EMPTY);
        for (CapabilityClient client : snap.clientsById().values()) {
            try { client.close(); } catch (Exception e) {
                log.warn("Error closing client {}: {}", client.candidateId(), e.getMessage());
            }
        }
        log.info("LlmClientRegistry destroyed, {} clients closed", snap.clientsById().size());
    }
}
```
## 10. 配置体系

### 10.1 设计原则

- **供应商与模型解耦**：供应商只关心连接信息（url、api-key、endpoints），模型独立声明并引用供应商
- **按能力分组**：模型按 `Map<LlmCapability, ModelGroup>` 映射管理，新增能力只需在 YAML 中添加条目，无需修改 Java 代码
- **candidates = Fallback Chain**：候选列表按 priority 排序，不需要单独的 fallback 配置
- **命名槽位**：`default-model` 和 `deep-thinking-model` 是命名槽位，调用方按场景选择

### 10.2 完整 YAML 结构

```yaml
app:
  llm:
    # ==================== 供应商定义（只关心连接） ====================
    providers:
      bailian:
        url: https://dashscope.aliyuncs.com/compatible-mode/v1   # 百炼 OpenAI 兼容模式（Chat 使用）
        api-key: ${BAILIAN_API_KEY:}
        endpoints:                          # Map<LlmCapability, String>，key 匹配枚举名
          chat: /chat/completions
          # embedding 和 reranking 由 BailianXxxClientFactory 使用 DashScope 原生 API，
          # 无需在此配置端点（Strategy 工厂内部管理端点地址）。

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
          reranking: /rerank

      ollama:
        url: http://10.0.0.50:11434
        # api-key 不配置 → isAvailable() 仍返回 true（ollama 不需要 key）
        endpoints:
          chat: /api/chat

    # ==================== 能力模型组（Map<LlmCapability, ModelGroup>） ====================
    # key 匹配 LlmCapability 枚举名（Spring Boot 大小写不敏感绑定），
    # 新增能力只需在此处添加新条目，无需修改 Java 代码。
    capabilities:
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
            model: qwen3-max           # id 与 model 值相同是合法的——id 是内部引用标识（default-model / deep-thinking-model），model 是 API 真实模型名；当两者一致时无需区分
            supports-thinking: true
            priority: 4

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

      reranking:
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
public record LlmConfig(
    /** 供应商配置（Map<供应商id, 连接配置>） */
    Map<String, ProviderConfig> providers,
    /** 能力 → 模型组映射。新增能力只需在 YAML 中添加条目，无需修改 Java 代码。
     *  YAML key 必须匹配 LlmCapability 枚举名（Spring Boot 大小写不敏感绑定）。 */
    Map<LlmCapability, ModelGroup> capabilities,
    /** 弹性策略配置 */
    ResilienceConfig resilience
) {
    /** 按能力获取模型组。未配置的能力返回 null（null-safe 查询） */
    @Nullable
    public ModelGroup modelGroup(LlmCapability cap) {
        return capabilities.get(cap);
    }

    /** 按能力获取模型组。未配置时抛 LLM_CONFIG_ERROR（用于必须存在的场景，如 default-model 校验） */
    public ModelGroup requireModelGroup(LlmCapability cap) {
        ModelGroup group = capabilities.get(cap);
        if (group == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No ModelGroup configured for capability: " + cap);
        }
        return group;
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
 * <p>
 * <b>注意</b>：{@code id} 不作为 record 字段——Spring Boot {@code @ConfigurationProperties}
 * 绑定 {@code Map<String, ProviderConfig>} 时，Map key 不会注入到值对象的字段中。
 * {@code id} 由 {@code LlmConfig} 或调用方从 Map key 显式传入。
 */
public record ProviderConfig(
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
 * <p>
 * 使用 {@code Map<LlmCapability, String>} 而非命名字段，新增能力无需修改 Java 代码。
 * YAML key 必须匹配 {@code LlmCapability} 枚举名（Spring Boot 大小写不敏感绑定），
 * 例如 {@code chat} → {@code CHAT}，{@code reranking} → {@code RERANKING}。
 */
public record EndpointConfig(
    /** 能力 → 端点路径映射。未配置的能力返回 null，表示该供应商不支持此能力 */
    Map<LlmCapability, String> endpoints
) {
    public EndpointConfig() { this(Map.of()); }

    /** 获取指定能力的端点路径，不存在时返回 null */
    public String get(LlmCapability cap) {
        return endpoints != null ? endpoints.get(cap) : null;
    }
}

/**
 * 按能力分组的模型配置
 * <p>
 * 映射 YAML 中 {@code app.llm.capabilities.<capability>} 节点（如 chat、embedding、reranking）。
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
    RetryConfig retry,
    CircuitBreakerProperties circuitBreaker,
    ProbeProperties probe,
    Map<LlmCapability, RetryConfig> retryOverrides
) {
    /**
     * 解析指定能力的重试配置：全局默认 + 按能力覆盖
     * <p>
     * 命名为 {@code resolveRetryConfig} 而非 {@code resolveRetry}——
     * 明确返回值是 {@code RetryConfig} 配置对象，而非"重试"动作本身。
     */
    public RetryConfig resolveRetryConfig(LlmCapability capability) {
        RetryConfig override = retryOverrides.get(capability);
        if (override == null) {
            return retry;
        }
        return retry.mergeWithOverride(override);
    }
}

public record RetryConfig(
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
     * 命名为 {@code mergeWithOverride} 而非 {@code mergeWith}——
     * 明确参数语义是"覆盖配置"，而非任意的 RetryConfig 合并。
     * <p>
     * 使用 nullable 类型而非 sentinel 值（如 0），避免"用户显式设置 0"与"未覆盖"的歧义。
     */
    public RetryConfig mergeWithOverride(RetryConfig override) {
        return new RetryConfig(
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
| `candidate.provider` 引用 | 必须在 `providers` Map 中存在对应 id | `LlmClientFactory` 校验时（由 `LlmClientRegistry` 构造时调用） |
| `default-model` 引用 | 必须在 candidates 中存在对应 id | `LlmClientFactory` 校验时 |
| `deep-thinking-model` 引用 | 必须在 candidates 中存在对应 id（如配置） | `LlmClientFactory` 校验时 |
| `endpoint` 非空 | candidate 引用的 provider 必须有对应能力的 endpoint 配置 | `LlmProvider.createClient()` 时 |
| `priority` | 非负整数 | `ModelCandidate` 构造时 |
| `capability` | 不可为 null（由所属 ModelGroup 决定） | `ModelCandidate` 构造时 |
| `@Component` 自注册 | YAML 中每个 provider id 要么有对应的 `@Component` Bean，要么由 Registrar 创建 | `LlmClientRegistry` 构造时校验 |

> **YAML 绑定注意事项**：
> - `providers` 是 `Map<String, ProviderConfig>`，YAML key 即供应商 id（如 `bailian`、`deepseek`）
> - `capabilities` 是 `Map<LlmCapability, ModelGroup>`，YAML key 必须匹配枚举名（Spring Boot 大小写不敏感），如 `chat` → `CHAT`、`reranking` → `RERANKING`
> - `providers.<id>.endpoints` 是 `Map<LlmCapability, String>`，YAML key 同理匹配枚举名
> - `resilience.retry-overrides` 的 key 为 `LlmCapability` 枚举名，推荐统一使用大写（`EMBEDDING`、`RERANKING`）
> - YAML 中使用 kebab-case（如 `default-model`、`deep-thinking-model`、`retry-overrides`），Spring Boot 自动映射到 Java camelCase

```java
// LlmClientFactory 配置引用校验（由 LlmClientRegistry 构造时调用）
void validateCandidateReferences(LlmConfig properties, Set<String> providerIds) {
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

    // 校验 candidate.id 全局唯一性（跨能力组）
    Set<String> seenIds = new HashSet<>();
    for (LlmCapability cap : LlmCapability.values()) {
        ModelGroup group = properties.modelGroup(cap);
        if (group == null) continue;
        for (ModelCandidate candidate : group.candidates()) {
            if (!seenIds.add(candidate.id())) {
                throw new RemoteException(LLM_CONFIG_ERROR,
                    "Duplicate candidate.id '" + candidate.id() + "' — must be globally unique across all capabilities");
            }
        }
    }
}
```

## 11. 异常体系（复用已有层次结构）【已实施】

> **设计决策**：不新建 `LlmException`，所有 LLM 弹性层异常**继承已有的 `RemoteException`（C类）**。
> 已有异常体系按 A/B/C 三类划分，LLM 调用失败属于"第三方服务错误"，天然属于 C 类。
>
> **实施状态**：本节描述的异常重构（`ProbeTimeoutException`、`ModelCircuitOpenException` 改为 `extends RemoteException`，
> 新增 `LlmTransientException`、`RemoteErrorCode` 301xxx 段）**已合入主干**。
> §11.1–11.3 的异常分类与弹性层交互表为已实施代码的架构参考文档（非待实施设计）。

### 11.1 异常层次映射

```
AbstractException (RuntimeException, 携带 IErrorCode)
├── ClientException (A类, 1xxxxx) ← 不可降级
│   ├── ContentFilteredException        ← 内容过滤（已有）
│   └── RateLimitExceededException      ← 客户端自身限流（已有，A 类，与供应商 429 不同，见 §11.3）
│
├── ServiceException (B类, 2xxxxx) ← 不可降级
│   └── ModelNotFoundException          ← 模型配置不存在（已有）
│
└── RemoteException (C类, 3xxxxx) ← 可降级
    ├── [类] ProviderNotFoundException       ← 厂商未配置（已有, 300001）
    │
    └── LLM 弹性层异常 (301xxx)
        ├── [类] LlmTransientException        ← LLM 瞬态错误（301007, 可重试可降级）
        ├── [类] ModelCircuitOpenException     ← 熔断器打开（301002, 已重构为 RemoteException）
        ├── [类] ProbeTimeoutException         ← 首包探测超时（301003, 已重构为 RemoteException）
        │
        └── [码] 直接抛出的 RemoteException（new RemoteException(ErrorCode, message)）
            ├── [码] LLM_ALL_MODELS_FAILED    ← 链耗尽（301001）
            ├── [码] LLM_CONFIG_ERROR         ← 配置校验失败（301004）
            ├── [码] LLM_RATE_LIMITED         ← 供应商 429（301005）
            └── [码] LLM_RESPONSE_TRUNCATED   ← 流式响应超时（301006）
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
| `RemoteException(LLM_RATE_LIMITED)` | 供应商返回 429（C 类，与客户端自身限流的 A 类 `RateLimitExceededException` 不同） | ✓ | ✓ | ✓ |
| `RemoteException(LLM_ALL_MODELS_FAILED)` | FallbackExecutor 链耗尽 | ✗ | — | — |
| `UnsupportedOperationException` | 客户端不支持的操作（如非流式客户端调 chatStream） | ✗ | ✗ | ✗ |
| `IOException` / `SocketTimeoutException` | 网络层 | ✓ | ✓ | ✓ |
| `LlmTransientException` | LLM 瞬态错误（供应商 5xx、超时等） | ✓ | ✓ | ✓ |
> 所有 C 类异常（`RemoteException` 及其子类）均通过 `FallbackEligibility.isEligible()` 返回 `true`，可触发跨模型降级。

### 11.3.1 异常包装链：从原始异常到 FallbackExecutor 的完整路径

弹性层与编排层的正交性依赖于一条明确的异常包装链。FallbackExecutor 不知道 ResilientClient 内部的重试/熔断状态——它只消费最终的异常对象。

**阻塞调用链路**：
```
LLM API 抛出 SocketTimeoutException
  ↓
RetryPolicy.executeWithBackoff()
  ├─ isRetryable(e) → true → 重试（最多 maxAttempts 次）
  └─ 重试耗尽 → 包装为 LlmTransientException(cause=SocketTimeoutException) 抛出
  ↓
CircuitBreaker.execute()
  ├─ 捕获 LlmTransientException → registry.recordFailure(candidateId) → 向上抛出
  └─ 非基础设施异常 → 不计熔断，直接向上抛出
  ↓
FallbackExecutor.execute()
  ├─ 收到 LlmTransientException（C 类） → fallbackEligibility.isEligible() → true → 降级到下一个 client
  └─ 收到 ContentFilteredException（A 类） → isEligible() → false → 直接向上抛出，不降级
```

**流式调用链路**：
```
LLM API 流首包超时
  ↓
ProbeHandler.wrap()
  └─ 抛出 ProbeTimeoutException → 已有 ProbeStreamHandler 内部调用 breakers.recordFailure()
  ↓
RetryPolicy.retryStream()
  ├─ ProbeTimeoutException.isRetryable() → true → 重新订阅（最多 maxAttempts 次）
  └─ 重试耗尽 → 包装为 LlmTransientException(cause=ProbeTimeoutException) 抛出
  ↓
CircuitBreaker.executeStream().doOnError()
  ├─ LlmTransientException → 排除 ProbeTimeoutException（已由 ProbeStreamHandler 处理）→ recordFailure
  └─ 向上抛出
  ↓
FallbackExecutor.executeStream()
  └─ 收到 LlmTransientException → onErrorResume → 降级到下一个 client 的 Flux
```

**关键设计约束**：
1. **RetryPolicy 是唯一的异常包装点**：所有原始异常（IOException、SocketTimeoutException 等）在重试耗尽后统一包装为 `LlmTransientException`。FallbackExecutor 不处理原始异常。
2. **CircuitBreaker 是异常过滤点**：通过 `fallbackEligibility.isEligible()` 决定是否计入熔断计数，但**不改变异常类型**——透传给 FallbackExecutor。
3. **FallbackEligibility 是降级判定点**：基于异常类型（A/B/C 类）决定是否降级，不依赖异常消息或上下文。

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

**统一后的异常层次**（`[类]` = Java 异常类继承关系，`[码]` = `RemoteErrorCode` 枚举值）：
```
AbstractException
├── ClientException (A类)     ← 不可降级
├── ServiceException (B类)    ← 不可降级
└── RemoteException (C类)     ← 可降级
    ├── [类] ProviderNotFoundException     (300001)
    │
    └── LLM 弹性层 (301xxx)
        ├── [码] LLM_ALL_MODELS_FAILED    (301001) ← FallbackExecutor 链耗尽
        ├── [类] ModelCircuitOpenException (301002) ← 熔断器打开（已有异常类）
        ├── [类] ProbeTimeoutException     (301003) ← 首包探测超时（已有异常类）
        ├── [码] LLM_CONFIG_ERROR          (301004) ← 配置校验失败（直接抛 RemoteException）
        ├── [码] LLM_RATE_LIMITED          (301005) ← 供应商 429（直接抛 RemoteException）
        ├── [码] LLM_RESPONSE_TRUNCATED    (301006) ← 流式响应超时截断
        └── [类] LlmTransientException     (301007) ← LLM 瞬态错误（已有异常类）
```



## 12. 调用方迁移

### 12.1 迁移对照表

| 调用方 | 迁移前 | 迁移后 | 复杂度 |
|--------|--------|--------|--------|
| `IntentClassifier` | `ChatClientRegistry.get(id)` + 自写 for-loop 2 次 | `registry.get(id, ChatCapable.class).chat(request)` | ★ |
| `LlmJudgeImpl` | 构造注入 `ChatClient` + 自写 for-loop 2 次 | **本次跳过**，维持独立 `@Bean("judgeChatClient")` 不变 | — |
| `DatasetGenerator` | Spring AI `ChatClient.Builder` 直接注入 | `registry.get(id, ChatCapable.class).chat(request)` | ★ |
| `RagConfig`（query rewrite） | `RewriteQueryTransformer` 内部 ChatClient.Builder | 保留框架组件，将其底层 `ChatClient.Builder` 替换为从 `LlmClientRegistry` 获取的 `ChatCapable`（通过 `.asChatModel()` 适配为 `ChatModel`），再 `ChatClient.builder(chatCapable.asChatModel()).build()` 构造 | ★★ |
| `DashScopeEmbeddingModel` | 已有独立 REST 实现（DashScope 原生 API，含 text_type / instruct） | 迁移到 `BailianEmbeddingClient`（由 `EmbeddingCapabilityStrategy` 通过 `BailianEmbeddingClientFactory` 创建，使用 DashScope 原生 API，保留 text_type / instruct / 批量分片），复用现有核心逻辑。PgVectorStore 继续用已有 `EmbeddingModel`，新 SPI 调用方用 `EmbeddingCapable` | ★★ |
| `AnswerRelevanceScorer` | 注入 Spring AI `EmbeddingModel` | 改为注入 `EmbeddingCapable`（从 Registry 获取） | ★ |
| `BailianRerankPostProcessor` | 自己实现 RestClient + 3x 重试 + 线程池 + 降级 | `registry.get("qwen3-rerank", RerankCapable.class).rerank(request)` | ★★ |
| `AgentModeStrategy` | `ChatClientRegistry` + `TokenCountingChatModel` 包装 | 替换为 `LlmClientRegistry.getDefault(CHAT, ChatCapable.class).asChatModel()`，通过 `ChatModelAdapter` 适配为 `ChatModel`，`TokenCountingChatModel` + `ChatClient.Builder` + Advisor 链零改动 | ★★★ |
| `ChatServiceImpl`（核心路径） | 15 个构造参数，自己编排 fallback + circuit breaker + probe | 由 `FallbackExecutor` + `ResilientChatClient` 处理 | ★★★★ |

> **AgentModeStrategy 迁移策略**：`ChatCapable` 不再直接继承 `ChatModel`（§5.1），
> 而是通过 `ChatModelAdapter`（§5.5）适配。调用方式：
> `LlmClientRegistry.getDefault(CHAT, ChatCapable.class).asChatModel()` 返回 `ChatModel` 实例。
> `TokenCountingChatModel`（装饰器，接受 `ChatModel`）和 `ChatClient.builder(model).build()` 均无需修改。
> 迁移仅需：将 `chatClientRegistry` 替换为 `LlmClientRegistry`，通过 `.asChatModel()` 获取 `ChatModel`，
> Spring AI 工具调用（`ToolCallAdvisor` + `DefaultToolCallingManager`）、`MessageChatMemoryAdvisor`、
> `AgentGuardrails` 等组件完全不受影响。
> 弹性层保护（重试/熔断/首包探测）由 `ResilientChatClient` 在 `ChatCapable` 层面提供，
> `ChatModelAdapter` 仅做类型桥接，不影响弹性语义。


### 12.2 迁移顺序（风险从低到高）

```
Phase 1（低风险，消除散落重试）:
  1. IntentClassifier        ← 最简单，消除自写 retry
  2. DatasetGenerator        ← 最简单

Phase 2（中风险，统一非 Chat 调用）:
  3. DashScopeEmbeddingModel ← 迁移到 BailianEmbeddingClient（Strategy 工厂创建，保留原生 API 特性），消除绕路 RestClient
  4. BailianRerankPostProcessor ← 迁移到 BailianRerankClient（Strategy 工厂创建，保留 instruct），消除绕路 RestClient + 线程池
  5. RagConfig (query rewrite) ← 保留 RewriteQueryTransformer，替换底层 ChatClient 注入来源

Phase 3（高风险，核心路径）:
  6. AgentModeStrategy       ← 替换 ChatClientRegistry → LlmClientRegistry，通过 `.asChatModel()` 适配 Spring AI，组件不变
  7. ChatServiceImpl (streaming) ← 保留旧路径可灰度切换
  8. ChatServiceImpl (blocking)  ← 最后迁移

跳过（本次不迁移）:
  - LlmJudgeImpl             ← 维持独立 @Bean("judgeChatClient")，评估专用隔离
```

### 12.3 ChatServiceImpl 迁移效果

**Before（15 个构造参数，其中 8 个为 LLM 基础设施相关）:**

```java
@Service
public class ChatServiceImpl implements ChatService {
    // LLM 基础设施（8 个，重构后由 registry + fallbackExecutor 替代）
    private final ChatClientRegistry registry;
    private final ModelRouter modelRouter;
    private final ChatFallbackProperties fallbackProperties;
    private final FallbackChainProvider fallbackChainProvider;
    private final FallbackEligibility fallbackEligibility;
    private final StreamRetryHandler streamRetryHandler;
    private final ProbeStreamHandler probeStreamHandler;
    private final ModelCircuitBreakerRegistry circuitBreakers;
    // 业务参数（7 个，重构后保留）
    private final ModeRouter modeRouter;
    private final ChatUsageTracker usageTracker;
    private final ChatConversationHelper conversationHelper;
    private final SseStreamBridge sseStreamBridge;
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;
    private final UserContextProvider userContextProvider;
}
```

**After（15 → 9 个构造参数：8 个基础设施参数收敛为 2 个）:**

```java
@Service
public class ChatServiceImpl implements ChatService {
    // LLM 基础设施（2 个，替代原来的 8 个）
    private final LlmClientRegistry registry;
    private final FallbackExecutor fallbackExecutor;
    // 业务参数（7 个，保持不变）
    private final ModeRouter modeRouter;
    private final ChatUsageTracker usageTracker;
    private final ChatConversationHelper conversationHelper;
    private final SseStreamBridge sseStreamBridge;
    private final RequestContextManager cagContextManager;
    private final CagProperties cagProperties;
    private final UserContextProvider userContextProvider;

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
        return registry.getChainFor(LlmCapability.CHAT, ChatCapable.class, modelId);
    }
}
```

> **LoD（迪米特法则）改进**：
> - **Before**：ChatServiceImpl 直接持有 8 个 LLM 基础设施组件（`ChatClientRegistry`、`ModelRouter`、
>   `FallbackChainProvider`、`FallbackEligibility`、`StreamRetryHandler`、`ProbeStreamHandler`、
>   `ModelCircuitBreakerRegistry`、`ChatFallbackProperties`），需要了解每个组件的内部结构和交互方式
> - **After**：ChatServiceImpl 只依赖 2 个组件（`LlmClientRegistry` + `FallbackExecutor`），
>   通过 `registry.getChainFor()` 获取降级链，通过 `fallbackExecutor.execute/executeStream()` 执行——
>   不感知重试策略、熔断器状态、首包探测的内部细节
> - **FallbackEvent 解耦**：降级事件通过 `FallbackEvent` record 发布（Observer 模式），
>   ChatServiceImpl 可选择性订阅（如通过 `eventPublisher` 回调通知 UI 层），
>   但不依赖 FallbackExecutor 的内部实现——事件格式稳定，内部实现可自由变化
> - **调用链简化**：`chat()` 和 `chatStream()` 各只需 2 行代码（`resolveChain` + `execute`），
>   所有弹性行为（重试/熔断/探测/降级）由下层透明处理

> **降级时序示例**（流式路径）：
> ```
> 请求 → deepseek-v4-flash (priority=2)
>   → CircuitBreaker: CLOSED ✓
>   → RetryPolicy.retryStream()
>     → ProbeHandler: 首包 3s 超时 → ProbeTimeoutException
>     → retryStream: 重试 1/3 → 再次超时
>     → retryStream: 重试 2/3 → 再次超时
>     → retryStream: 重试 3/3 → 耗尽 → ProbeTimeoutException 冒泡
>   → CircuitBreaker: recordFailure() → failures=1（未达阈值 5）
>   → FallbackExecutor.onErrorResume → 切换到下一个
>
> 请求 → qwen3-local (priority=3)
>   → CircuitBreaker: CLOSED ✓
>   → 正常流式输出 → onComplete → recordSuccess()
> ```

> **⚠ 流式降级使用约束**：
> - **内容不连贯**：若模型 A 在输出部分内容后失败，`onErrorResume` 切换到模型 B 时，下游收到的是「A 的不完整片段 + B 的完整输出」拼接。已发送的片段不会回滚。
> - **适用场景**：流式降级适合**容错优先**的场景（如实时聊天），而非**内容连贯优先**的场景（如文档生成）。
> - **调用方处理建议**：对连贯性有要求的调用方，应在 `chatStream` 之外自行处理拼接不连贯的情况——例如丢弃模型 A 的片段后重新请求，或在 UI 层提示用户"因模型切换，前文可能不完整"。
> - **降级链配置**：建议为同能力候选配置语义相近的模型（如同一系列的不同规格），以减小切换后的风格差异。


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

    capabilities:
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

    capabilities:
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

      reranking:
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

    capabilities:
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
> - 我有哪些模型（`capabilities.<capability>.candidates`）
> - 每个模型由哪个供应商提供（`candidate.provider` 引用 `providers` 中的 id）
>
> 声明完成后，`GenericOpenAiProvider` 自动创建客户端 → `LlmClientRegistry` 自动注册 → Resilient 自动包装 → Fallback Chain 自动包含。
> 用户自定义的供应商与内置供应商享有完全相同的弹性保护（重试/熔断/降级/首包探测）。

### 场景 B：非标准 API（完全自定义 Provider）

> **适用场景**：供应商 API 完全不符合 OpenAI 兼容格式（如自定义协议、私有 SDK）。
> 需要手写 `@Component` LlmProvider + 专用 Client。
>
> **注意**：百炼（Bailian）**不属于**场景 B。百炼的 Chat 走 OpenAI 兼容（`GenericChatClient`），
> Embedding 和 Reranking 虽使用 DashScope 原生 API，但通过 `CapabilityStrategy` 的
> `ProviderClientFactory` 扩展点实现（见 §7.8 / §8.3），不需要自定义 Provider。
> 只有完全非标准 API 才需要走场景 B。
>
> **`CapabilityStrategyRegistry` 职责**：它是能力策略的注册中心，维护 `LlmCapability → CapabilityStrategy` 映射。
> 每个策略知道如何为对应能力类型创建客户端（`createClient`）、解析端点（`resolveEndpoint`）、
> 包装弹性层（`wrapWithResilience`）。由 Spring 自动收集所有 `@Component` 策略 Bean。
>
> **非 OpenAI 兼容 API 的集成方式**：自定义 Provider 的 `createClient()` 对非标准能力直接实例化
> 特殊 Client（绕过策略的 `createClient`），但对仍属 OpenAI 兼容的能力（如 chat）可委托
> `GenericOpenAiProvider` 处理——此时需要传入 `strategyRegistry`，因为 `GenericOpenAiProvider`
> 内部通过策略解析端点和创建客户端。

写 1 个 Provider + 特殊 Client：


```java
// 1. Provider（@Component 自注册，轻量工厂）
@Component
public class XxxProvider implements LlmProvider {
    private final ProviderConfig config;
    private final CapabilityStrategyRegistry strategyRegistry;

    public XxxProvider(LlmConfig properties, CapabilityStrategyRegistry strategyRegistry) {
        this.config = properties.requireProvider("xxx");
        this.strategyRegistry = strategyRegistry;
    }

    @Override public String id() { return "xxx"; }
    @Override public ProviderConfig config() { return config; }

    @Override
    public CapabilityClient createClient(ModelCandidate candidate) {
        return switch (candidate.capability()) {
            case EMBEDDING -> new XxxEmbeddingClient(config, candidate);
            default -> new GenericOpenAiProvider("xxx", config, strategyRegistry).createClient(candidate);
        };
    }
}

// 2. 特殊 Client（封装非标准 API 逻辑）
public class XxxEmbeddingClient extends AbstractEmbeddingClient {
    // 特殊 HTTP 调用逻辑
}
```

## 14. 目录结构

```
com.smart.rag.infrastructure.llm/
│
├── LlmCapability.java                      # 能力枚举（CHAT / EMBEDDING / RERANKING）
├── ModelCandidate.java                      # sealed interface — 模型候选声明
│   ├── AbstractModelCandidate.java          #   抽象基类（公共字段 + getter/setter）
│   ├── ChatCandidate.java                   #   Chat 候选（supportsThinking / supportsStreaming）
│   ├── EmbeddingCandidate.java              #   Embedding 候选（dimension）
│   └── RerankCandidate.java                 #   Rerank 候选（无额外字段）
├── CapabilityClient.java                    # 客户端根接口（candidateId / providerId / modelName）
├── ChatCapable.java                         # Chat 能力契约
├── EmbeddingCapable.java                    # Embedding 能力契约
├── RerankCapable.java                       # Rerank 能力契约
├── ToolCallingCapable.java                  # 工具调用能力（ISP 拆分，extends ChatCapable）
├── LlmProvider.java                         # 供应商接口（轻量工厂：id + config + createClient）
├── ChatModelAdapter.java                    # Spring AI ChatModel 适配器（ISP/LSP/SRP 桥接）
├── CapabilityStrategy.java                  # 能力策略接口（OCP：新增能力只添加策略 Bean）
├── CapabilityStrategyRegistry.java          # 能力策略注册表（自动收集 @Component 策略）
├── LlmClientFactory.java                   # 客户端工厂（创建 + Resilient 包装，SRP 拆分）
├── LlmClientRegistry.java                   # 统一注册表（查询 + 快照管理 + 禁用/启用）
├── MessageInformation.java                   # 对话消息（class，非 record，避免命名冲突）
├── ChatRequest.java                         # Chat 请求（含 Builder）
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
│   ├── ResilientChatClient.java             # Chat 装饰器（implements ChatCapable + ToolCallingCapable）
│   ├── ResilientEmbeddingClient.java        # Embedding 装饰器
│   ├── ResilientRerankClient.java           # Rerank 装饰器
│   ├── RetryPolicy.java                     # 统一重试策略
│   ├── FallbackExecutor.java                # 跨模型降级执行器（泛型 Flux<R>，责任链语义）
│   ├── FallbackEvent.java                   # 降级事件 record（Observer 模式，供 UI/metrics 消费）
│   ├── CircuitBreaker.java                  # 熔断器适配器（包装 ModelCircuitBreakerRegistry）
│   ├── LlmCircuitBreakerAdapterRegistry.java # 熔断器注册表
│   └── ProbeHandler.java                    # 首包探测
│
├── provider/                                # 供应商实现
│   └── generic/                             # 通用 OpenAI 兼容 Provider + Client
│       ├── GenericOpenAiProvider.java       # 轻量工厂，按 capability 策略创建 Client
│       ├── GenericOpenAiProviderRegistrar.java # BeanDefinitionRegistryPostProcessor
│       ├── GenericChatClient.java
│       ├── GenericEmbeddingClient.java      # 纯 OpenAI 兼容 Embedding，无百炼特判
│       └── GenericRerankClient.java
│
├── strategy/                                # 能力策略 + 供应商差异化
│   ├── CapabilityStrategy.java              # 能力策略接口
│   ├── CapabilityStrategyRegistry.java      # 策略注册表（自动收集 @Component）
│   ├── ChatCapabilityStrategy.java          # CHAT 策略
│   ├── EmbeddingCapabilityStrategy.java     # EMBEDDING 策略（含 ProviderClientFactory 扩展）
│   ├── RerankCapabilityStrategy.java        # RERANKING 策略（含 ProviderClientFactory 扩展）
│   └── provider/                            # 供应商专用 Client 工厂
│       ├── ProviderClientFactory.java       # 扩展点接口（providerId + capability + create）
│       ├── BailianEmbeddingClientFactory.java # 百炼 Embedding 工厂（@Component 自注册）
│       ├── BailianEmbeddingClient.java      # 百炼 Embedding Client（DashScope 原生 API）
│       ├── BailianRerankClientFactory.java  # 百炼 Reranking 工厂（@Component 自注册）
│       └── BailianRerankClient.java         # 百炼 Rerank Client（DashScope 原生 API）
│
├── config/                                  # 配置（供应商与模型解耦）
│   ├── LlmConfig.java                       # @ConfigurationProperties（providers + capabilities）
│   ├── ProviderConfig.java                  # 供应商连接配置（url / apiKey / endpoints）
│   ├── EndpointConfig.java                  # 端点路径配置（Map<LlmCapability, String>）
│   ├── ModelGroup.java                      # 按能力分组（default-model / candidates）
│   ├── ResilienceConfig.java
│   ├── RetryConfig.java
│   ├── CircuitBreakerProperties.java
│   └── ProbeProperties.java
```
> **目录按功能域划分**，而非按实现模式（adapter/strategy/registry）划分。
> 核心类型（接口、工厂、注册表、适配器）集中在 `llm/` 根包下，
> 子包用于 `client`（抽象层）、`resilience`（弹性层）、`provider`（供应商实现）、`strategy`（能力策略 + 供应商差异化）、`config`（配置）。
> 百炼（Bailian）通过 `strategy/provider/` 下的 `ProviderClientFactory` 实现供应商差异化，
> 使用 DashScope 原生 API 保留 text_type / instruct 等完整特性，不走 OpenAI 兼容端点。
> 纯 OpenAI 兼容供应商（DeepSeek / ZhiPu / SiliconFlow 等）零代码配置，无专用目录。

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
- `MessageInformation.java`
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

### Phase 2: Provider + Strategy 实现（逐个迁移）

新增 Provider 实现 + Strategy 供应商差异化扩展，取代现有 `ModelProvider`：
- `GenericOpenAiProvider.java` + `GenericOpenAiProviderRegistrar.java` + `GenericChatClient.java` + `GenericEmbeddingClient.java`（纯 OpenAI 兼容，RestClient）+ `GenericRerankClient.java`
- `ProviderClientFactory.java`（Strategy 供应商差异化扩展点接口）
- `BailianEmbeddingClientFactory.java` + `BailianEmbeddingClient.java`（百炼 Embedding，复用 `DashScopeEmbeddingModel` 核心逻辑，DashScope 原生 API，保留 text_type / instruct）
- `BailianRerankClientFactory.java` + `BailianRerankClient.java`（百炼 Reranking，复用 `BailianRerankPostProcessor` 核心逻辑，DashScope 原生 API，保留 instruct）
> DeepSeek / ZhiPu / MiniMax 为纯 OpenAI 兼容 API，由 `GenericOpenAiProvider` 自动处理（零代码配置）。
> 百炼 Chat 同样走 OpenAI 兼容（`GenericChatClient`），Embedding 和 Reranking 由 Strategy 工厂委托给专用 Client（使用原生 API 保留完整特性）。

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
- `DashScopeEmbeddingModel`（核心逻辑迁移至 `BailianEmbeddingClient` 后，旧类删除或简化为 Spring AI `EmbeddingModel` 适配壳，供 PgVectorStore 继续使用）
- `AgentModeStrategy` 中的 `ChatClientRegistry` 依赖（替换为 `LlmClientRegistry`）
---

## 16. 风险与缓解措施

| 风险 | 缓解措施 |
|------|---------|
| 核心聊天路径迁移回归 | Phase 3 最后迁移，保留旧路径可灰度切换 |
| `TokenCountingChatModel` 包装（Agent guardrails） | `ChatCapable.asChatModel()` 通过 `ChatModelAdapter` 适配，`TokenCountingChatModel` 接受任意 `ChatModel`，零改动 |
| `AgentModeStrategy` 工具调用深度耦合 Spring AI | `ChatCapable.asChatModel()` 返回 `ChatModelAdapter`，`ToolCallAdvisor` + `DefaultToolCallingManager` + `MessageChatMemoryAdvisor` 无需修改。仅替换 `ChatClientRegistry` → `LlmClientRegistry`，弹性保护在 `ChatCapable` 层面提供 |
| `ChatRequestSpecFactory` 的 Advisor 链 | 保持在编排层，CapabilityClient 只负责传输 |
| `DashScopeEmbeddingModel` 多实现 `EmbeddingCapable` 后 PgVectorStore 兼容性 | 保留 `@Primary implements EmbeddingModel`，PgVectorStore 注入不变，行为不变 |
| Embedding 批量分片逻辑 | 已有 `DashScopeEmbeddingModel.callBatch()` 中实现（`MAX_BATCH_SIZE=10`），迁移至 `BailianEmbeddingClient` 后保留核心逻辑，由 `ResilientEmbeddingClient` 包装重试。`GenericEmbeddingClient` 使用纯 OpenAI 兼容 API（`input` 数组输入），不混入百炼特判 |
| Strategy 工厂扩展点可靠性 | `ProviderClientFactory` 通过 Spring `@Component` 自动收集，启动时校验。工厂不存在时回退到通用 `GenericXxxClient`（OpenAI 兼容），不会因工厂缺失导致启动失败 |
| Evaluation Profile 独立 ChatClient | `LlmClientRegistry` 支持条件注册，evaluation 专用模型按 Profile 过滤 |
| 旧 `compositeId`（`provider/model` 格式，已删除）对应 `get(compositeId, type)` 查询 | 新 `candidateId`（YAML candidate.id，全局唯一）对应一个 client 实例，能力由 `capability()` 声明（类型系统强制一对一） |
| 两 Registry 共存期（Phase 2-3） | 本地开发阶段可容忍。旧 `ChatClientRegistry` 用 `"provider/model"` 格式 ID，新 `LlmClientRegistry` 用 YAML `candidate.id` 全局唯一标识（如 `qwen3-max`），命名空间互不冲突。熔断器共享底层 `ModelCircuitBreakerRegistry`，状态一致 |
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
| 5 | `AgentModeStrategy:134` | `ChatClientRegistry.getChatModel()` + `TokenCountingChatModel` | `LlmClientRegistry.getDefault(CHAT, ChatCapable.class).asChatModel()`，通过 `ChatModelAdapter` 适配，Advisor 链不变 |
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
当前 `BailianRerankPostProcessor`（已迁移为 `BailianRerankClient`，由 `BailianRerankClientFactory` 创建，使用 DashScope 原生 API，见 §8.3）实现 `DisposableBean` 关闭线程池，`OkHttpClient` 有连接池需要关闭。
新设计中 `GenericOpenAiProvider` 等 Provider 持有 RestClient/HTTP 客户端。

### 18.2 方案

持有资源的 Provider 直接实现 `DisposableBean`，由 Spring 容器在关闭时统一调用 `destroy()`：

实现示例：
- `GenericOpenAiProvider` — 实现 `DisposableBean`，`destroy()` 中关闭 RestClient 连接池
- `BailianRerankClient` — 实现 `CapabilityClient` 的 `close()` 方法，关闭线程池和 HTTP 连接（见 §8.3）
- `BailianEmbeddingClient` — 委托给已有 `DashScopeEmbeddingModel` 资源管理

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

---

## 20. 测试策略

> 完整测试策略已拆分至独立文档：[llm-unified-spi-testing.md](llm-unified-spi-testing.md)
>
> 覆盖范围：弹性层单元测试（CircuitBreaker / ProbeHandler / RetryPolicy）、编排层测试（FallbackExecutor）、Registry 并发刷新测试、集成测试（WireMock 联合）、新 Provider 验收模板、Mock 模式、命名与标签约定。
>
> **实施建议**：设计文档稳定后同步修复测试代码。弹性层优先级最高（RetryPolicy → CircuitBreaker → ProbeHandler），编排层次之，Registry 并发测试最后补齐。
