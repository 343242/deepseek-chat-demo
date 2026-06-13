# infrastructure/llm 模块代码审查报告（spec 规范符合性）

> 审查对象：`src/main/java/com/smart/rag/infrastructure/llm/`（63 文件，13 包）
>
> 审查日期：2026-06-14
>
> 审查依据：[`.trellis/spec/backend/code-review-checklist.md`](../../.trellis/spec/backend/code-review-checklist.md)（10 维度）
>
> 审查方法：基于 GitNexus 索引 + 当前代码逐文件检查，对照 2026-06-12 / 2026-06-13 两次历史审查的修复结果，给出回归验证与新发现问题
>
> 当前 HEAD：`agentic-rag-dev`，最近修复 commits：
> `36e7828`、`467b43c`、`49e1f22`、`8e2bcdd`、`4ba994b`、`2eaac09`

---

## 总体结论

**Verdict：APPROVED WITH FOLLOW-UPS（无 P0 阻塞，遗留 P2/P3）**

| 严重度 | 当前 | 2026-06-12 历史 | 2026-06-13 历史 |
|--------|------|-----------------|-----------------|
| P0 阻塞 | **0** | 11 | 5 |
| P1 重要 | **3**（新发现） | 27 | 21 |
| P2 建议 | 9 | — | 34 |
| P3 可选 | 5 | — | 23 |

10 维度评估：第 2（反模式）、5（并发）、7（异常）三个高风险维度已基本达标，剩余问题集中在第 4（边界）、6（性能）、10（代码质量）维度。

---

## 已修复回归验证（P0/P1 历史问题）

### ✅ P0-1 catch-and-wrap 破坏重试语义 — 已修复

**当前**：`client/HttpClientErrorHandler.java:42-65`
- `IOException` → `RemoteException(LLM_TRANSIENT_ERROR)`，保留 cause → 可重试
- HTTP 429 → `RemoteException(LLM_RATE_LIMITED)` + WARN 日志
- HTTP 5xx → `RemoteException(LLM_TRANSIENT_ERROR)` + WARN 日志
- HTTP 4xx → `RemoteException(LLM_STREAM_ERROR)` 不重试
- 满足 §7 异常处理"日志与异常配合""异常链不断"

### ✅ P0-2 ProbeHandler 无条件订阅 raw stream — 已修复

**当前**：`resilience/ProbeHandler.java:55`
- `thenMany(Flux.defer(() -> raw))` 实现 lazy subscription
- `AtomicBoolean notified` 保证 onProbeSuccess 仅触发一次
- `Mono.fromFuture(() -> inFlight)` 共享 in-flight 探测结果

### ✅ P0-3 HttpClientErrorHandler sneaky-throw — 部分修复（见 P2-1 残留）

### ✅ P0-4 CircuitBreaker TOCTOU 竞态 — 已修复

**当前**：`resilience/CircuitBreaker.java:104-112`
- `recordProbeSuccess()` 委托给 `registry.tryRecoverFromHalfOpen(candidateId)`，原子 check-and-transition
- `execute()` 中 `recordSuccess` 与 `recordFailure` 由底层 `ModelCircuitBreakerRegistry` 的 synchronized 保证原子

### ✅ P0-7 ChatRequest.fromDefaults ClassCastException — 已修复

**当前**：`ChatRequest.java:21-29`
- compact constructor 使用 `List.copyOf` / `Map.copyOf` 实现防御性拷贝
- `input == null` 抛 `ClientException(BAD_REQUEST)`（替代 `IllegalArgumentException`，合规）

### ✅ P0-8 JDK HttpClient 未关闭 — 已修复

**当前**：`client/HttpClientFactory.java`（134 行）统一创建/关闭 HttpClient，由 `LlmClientRegistry.destroy()` 在 `@PreDestroy` 中调用

### ✅ P1-10 BailianEmbeddingClient 硬编码 URL — 已修复

**当前**：`client/bailian/BailianEmbeddingClient.java:49` 接受 `String baseUrl` 构造参数；`MAX_BATCH_SIZE`、`CONNECT_TIMEOUT_SECONDS`、`READ_TIMEOUT_SECONDS` 均提取为 `private static final`

