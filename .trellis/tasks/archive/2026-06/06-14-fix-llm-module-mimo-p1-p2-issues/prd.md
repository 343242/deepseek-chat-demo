# PRD: Fix LLM Module Mimo Review P1/P2 Issues

> **Task**: 06-14-fix-llm-module-mimo-p1-p2-issues
> **Source**: `docs/reviews/2026-06-13-llm-infrastructure-layer-review.md`（Mimo 审查报告）
> **Cross-ref**: `docs/reviews/2026-06-14-infrastructure-llm-spec-review.md`（自身审查）
> **Scope**: `src/main/java/com/smart/rag/infrastructure/llm/`
> **Status**: planning

---

## 背景与目标

Mimo 审查报告发现 7 项问题，经核对后 1 项（P2-3 validate opt-in）已在 `config/ModelGroup.java:83` 调用 `c.validate()` 自动校验，过时；剩余 **6 项真实问题（P1×2 + P2×4）** 需修复。本任务完成全部 6 项修复并通过编译/测试验证。

## 范围

### In Scope（6 项修复）

| ID | 文件 | 严重度 | 问题 |
|----|------|--------|------|
| P1-1 | `config/RetryConfig.java` | P1 | compact constructor 把 null 替换为默认值，破坏 `mergeWithOverride()` 语义 |
| P1-2 | `AbstractModelCandidate.java` | P1 | `params()` null-safe 但 `getParams()` 返回原始字段，双路径不一致 |
| P2-1 | `strategy/CapabilityStrategyRegistry.java` | P2 | 缺失策略属本地配置错误，抛 `RemoteException` 应改 `ServiceException` 或 `IllegalStateException` |
| P2-2 | `resilience/AbstractResilientClient.java` | P2 | 所有 checked exception 用 `LLM_STREAM_ERROR`，应改为通用错误码 |
| P2-4 | `EmbeddingCapable.java` + `client/bailian/BailianEmbeddingClient.java` | P2 | `embed()` 返回可变 `float[]`；`zeroVector` 实例字段被多调用者共享 |
| P2-5 | `strategy/EmbeddingCapabilityStrategy.java` + `RerankCapabilityStrategy.java` | P2 | 两个 strategy 的 `providerFactories` map 构建逻辑 copy-paste |

### Out of Scope

- P2-3（已过时，`ModelGroup.toModelCandidates()` 已自动 `validate()`）
- P3-* 改进项（用户已确认跳过）
- 我自身审查的 9 项 P2 + 5 项 P3（独立 follow-up）

## 详细修复方案

### P1-1 RetryConfig.mergeWithOverride

**当前代码** (`config/RetryConfig.java:25-49`)：
```java
public RetryConfig {
    if (maxAttempts == null || maxAttempts <= 0) maxAttempts = 3;   // ← null → 3
    if (baseDelayMs == null || baseDelayMs <= 0) baseDelayMs = 500L;
    // ...
}
public RetryConfig mergeWithOverride(RetryConfig override) {
    return new RetryConfig(
        override.maxAttempts != null ? override.maxAttempts : this.maxAttempts,  // ← override.maxAttempts 已被替换为 3，永远 != null
        // ...
    );
}
```

**修复**：移除 compact constructor 中的 null 替换；保留 `effective*()` 方法在调用点提供默认值；保留 compact constructor 仅做范围校验（`<= 0` 检查应允许 null 跳过）。

```java
public RetryConfig {
    // 仅校验非 null 字段的范围，允许 null 透传以支持 mergeWithOverride 语义
    if (maxAttempts != null && maxAttempts <= 0)
        throw new IllegalArgumentException("maxAttempts must be positive");
    if (baseDelayMs != null && baseDelayMs <= 0)
        throw new IllegalArgumentException("baseDelayMs must be positive");
    if (maxDelayMs != null && maxDelayMs <= 0)
        throw new IllegalArgumentException("maxDelayMs must be positive");
    if (multiplier != null && multiplier <= 0)
        throw new IllegalArgumentException("multiplier must be positive");
}
```

**调用点适配**：使用 `effectiveMaxAttempts()` 等方法替代直接字段访问（`RetryPolicy.java` 等调用方）。

**测试**：新增 `RetryConfigTest`，覆盖：
- 全 null 构造 + effective*() 返回默认值
- mergeWithOverride 只覆盖部分字段时保留 base 配置
- 部分字段非 null + 非法值抛 IllegalArgumentException

---

### P1-2 AbstractModelCandidate 双访问器

**当前** (`AbstractModelCandidate.java:36,48`)：
```java
@Override public Map<String, Object> params() { return params != null ? params : Map.of(); }  // null-safe
public Map<String, Object> getParams() { return params; }                                      // 原始字段
```

**修复**：统一两个访问器：
```java
public Map<String, Object> getParams() { return params != null ? params : Map.of(); }
```

**风险评估**：低 — `params` 字段已初始化为 `Map.of()`，`setParams` 也做了 null 检查；改动仅为防御性一致。

---

### P2-1 CapabilityStrategyRegistry 异常类型

**当前** (`strategy/CapabilityStrategyRegistry.java:34-37`)：
```java
public CapabilityStrategy get(LlmCapability cap) {
    CapabilityStrategy s = strategies.get(cap);
    if (s == null) {
        throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
            "No CapabilityStrategy registered for " + cap);
    }
    return s;
}
```

**修复**：按 spec `error-handling.md` 规范，本地配置错误应抛 `ServiceException` 或 `IllegalStateException`。优先用 `IllegalStateException`（属于未配置的开发期错误）：

