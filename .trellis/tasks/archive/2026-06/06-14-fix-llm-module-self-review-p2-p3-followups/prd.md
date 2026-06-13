# PRD: Fix LLM Module Self-Review P2/P3 Follow-ups

> **Task**: 06-14-fix-llm-module-self-review-p2-p3-followups
> **Source**: `docs/reviews/2026-06-14-infrastructure-llm-spec-review.md`（自身审查 P2/P3 项）
> **Scope**: `src/main/java/com/smart/rag/infrastructure/llm/`
> **Status**: planning

---

## 背景与目标

`docs/reviews/2026-06-14-infrastructure-llm-spec-review.md` 中识别的 P2/P3 改进项。P0/P1（3 项）已在 `488413e` 修复，Mimo 6 项在 `49c150c`。本次完成剩余 P2 + P3 改进。

## 范围（核对后实际待办）

### 已过时/已修复（不在范围）

| 项 | 原因 |
|----|------|
| P3-1 HttpClientErrorHandler Javadoc 矛盾 | 已由 `488413e` sneaky-throw 修复解决 |
| P3-3 CircuitBreaker.execute() fallbackEligibility 双调用 | 当前 `execute()` 中 `fallbackEligibility.isEligible(e)` 仅调用一次 |
| P3-5 BailianEmbeddingClient.zeroVector DCL | `zeroVector` 已是 `private final` 实例字段，构造期初始化，无 DCL 模式 |
| P2-9 ResilientToolCallingChatClient delegation | 已有详细 design note 解释 delegation 是有意为之（避免构造第二层 CB） |

### In Scope（11 项）

| ID | 文件 | 类型 | 改动 |
|----|------|------|------|
| P2-1 | `resilience/CircuitBreaker.java` | Javadoc | `execute()` 添加 Javadoc 说明 `recordSuccess` 在非 CLOSED 状态为 no-op |
| P2-2 | `client/HttpClientErrorHandler.java` | 代码 | 5xx 错误日志级别从 `warn` 提升为 `error`；429 保持 `warn`（瞬态可重试） |
| P2-3 | `resilience/ProbeHandler.java` | Javadoc | 修正 `wrap()` Javadoc：原描述"onProbeSuccess 为 null 时不添加回调"不准确——两条路径（in-flight 复用 / delegate 走探测）都会注册回调 |
| P2-4 | `client/bailian/BailianEmbeddingClient.java` + 新文件 | 重构 | 将 Spring AI `EmbeddingModel` 适配方法提取到独立 `BailianSpringAiEmbeddingAdapter`（260 行 → ~140 + ~80） |
| P2-5 | `ChatRequest.java` | 代码 | 错误消息改为指明字段：`"输入内容不能为 null"` → `"ChatRequest.input 不能为 null"`；同理 `Builder.build()` 的 `"输入内容不能为空"` |
| P2-6 | `registry/LlmClientRegistry.java` | 代码 | `DESTROY_TIMEOUT` 和 `DESTROY_CONCURRENCY` 改为通过构造器注入（默认值保留） |
| P2-7 | `client/HttpClientErrorHandler.java` | 代码 | 新增 `ResourceAccessException` 解包分支：检测 `getCause() instanceof IOException` 后按 IO 异常处理 |
| P2-8 | `resilience/ProbeHandler.java` | 重构 | 提取 `awaitInFlightProbe(candidateId)` 私有方法，主 `wrap()` 方法保持 ≤2 层嵌套 |
| P3-2 | `ChatRequest.java` | 代码 | 错误消息本地化（与 P2-5 合并到同一改动）—— 现有 `BAD_REQUEST` 错误码保留，无需新增 |
| P3-4 | `resilience/ProbeHandler.java` | Javadoc | 类级 Javadoc 说明为何不标 `@Component`（由 `LlmClientFactory` 按 candidate 实例化） |

## 详细修复方案

### P2-2 HttpClientErrorHandler 日志级别

```java
if (status == 429) {
    code = RemoteErrorCode.LLM_RATE_LIMITED;
    log.warn("{} 请求被限流: HTTP 429 from {} - {}", operation, url, body);
} else if (status >= 500) {
    code = RemoteErrorCode.LLM_TRANSIENT_ERROR;
    log.error("{} 请求服务端错误: HTTP {} from {} - {}", operation, status, url, body);
}
```