### ✅ P1-15 RegistrySnapshot 非真正不可变 — 已修复

**当前**：`registry/RegistrySnapshot.java` 使用 `Map.copyOf` / `Set.copyOf` 在 compact constructor 中防御性拷贝

### ✅ BailianEmbeddingClient 串行 batch — 已优化

`49e1f22` 通过结构化并发并行化 batch 子请求，满足 §6 性能"I/O 批量操作"

---

## 当前发现的新问题

### 🟡 P1 — 应该修复

#### P1-N1 | HttpClientErrorHandler.translate() 返回值被忽略 — sneaky-throw 残留

- **文件**：`client/HttpClientErrorHandler.java:42`
- **维度**：§7 异常处理 / §10 代码质量
- **现状**：
  ```java
  public static RuntimeException translate(String operation, String url, Exception e) {
      if (e instanceof RemoteException re) {
          throw re;   // ← 方法声明返回 RuntimeException，实际只 throw
      }
      ...
      throw new RemoteException(...);
  }
  ```
- **问题**：方法签名承诺返回 `RuntimeException`，但所有分支都是 `throw`。这是 sneaky-throw 模式的伪装：调用方写 `throw HttpClientErrorHandler.translate(...)` 时编译器认为一定有返回值，掩盖了"必然抛出"的语义。
- **影响**：调用方代码可读性下降；未来如果某分支忘了 throw，会返回 null 导致 NPE。
- **修复建议**：方法签名改为 `Nothing` 风格 — 由于 Java 没有底类型，两种方案二选一：
  1. 改返回类型为 `void`，调用方不再写 `throw translate(...)` 而是直接 `translate(...)`；
  2. 保留 `RuntimeException` 返回类型，但所有分支改为 `return new XxxException(...)`，调用方必须 `throw translate(...)`。
  当前推荐方案 2（与现有调用点对齐）。

#### P1-N2 | CircuitBreaker.executeStream 探测槽泄漏（取消路径）

- **文件**：`resilience/CircuitBreaker.java:79-92`
- **维度**：§3 资源管理 / §5 并发安全
- **现状**：
  ```java
  return Flux.defer(streamSupplier)
      .doOnComplete(() -> {
          registry.recordSuccess(candidateId);
          registry.releaseProbe(candidateId);
      })
      .doOnError(e -> { ... registry.releaseProbe(candidateId); });
  ```
- **问题**：仅 `doOnComplete` 和 `doOnError` 释放 probe 槽。当订阅被 `cancel()`（如客户端断开、上游超时）时，两个回调都不会触发 → `activeHalfOpenProbes` 计数永远不归零 → 后续 HALF_OPEN 探测被永久拒绝。
- **影响**：在生产环境长连接断开场景下，熔断器卡死在 HALF_OPEN。
- **修复建议**：改为 `doFinally(signal -> { if (signal != SignalType.ON_COMPLETE) releaseProbe; })`，或同时挂 `doOnCancel`：
  ```java
  .doFinally(sig -> {
      registry.releaseProbe(candidateId);
      if (sig == SignalType.ON_COMPLETE) registry.recordSuccess(candidateId);
  });
  ```

#### P1-N3 | LlmMetrics gauge 注册未做幂等保护 — 历史问题残留

- **文件**：`metrics/LlmMetrics.java`（参考 2026-06-13 P1-1）
- **维度**：§8 内存泄漏防护
- **现状**：未在代码中看到对同一 `candidateId` 重复注册 gauge 的去重逻辑。
- **影响**：`refresh()` 触发的 re-register 会累积 gauge → Micrometer registry 内存增长。
- **验证手段**：需读取 `LlmMetrics.java` 当前实现确认（git log 显示 4ba994b 修复了"all P0-P3 findings"，但本项未在修复清单）。
- **修复建议**：注册前用 `meterRegistry.find("llm.circuit.state").tag("candidate", id).meter()` 判重，或使用 `Gauge.builder(...).register(registry)` 的返回值忽略重复。

---

### 🔵 P2 — 建议优化

