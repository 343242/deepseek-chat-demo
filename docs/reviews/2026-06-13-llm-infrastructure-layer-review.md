# LLM Infrastructure Layer Code Review

> Review date: 2026-06-13
> Scope: `src/main/java/com/smart/rag/infrastructure/llm/` (60+ files, ~4500 lines)
> Checklist: `.trellis/spec/backend/code-review-checklist.md`
> Reviewers: AI code review agent

---

## Summary

LLM 基础设施层整体设计质量较高，采用了 sealed interface + strategy + registry + resilience decorator 的分层架构，SOLID 原则遵守良好。发现 **2 个 P1 bug**、**5 个 P2 问题**、**若干 P3 改进项**。

### Findings Count

| Severity | Count | Description |
|----------|-------|-------------|
| **P0** | 0 | — |
| **P1** | 2 | Bug / 线上风险 |
| **P2** | 5 | 设计缺陷 / 维护风险 |
| **P3** | 8 | 改进建议 |

---

## P1 — 必须修复

### P1-1: `RetryConfig.mergeWithOverride()` 合并逻辑失效

**文件**: `config/RetryConfig.java:25-49`
**维度**: #4 边界条件, #9 可扩展性

**问题**: compact constructor 在构造时将 null 字段替换为默认值，导致 `mergeWithOverride()` 中 `override.xxx != null` 判断永远为 true。override 中未显式设置的字段会用默认值覆盖 base 配置的实际值。

```java
// RetryConfig compact constructor — line 25-30
public RetryConfig {
    if (maxAttempts == null || maxAttempts <= 0) maxAttempts = 3;  // null → 3
    if (baseDelayMs == null || baseDelayMs <= 0) baseDelayMs = 500L;
    // ...
}

// mergeWithOverride — line 42-48
public RetryConfig mergeWithOverride(RetryConfig override) {
    return new RetryConfig(
        override.maxAttempts != null ? override.maxAttempts : this.maxAttempts,
        // ↑ override 已经在 compact constructor 中将 null → 3，永远 != null
        override.baseDelayMs != null ? override.baseDelayMs : this.baseDelayMs,
        // ↑ 同理，null → 500，永远 != null
        ...
    );
}
```

**复现场景**:
```yaml
# base config
retry:
  maxAttempts: 5
  baseDelayMs: 1000
# override — 只想改 maxAttempts
retryOverrides:
  chat:
    maxAttempts: 8
```
预期：`{maxAttempts=8, baseDelayMs=1000}`，实际：`{maxAttempts=8, baseDelayMs=500}` — base 配置的 `baseDelayMs=1000` 被默认值 500 覆盖。

**修复方案**: compact constructor 不应替换 null，改用 `effective*()` 方法在使用时提供默认值。或者 `mergeWithOverride` 需要先用原始 nullable 值判断。

---

### P1-2: `AbstractModelCandidate` 双访问器路径不一致

**文件**: `AbstractModelCandidate.java:36,48`
**维度**: #1 DIP, #10 代码质量

**问题**: `params()` (line 36) 有 null-safe 防护，`getParams()` (line 48) 直接返回原始字段。两条路径返回不同值：

```java
@Override public Map<String, Object> params() { return params != null ? params : Map.of(); }  // null-safe
public Map<String, Object> getParams() { return params; }  // 可能返回 null
```

Spring Boot `@ConfigurationProperties` 绑定使用 getter/setter，上层 SPI 使用 `params()`。如果 `setParams(null)` 被调用，两条路径返回不同结果。

**修复方案**: `getParams()` 也应做 null-safe 处理，与 `params()` 保持一致：
```java
public Map<String, Object> getParams() { return params != null ? params : Map.of(); }
```

---

## P2 — 建议修复

### P2-1: `CapabilityStrategyRegistry.get()` 异常类型错误

**文件**: `strategy/CapabilityStrategyRegistry.java:36`
**维度**: #7 异常处理

**问题**: 缺少策略注册是**本地配置错误**，不是第三方服务错误，但抛出了 `RemoteException`：