```java
throw new IllegalStateException(
    "No CapabilityStrategy registered for " + cap
    + " — ensure corresponding @Component strategy is on classpath");
```

**调用点影响**：`LlmClientFactory` 调用 `strategyRegistry.get(cap)` — 该错误会作为 IllegalStateException 在启动期暴露，调用方无需新增 catch。

---

### P2-2 AbstractResilientClient.executeResilient 错误码

**当前** (`resilience/AbstractResilientClient.java:87`)：
```java
throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR,
    "Unexpected checked exception from LLM action: " + e.getMessage(), e);
```

**修复**：checked exception 在 chat/embed/rerank 三种场景都可能抛，不应限定 stream 语义。改为 `LLM_TRANSIENT_ERROR`（可重试归类）或新增 `LLM_UNEXPECTED_ERROR`。优先复用现有 `LLM_TRANSIENT_ERROR`：

```java
throw new RemoteException(RemoteErrorCode.LLM_TRANSIENT_ERROR,
    "Unexpected checked exception from LLM action: " + e.getMessage(), e);
```

**风险评估**：低 — 该分支仅在 checked exception（非 RuntimeException）出现时触发，实际很少命中；改为 TRANSIENT 后该异常会被 retry/circuit breaker 正确分类。

---

### P2-4 embed() 返回可变 float[] / zeroVector 共享

**当前**：
- `EmbeddingCapable.java` — `float[] embed(...)` 接口契约未说明不可变性
- `BailianEmbeddingClient.java:49,231-232` — `private final float[] zeroVector` 实例字段，`getZeroVector()` 直接返回引用

**修复**：
1. `EmbeddingCapable.java` Javadoc 明确契约：调用者不得修改返回数组；如需修改应自行复制
2. `BailianEmbeddingClient.getZeroVector()` 返回 defensive copy：`return zeroVector.clone();`
3. 添加 Javadoc 说明：zeroVector 在多次调用间不可变（每次返回新拷贝）

**性能影响**：每次 `embed("")` 调用产生一次 `dimension()` 大小的数组 clone（如 1536 floats = 6KB），可接受。

---

### P2-5 Embedding/Rerank strategy DRY

**当前**：两个 strategy 的构造器和 `createClient` 几乎完全 copy-paste，仅 `capability()` 过滤值和 fallback client 类不同。

**修复方案 A（推荐）**：抽 `AbstractProviderFactoryAwareStrategy` 抽象基类
```java
public abstract class AbstractProviderFactoryAwareStrategy implements CapabilityStrategy {
    private final Map<String, ProviderClientFactory> providerFactories;

    protected AbstractProviderFactoryAwareStrategy(List<ProviderClientFactory> factories) {
        this.providerFactories = factories.stream()
            .filter(f -> f.capability() == capability())
            .collect(Collectors.toUnmodifiableMap(
                f -> f.providerId() + ":" + f.capability(),
                Function.identity(),
                (a, b) -> { throw new IllegalStateException(...); }));
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        ProviderClientFactory factory = providerFactories.get(
            candidate.provider() + ":" + capability());
        if (factory != null) return factory.create(baseUrl, endpoint, apiKey, candidate);
        return createGenericClient(baseUrl, endpoint, apiKey, candidate);
    }

    /** 子类提供 generic fallback client 实例 */
    protected abstract CapabilityClient createGenericClient(
        String baseUrl, String endpoint, String apiKey, ModelCandidate candidate);
}
```

`EmbeddingCapabilityStrategy` 和 `RerankCapabilityStrategy` 各只剩 ~10 行。

**修复方案 B（轻量）**：抽 `static` 工具方法 `ProviderFactoryMaps.build(List, LlmCapability)` — 不引入新抽象层。

**选择 A**：strategy 模式本就该有公共基类；方案 A 让 `ChatCapabilityStrategy` 也可继承（虽然当前 chat 没有 providerFactories）。

---

## 验证

### 编译验证
```bash
mvn -pl . compile -q -DskipTests
```

### 单元测试
- 新增 `RetryConfigTest`（P1-1 关键回归）
- 现有 `CircuitBreakerTest`、`RetryPolicyTest`、`EmbeddingCapabilityStrategyTest` 等需通过

### 静态分析
- `mvn checkstyle:check`（如配置）
- IDE inspection 确认无新增 warning

### 影响分析（GitNexus）
- 每个修改前 `gitnexus_impact({target: "symbolName", direction: "upstream"})`
- 提交前 `gitnexus_detect_changes()`

## 出口标准

- [ ] 6 项修复全部完成，编译通过
- [ ] 新增 `RetryConfigTest` 覆盖 P1-1 回归
- [ ] 现有相关测试全绿
- [ ] 提交前 `gitnexus_detect_changes()` 影响范围与预期一致
- [ ] commit message 遵循项目风格（参考 `git log -- src/main/java/com/smart/rag/infrastructure/llm/`）

## 提交策略

单一 commit：
```
refactor(llm): address Mimo P1/P2 review findings

- RetryConfig compact constructor preserves null for mergeWithOverride
- AbstractModelCandidate getParams() aligns with params() null-safety
- CapabilityStrategyRegistry uses IllegalStateException for missing strategy
- AbstractResilientClient uses LLM_TRANSIENT_ERROR for checked exceptions
- EmbeddingCapable Javadoc + BailianEmbeddingClient defensive zeroVector copy
- Extract AbstractProviderFactoryAwareStrategy base to DRY strategy classes
```