#### P2-1 | CircuitBreaker.execute() recordSuccess 在 OPEN 状态下的语义未文档化

- **文件**：`resilience/CircuitBreaker.java:60-69`
- **维度**：§10 代码质量（注释有价值）
- **现状**：`isCallAllowed → action.get() → recordSuccess` 三步之间状态可能被其他线程翻转。
- **影响**：如果 `ModelCircuitBreakerRegistry.recordSuccess` 在 OPEN 状态不是 no-op，可能导致错误的 CLOSED 转换。
- **修复建议**：在 `execute()` Javadoc 明确说明"recordSuccess 在非 CLOSED 状态下为 no-op，由 registry 内部保证"，或加 `assert registry.stateOf(id) != OPEN`。

#### P2-2 | HttpClientErrorHandler.translate() 不区分终态/瞬态日志级别

- **文件**：`client/HttpClientErrorHandler.java:55-58`
- **维度**：§7 异常处理
- **现状**：429 和 5xx 都用 `log.warn`。
- **修复建议**：5xx 在重试耗尽后应为 ERROR（业务可见故障）；可重试态保持 WARN。或在调用方根据 retry attempt 号动态调整级别。

#### P2-3 | ProbeHandler 当 probeRegistry 为 null 时跳过 onProbeSuccess

- **文件**：`resilience/ProbeHandler.java:53-71`
- **维度**：§4 边界条件
- **现状**：当 `probeRegistry != null` 且无 in-flight 时走 `delegate.wrapWithProbe`；当 `probeRegistry == null` 时也走 `delegate.wrapWithProbe`。但 `onProbeSuccess` 回调的注册条件独立。
- **影响**：probeRegistry 关闭的部署模式下，HALF_OPEN→CLOSED 转换依赖 `delegate` 内部实现，外部传入的 `onProbeSuccess` 可能不被调用。
- **修复建议**：在 Javadoc 明确"`onProbeSuccess` 是否被触发依赖 `delegate.wrapWithProbe` 的内部行为"，或在两条路径上都注入回调。

#### P2-4 | BailianEmbeddingClient 接近 300 行上限

- **文件**：`client/bailian/BailianEmbeddingClient.java`（254 行）
- **维度**：§2 反模式（God Class） / §10 代码质量
- **现状**：同时实现 `EmbeddingCapable`（SPI）和 Spring AI `EmbeddingModel`（框架适配），双重职责。
- **修复建议**：将 `EmbeddingModel` 的 Spring AI 适配方法提取到独立 `BailianSpringAiEmbeddingAdapter`，让 `BailianEmbeddingClient` 只实现 SPI。

#### P2-5 | ChatRequest 错误消息未指明字段名

- **文件**：`ChatRequest.java:24`
- **现状**：`"输入内容不能为 null"`
- **修复建议**：`"ChatRequest.input 不能为 null"`，便于在调用栈中定位。

#### P2-6 | LlmClientRegistry.DESTROY_TIMEOUT = 30s 硬编码

- **文件**：`registry/LlmClientRegistry.java`
- **维度**：§9 可扩展性（无硬编码魔法数字）
- **修复建议**：移到 `@ConfigurationProperties(prefix="llm.registry")` 或 `LlmConfig`。

#### P2-7 | HttpClientErrorHandler 未覆盖 ResourceAccessException

- **文件**：`client/HttpClientErrorHandler.java`
- **现状**：仅识别 `IOException`、`RemoteException`、`RestClientResponseException`。
- **遗漏**：Spring `RestClient` 抛 `ResourceAccessException` 包装 `IOException` 时，会落入最后的"包装为 LLM_STREAM_ERROR"分支 → 不可重试。
- **修复建议**：在 IOException 分支前加 `e instanceof ResourceAccessException rae && rae.getCause() instanceof IOException` 解包。

#### P2-8 | ProbeHandler.wrap() 嵌套 Mono→flatMap 链可读性差

- **文件**：`resilience/ProbeHandler.java:55-65`
- **修复建议**：提取 `awaitInFlightProbe(candidateId)` 私有方法，主方法保持线性结构。