```java
throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
    "No CapabilityStrategy registered for " + cap);
```

按 error-handling.md 规范，应使用 `ServiceException`（服务端错误：状态异常、业务逻辑不符合预期）。

**修复方案**: 改为 `new ServiceException(ServiceErrorCode.XXX, ...)` 或 `IllegalStateException`。

---

### P2-2: `AbstractResilientClient.executeResilient()` 错误码语义错误

**文件**: `resilience/AbstractResilientClient.java:87`
**维度**: #7 异常处理

**问题**: 所有 checked exception 统一使用 `RemoteErrorCode.LLM_STREAM_ERROR`，但该方法用于 chat、embedding、rerank 所有场景，不仅仅是 stream：

```java
throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
    "Unexpected checked exception from LLM action: " + e.getMessage(), e);
```

**修复方案**: 使用更通用的错误码（如 `LLM_TRANSIENT_ERROR`），或按 capability 类型区分。

---

### P2-3: `AbstractModelCandidate.validate()` 是 opt-in，无自动校验

**文件**: `AbstractModelCandidate.java:58-62`
**维度**: #4 边界条件

**问题**: `validate()` 检查 id/provider/model 非空，但必须显式调用。YAML 绑定后无 `@PostConstruct` 或 factory-level 校验，misconfigured candidate 可静默传播到运行时 NPE。

**修复方案**: 在 `LlmClientFactory.buildClient()` 或 `LlmClientRegistry` 初始化时调用 `candidate.validate()`。

---

### P2-4: `EmbeddingCapable.embed()` 返回可变 `float[]`

**文件**: `EmbeddingCapable.java`
**维度**: #5 并发安全, #8 内存泄漏

**问题**: `embed()` 返回 `float[]` — 可变数组。如果实现类缓存或复用数组，并发调用者会看到被篡改的数据。`BailianEmbeddingClient.getZeroVector()` 返回共享的可变 `float[]` 静态字段。

**修复方案**: 接口文档明确要求返回 defensive copy，或返回类型改为不可变包装。

---

### P2-5: `EmbeddingCapabilityStrategy` 和 `RerankCapabilityStrategy` DRY 违反

**文件**: `strategy/EmbeddingCapabilityStrategy.java`, `strategy/RerankCapabilityStrategy.java`
**维度**: #1 DRY

**问题**: 两个 strategy 的 `providerFactories` map 构建逻辑几乎完全相同（copy-paste）。

**修复方案**: 提取公共的 `buildProviderFactoriesMap(List<ProviderClientFactory>)` 工具方法到基类或工具类。

---

## P3 — 改进建议

### P3-1: `ChatRequest` 校验不一致

**文件**: `ChatRequest.java:24,82`

Canonical constructor 只检查 `input == null`；Builder.build() 检查 `input == null || input.isBlank()`。两条构造路径语义不同。建议统一为 `isBlank()` 检查。

### P3-2: `ProviderConfig.isAvailable()` 本地地址检测不完整

**文件**: `ProviderConfig.java:47-53`

只检查 `localhost` 和 `127.0.0.1`，遗漏 `0.0.0.0`、`::1`（IPv6 loopback）、私网地址。建议使用 `java.net.InetAddress` 或 URI 解析。

### P3-3: 配置类中的魔法数字

**文件**: `RetryConfig.java`, `CircuitBreakerProperties.java`, `ProbeProperties.java`

`3`, `500`, `5000`, `2.0`, `5`, `30000`, `2`, `3000` 等默认值无命名常量。建议提取为 `private static final` 常量。

### P3-4: `ChatCapable.chatStream()` 返回 `Flux<String>`，丢失结构化元数据

**文件**: `ChatCapable.java`

流式响应无法传递 token usage、truncation、tool calls 等元数据。当前设计是合理的简化，但应在接口文档中说明限制。

### P3-5: `MessageInformation` 无 null 检查

**文件**: `MessageInformation.java`

私有构造器不校验 `role` 和 `content`，`MessageInformation.user(null)` 可创建 null content 的消息。建议加 `Objects.requireNonNull`。