**理由**：5xx 是服务端故障（业务可见），429 是限流（瞬态可重试）。日志级别区分便于运维告警。

### P2-4 BailianEmbeddingClient 拆分

新建 `client/bailian/BailianSpringAiEmbeddingAdapter.java`：
- 实现 Spring AI `EmbeddingModel` 接口
- 持有 `BailianEmbeddingClient` 引用（委托）
- 暴露 `embed(Document)`、`embed(String)`、`call(EmbeddingRequest)`、`embed(List<String>)`、`dimensions()`

`BailianEmbeddingClient` 仅保留：
- `EmbeddingCapable` SPI 实现（`embed(String, EmbeddingType)`、`embedBatch`、`dimension`）
- DashScope API 调用基础设施（`callApi`、`doPost`、`extractFirst/All`）
- `close()` 生命周期

**调用点适配**：
- `LlmAutoConfiguration.primaryEmbeddingModel()` 检查 `instanceof EmbeddingModel` → 改为检查 `instanceof BailianSpringAiEmbeddingAdapter` 或在工厂层直接构造 adapter
- `BailianEmbeddingClientFactory` 创建 client 后包装 adapter

### P2-6 DESTROY_TIMEOUT/CONCURRENCY 配置化

```java
public LlmClientRegistry(LlmClientFactory factory, ScopedTasks scopedTasks,
                          Duration destroyTimeout, int destroyConcurrency) { ... }

// 默认值通过 @Bean 或配置属性注入
```

**实现**：在 `LlmAutoConfiguration` 或独立 `@ConfigurationProperties(prefix="llm.registry")` 中提供默认值，保持 YAML 可覆盖。

### P2-7 ResourceAccessException 解包

```java
if (e instanceof ResourceAccessException rae && rae.getCause() instanceof IOException ioCause) {
    return translate(operation, url, ioCause);  // 委托给已有 IOException 分支
}
```

放在 IOException 分支前。

### P2-8 ProbeHandler.wrap() 重构

```java
public Flux<String> wrap(String candidateId, Flux<String> raw, @Nullable Runnable onProbeSuccess) {
    Flux<String> probed = wrapWithInFlightProbeOrDelegate(candidateId, raw);
    if (onProbeSuccess == null) return probed;
    AtomicBoolean notified = new AtomicBoolean(false);
    return probed.doOnNext(first -> {
        if (notified.compareAndSet(false, true)) onProbeSuccess.run();
    });
}

private Flux<String> wrapWithInFlightProbeOrDelegate(String candidateId, Flux<String> raw) {
    if (probeRegistry == null) return delegate.wrapWithProbe(candidateId, raw);
    CompletableFuture<ProbeResult> inFlight = probeRegistry.getInFlight(candidateId);
    if (inFlight == null) return delegate.wrapWithProbe(candidateId, raw);
    return Mono.<ProbeResult>fromFuture(() -> inFlight)
        .timeout(Duration.ofMillis(probeTimeoutMs))
        .flatMap(probeResult -> probeResult.success()
            ? Mono.empty()
            : Mono.error(new ProbeTimeoutException("In-flight probe failed for " + candidateId)))
        .thenMany(Flux.defer(() -> raw));
}
```

## 验证

- `mvn clean compile` BUILD SUCCESS
- 现有 96 项 LLM 测试全绿
- 新增 BailianSpringAiEmbeddingAdapter 后，`AnswerRelevanceScorer` + `PgVectorStore` 调用链验证（如有测试）
- `gitnexus_detect_changes` 确认影响范围

## 提交策略

单一 commit：
```
refactor(llm): address self-review P2/P3 follow-ups

- HttpClientErrorHandler: ERROR for 5xx (was WARN); unwrap ResourceAccessException
- ProbeHandler: extract wrapWithInFlightProbeOrDelegate helper; clarify Javadoc
- BailianEmbeddingClient: split Spring AI EmbeddingModel adapter (260 → ~140 lines)
- LlmClientRegistry: externalize DESTROY_TIMEOUT/CONCURRENCY via constructor injection
- ChatRequest: error messages specify field name (ChatRequest.input)
- CircuitBreaker.execute: document recordSuccess no-op semantics in non-CLOSED state
```