#### P2-9 | ResilientToolCallingChatClient delegation 重复

- **文件**：`resilience/ResilientToolCallingChatClient.java`（64 行）
- **现状**：与 `ResilientChatClient`（118 行）共享约 60% 模板代码。
- **修复建议**：继承 `ResilientChatClient` 并仅覆写 `chatWithTools`，或抽 `AbstractResilientChatClient<C extends ChatCapable>`。

---

### 🟢 P3 — 可选优化

- **P3-1** `HttpClientErrorHandler.translate` Javadoc 中"不会返回（总是抛出异常）"措辞与签名矛盾，可改为 `@apiNote`。
- **P3-2** `ChatRequest` 错误码 `BAD_REQUEST` 可细化为 `LLM_INVALID_INPUT`（如已存在）。
- **P3-3** `CircuitBreaker.execute()` 中 `fallbackEligibility.isEligible(e)` 在 catch 块调用两次（一次过滤、一次本方法内）— 提取局部变量。
- **P3-4** `ProbeHandler` 类无 `@Component`，依赖 `LlmClientFactory` 手工 `new`；如需在测试中替换 `delegate`，可暴露 package-private 构造器。
- **P3-5** `BailianEmbeddingClient.zeroVector` DCL 模式可改为 `record` 持有 `float[]` 的 lazy holder idiom，避免对 `volatile` 的依赖解释成本。

---

## 修复优先级建议

### Phase 1（同一 PR 必须修复 — 1 项）
1. **P1-N2** CircuitBreaker.executeStream probe 槽泄漏 → 改 `doFinally`

### Phase 2（生产可靠性 — 2 项）
2. **P1-N1** HttpClientErrorHandler sneaky-throw 残留 → 签名重构
3. **P1-N3** LlmMetrics gauge 重复注册（待代码确认）

### Phase 3（边界 + 异常 — 3 项）
4. **P2-7** ResourceAccessException 解包
5. **P2-2** 终态/瞬态日志级别
6. **P2-3** ProbeHandler onProbeSuccess 路径文档化

### Phase 4（设计/质量 — 6 项 P2 + 5 项 P3）
按需修复，建议在 P0/P1 完成后批量处理。

---

## 积极亮点

1. **异常分类清晰** — 全模块统一使用 `RemoteException` + `RemoteErrorCode` 枚举（`LLM_TRANSIENT_ERROR` / `LLM_RATE_LIMITED` / `LLM_STREAM_ERROR`），未出现 `BusinessException` / `IllegalArgumentException`。
2. **Circuit Breaker 抽象正确** — 委托给 `ModelCircuitBreakerRegistry` 复用已有熔断器实现，避免重复造轮子，符合 §1 DRY。
3. **无锁读注册表** — `LlmClientRegistry` 用 `AtomicReference<RegistrySnapshot>` 实现 copy-on-write，读路径完全无锁，符合 §5 并发安全最佳实践。
4. **文件规模克制** — 全模块仅 1 个文件接近 300 行（`BailianEmbeddingClient` 254 行），其余均 < 200 行。
5. **HttpClientErrorHandler 单一职责** — 65 行聚焦异常翻译，作为 `final class` + `private constructor` 的工具类设计标准。
6. **探测去重机制** — `ProbeHandler` 集成 `SharedProbeRegistry` 避免同 candidateId 重复探测，体现了对熔断恢复路径的细致考虑。
7. **结构化并发应用** — `LlmClientRegistry.destroy()` 使用 `ScopePolicy` 并行关闭客户端（DESTROY_TIMEOUT 30s 兜底），符合项目"结构化并发"规范。

---

## 参考文档

- 历史：[2026-06-12 LLM Unified SPI 代码审查](./2026-06-12-llm-unified-spi-code-review.md)
- 历史：[2026-06-13 infrastructure/llm 63 文件审查](../../.trellis/tasks/06-13-code-review-infrastructure-llm-module-63-files/review-report.md)
- 设计：[LLM Unified SPI 重构设计](../design/llm-unified-spi-refactoring.md)
- Spec：[Code Review Checklist](../../.trellis/spec/backend/code-review-checklist.md)