### P3-6: `LlmAutoConfiguration.primaryEmbeddingModel()` 穿透 `AbstractResilientClient` 包装

**文件**: `LlmAutoConfiguration.java:59`

```java
Object target = client instanceof AbstractResilientClient<?> arc ? arc.getDelegate() : client;
```

穿透弹性层获取底层 client，如果包装链变化（如多层装饰器）会静默失效。建议通过 `instanceof` 直接检查 registry 返回的 client 是否同时实现 `EmbeddingModel`。

### P3-7: `LlmClientRegistry.refresh()` 在调用线程顺序关闭旧 client

**文件**: `registry/LlmClientRegistry.java`

refresh 时旧 client 的 close() 在调用线程顺序执行，如果某个 close() 阻塞会延迟整个刷新。建议使用 `TaskScope` 并行关闭（参考 `@PreDestroy` 的实现）。

### P3-8: `RerankRequest` 和 `RerankResult` 有未使用的 `Objects` import

**文件**: `RerankRequest.java`, `RerankResult.java`

dead import，应清理。

---

## 维度逐项评审

### 1. 设计原则合规 ✅

| 原则 | 评价 |
|------|------|
| SRP | ✅ 类职责清晰。Client 只做 HTTP 调用，Strategy 只做路由，Registry 只做生命周期管理 |
| OCP | ✅ 新增 Provider 只需实现 `LlmProvider` + `CapabilityStrategy`，不改已有代码 |
| LSP | ✅ sealed hierarchy 保证子类替换安全 |
| ISP | ✅ `CapabilityClient` → `ChatCapable`/`EmbeddingCapable`/`RerankCapable` 按能力拆分 |
| DIP | ✅ 高层依赖 `CapabilityClient` 接口，不依赖具体 client 实现 |
| DRY | ⚠️ P2-5: Embedding/Rerank strategy 重复逻辑 |
| KISS | ✅ 无过度抽象。两个实现不需要策略接口的地方没有引入 |

### 2. 反模式检测 ✅

- 无 God Class（最大类 ~265 行 GenericChatClient）
- 无循环依赖
- 模块边界清晰：`llm` 包不直接操作其他模块的 Mapper
- 无 `@Transactional`（正确，这是基础设施层）
- 无 Feature Envy

### 3. 资源管理 ✅

- HttpClient 通过 `HttpClientFactory` 统一管理，`@PreDestroy` 关闭
- `CapabilityClient extends AutoCloseable`，所有 client 正确实现 close()
- `LlmClientRegistry` 的 `@PreDestroy` 使用 `TaskScope` 并行关闭所有 client
- SSE 流在 `GenericChatClient.readSse()` 中正确处理 `sink.onCancel`/`onDispose` 清理
- 无 Stream/Connection 泄漏

### 4. 边界条件 ⚠️

- P1-1: `RetryConfig.mergeWithOverride()` null 边界 bug
- P2-3: `validate()` opt-in，无自动校验
- P3-1: `ChatRequest` 校验不一致
- P3-5: `MessageInformation` 无 null 检查
- ✅ 集合边界：`List.copyOf()`/`Map.copyOf()` 防御性拷贝
- ✅ 数值边界：`RerankResult` 检查 NaN/Infinity

### 5. 并发安全 ✅

- 所有字段 `final`（除了 `AbstractModelCandidate` 的 JavaBean setter，仅用于 YAML 绑定初始化阶段）
- `LlmClientRegistry` 使用 `AtomicReference` CAS 实现 lock-free 读
- `LlmCircuitBreakerAdapterRegistry` 使用 `ConcurrentHashMap.computeIfAbsent`
- `RetryPolicy.retryStream` 使用 `AtomicBoolean` 防止下游发射后重试
- `BailianEmbeddingClient` 并发批处理通过 `ScopedTasks` + 预分配数组，写入不同 index，无竞态
- ⚠️ P2-4: `embed()` 返回可变数组的并发风险

### 6. 性能 ✅

- ✅ 数据结构选择合理：`ConcurrentHashMap` 缓存 OkHttpClient、`AtomicReference` 快照
- ✅ `BailianEmbeddingClient` 使用 `ScopedTasks` 并发批处理（`MAX_CONCURRENCY=4`）
- ✅ `RegistrySnapshot` 使用不可变快照避免读锁
- ⚠️ P3-3: 魔法数字（不影响运行时性能，影响可维护性）

### 7. 异常处理 ⚠️

- P2-1: `CapabilityStrategyRegistry.get()` 抛 `RemoteException` 而非 `ServiceException`
- P2-2: `AbstractResilientClient` 使用 `LLM_STREAM_ERROR` 处理所有 checked exception
- ✅ 统一使用 `RemoteException` 包装第三方错误
- ✅ `HttpClientErrorHandler` 正确分类 HTTP 错误码
- ✅ RuntimeException 直接 re-throw，不吞异常
- ✅ `ProbeTimeoutException` 明确排除在 retry 和 CB failure 之外

### 8. 内存泄漏防护 ✅

- 无静态集合泄漏（`LlmMetrics.registeredGauges` 使用 `ConcurrentHashMap.newKeySet()`，量级可控）
- `HttpClientFactory.@PreDestroy` 清理连接池和 dispatcher executor
- 无 ThreadLocal 使用
- ⚠️ P2-4: `BailianEmbeddingClient.getZeroVector()` 返回共享可变数组

### 9. 可扩展性 ✅

- ✅ `ProviderClientFactory` SPI 支持新 Provider 扩展
- ✅ `CapabilityStrategy` SPI 支持新能力类型扩展
- ✅ 配置全部外置到 YAML
- ⚠️ `LlmCapability.yamlKey()` 硬编码 ternary（P3 级，当前只有 3 个枚举值，可接受）
- ⚠️ P3-3: 配置默认值为魔法数字

### 10. 代码质量 ✅

- ✅ 命名清晰：`ResilientChatClient`、`CapabilityStrategyRegistry`、`FallbackExecutor`
- ✅ 方法长度合理（最大 ~50 行 `GenericChatClient.chatStream`）
- ✅ record 用于 DTO，POJO 用于需要默认值的配置绑定（有文档说明选择理由）
- ⚠️ P1-2: 双访问器路径不一致
- ⚠️ P2-5: DRY 违反

---

## 修复优先级建议

| 优先级 | Issue | 工作量 | 影响 |
|--------|-------|--------|------|
| 1 | P1-1 RetryConfig merge bug | 中 | 按能力覆盖重试配置静默失效 |
| 2 | P1-2 AbstractModelCandidate 双访问器 | 小 | Spring 绑定与 SPI 路径不一致 |
| 3 | P2-3 自动调用 validate() | 小 | misconfigured candidate 静默传播 |
| 4 | P2-1 CapabilityStrategyRegistry 异常类型 | 小 | 错误分类影响监控和排查 |
| 5 | P2-2 错误码语义 | 小 | 影响错误监控准确性 |
| 6 | P3-* 改进项 | 小 | 代码质量提升 |

---

## 设计亮点（值得保留）

1. **Sealed hierarchy + POJO** — `ModelCandidate → AbstractModelCandidate → {Chat, Embedding, Rerank}Candidate`，用 sealed 保证类型安全，用 POJO 支持 `@ConfigurationProperties` 的 `boolean enabled = true` 默认值，有文档说明 tradeoff
2. **Registry + Snapshot + AtomicReference CAS** — lock-free 读、copy-on-write 写，高并发场景下无锁竞争
3. **Resilient decorator 模式** — `ResilientChatClient`/`ResilientEmbeddingClient`/`ResilientRerankClient` 组合 retry + circuit breaker + probe，装饰器链清晰
4. **`ScopedTasks` 并发批处理** — `BailianEmbeddingClient` 使用结构化并发处理大批量 embedding，避免阻塞
5. **`HttpClientFactory` 共享连接池** — 通过 timeout 签名缓存 OkHttpClient 实例，避免每个 client 创建独立连接池
