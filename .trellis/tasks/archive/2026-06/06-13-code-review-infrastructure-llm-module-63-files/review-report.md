# Code Review Report: infrastructure/llm

> **Scope:** `src/main/java/com/smart/rag/infrastructure/llm/` (63 files, 13 packages)
> **Reviewer:** code-reviewer agents (parallel, 6 domains)
> **Date:** 2026-06-13
> **Standard:** `.trellis/spec/backend/code-review-checklist.md` (10 dimensions)

---

## Summary

| Severity | Count | Description |
|----------|-------|-------------|
| **P0 CRITICAL** | 5 | Must fix before production — will cause incidents |
| **P1 HIGH** | 21 | Should fix — significant quality/reliability issues |
| **P2 MEDIUM** | 34 | Consider fixing — minor improvements |
| **P3 LOW** | 23 | Optional — nitpicks and suggestions |
| **Total** | **83** | |

### Verdict: **REQUEST CHANGES**

5 个 P0 问题必须在合并前修复。整体架构设计良好（sealed hierarchy、ISP、lock-free registry），但并发安全、资源管理和异常处理存在生产风险。

---

## P0 CRITICAL (Must Fix)

### P0-1 | ProbeHandler double timeout on raw stream
- **File:** `resilience/ProbeHandler.java:59-60`
- **Dimension:** 并发安全
- **Issue:** `wrap()` 中 in-flight probe 存在时，`.timeout(probeTimeoutMs)` 同时应用于 probe 等待和后续 raw stream 发射。probeTimeoutMs（通常 3s）被错误地当作全流超时，导致正常 stream 被提前终止。
- **Fix:** 移除 line 60 的 `.timeout()`。probe 阶段超时由 line 51 已覆盖，stream 首包超时由 `delegate.wrapWithProbe` 处理。

### P0-2 | ProbeHandler subscribes to raw stream unconditionally
- **File:** `resilience/ProbeHandler.java:59`
- **Dimension:** 并发安全
- **Issue:** `thenMany(raw)` 无论 in-flight probe 成功与否都会订阅 raw stream，违背 probe 去重目的。应改为 `thenMany(Flux.defer(() -> raw))` 实现 lazy subscription。
- **Fix:** 替换为 `Flux.defer(() -> raw)` 确保 probe 成功后才订阅。

### P0-3 | HttpClientErrorHandler sneaky-throws IOException
- **File:** `client/HttpClientErrorHandler.java:36-41`
- **Dimension:** 异常处理 / 类型安全
- **Issue:** `translate()` 方法声明返回 `RuntimeException` 但实际 sneaky-throw `IOException`，绕过 checked exception 体系。如果 retry 层只 catch `RemoteException`，则 `IOException` 会被漏掉，导致重试静默失效。
- **Fix:** 将 `IOException` 包装为 `RemoteException` 而非 sneaky-throw，保持异常层级一致性。

### P0-4 | GenericChatClient dual HTTP client resource leak
- **File:** `client/generic/GenericChatClient.java:55,66`
- **Dimension:** 资源管理
- **Issue:** 同时持有 `java.net.http.HttpClient`（blocking）和 `OkHttpClient`（SSE），两套连接池/线程池。若 registry 替换 client 时未调用 `close()`，两套资源均泄漏。`chatStream()` 中 OkHttp Call 在异常路径也可能泄漏。
- **Fix:** 统一为单一 HTTP 客户端技术（推荐 OkHttp 同时用于 blocking 和 streaming）。添加 `sink.onDispose(call::cancel)` 增强资源清理。

### P0-5 | @Autowired field injection in LlmAutoConfiguration
- **File:** `config/LlmAutoConfiguration.java:35`
- **Dimension:** 代码质量（禁止模式）
- **Issue:** `@Autowired(required = false) private MeterRegistry meterRegistry` 使用了项目明确禁止的字段注入模式。
- **Fix:** 改为构造器注入：
  ```java
  private final MeterRegistry meterRegistry;
  public LlmAutoConfiguration(@Autowired(required = false) MeterRegistry meterRegistry) {
      this.meterRegistry = meterRegistry;
  }
  ```

---

## P1 HIGH (Should Fix)

### P1-1 | LlmMetrics gauge registration leak on duplicate calls
- **File:** `metrics/LlmMetrics.java:69-78`
- **Issue:** `registerCircuitBreakerGauge` 对同一 candidateId 重复调用会产生重复 gauge 注册（Micrometer gauge 不幂等），导致内存泄漏。

### P1-2 | Missing @ConditionalOnMissingBean on LlmAutoConfiguration beans
- **File:** `config/LlmAutoConfiguration.java:39,49`
- **Issue:** 两个 `@Bean` 方法均无 `@ConditionalOnMissingBean`，阻止用户覆盖。

### P1-3 | Missing @AutoConfigureAfter ordering
- **File:** `config/LlmAutoConfiguration.java:30`
- **Issue:** 依赖 `LlmClientRegistry` 但无 `@AutoConfigureAfter` 声明，非确定性启动顺序可能导致失败。

### P1-4 | Duplicate ChatModel import
- **File:** `adapter/ChatModelAdapter.java:5,10`
- **Issue:** `import org.springframework.ai.chat.model.ChatModel` 重复。

### P1-5 | Unsafe cast to DefaultListableBeanFactory
- **File:** `provider/generic/GenericOpenAiProviderRegistrar.java:53`
- **Issue:** `(DefaultListableBeanFactory) registry` 无类型检查，启动失败时错误信息不可追踪。

### P1-6 | AbstractModelCandidate.validate() throws NPE with English messages
- **File:** `AbstractModelCandidate.java:56-60`
- **Issue:** 应使用 `ClientException` + 中文消息，而非 `NullPointerException`。

### P1-7 | Mutable fields in AbstractModelCandidate return null before validate()
- **File:** `AbstractModelCandidate.java:20-34`
- **Issue:** `params()` 可返回 null（`setParams(null)` 破坏 `Map.of()` 不变量），`id()`/`provider()` 在 validate 前均为 null。

### P1-8 | ChatCapable.asChatModel() default couples SPI to concrete adapter
- **File:** `ChatCapable.java:34-36`
- **Issue:** 接口 default 方法直接 `new ChatModelAdapter(this)`，违反 DIP（抽象依赖具体类）。

### P1-9 | EmbeddingCapable.embedBatch default is O(n) sequential HTTP calls
- **File:** `EmbeddingCapable.java:14-16`
- **Issue:** 默认实现逐条调用 `embed()`，100 条文本 = 100 次串行 HTTP 请求，无性能警告。

### P1-10 | Hardcoded DashScope URL in BailianEmbeddingClient
- **File:** `client/bailian/BailianEmbeddingClient.java:55`
- **Issue:** `"https://dashscope.aliyuncs.com"` 硬编码，无法使用代理/测试端点。

### P1-11 | Duplicate rerank parsing between BailianRerankClient and GenericRerankClient
- **File:** `client/bailian/BailianRerankClient.java:79-99` vs `client/generic/GenericRerankClient.java:85-107`
- **Issue:** `parseResponse()` 逐字相同，应提取到 `AbstractRerankClient`。

### P1-12 | No logging in Generic clients on init or errors
- **File:** `client/generic/GenericChatClient.java`, `GenericEmbeddingClient.java`, `GenericRerankClient.java`
- **Issue:** Bailian 客户端有初始化日志，Generic 客户端无任何日志，影响生产排障。

### P1-13 | HttpClientErrorHandler.translate() never logs before throwing
- **File:** `client/HttpClientErrorHandler.java:44-59`
- **Issue:** HTTP 429/5xx 错误响应体（含 provider 诊断信息）不进日志。

### P1-14 | No null guard on client constructor parameters
- **File:** 所有 5 个具体 client 的构造器
- **Issue:** `apiKey` 为 null 时静默传播为 `"Authorization: Bearer null"`，产生困惑的 401 错误。

### P1-15 | RegistrySnapshot not truly immutable
- **File:** `registry/RegistrySnapshot.java:18-33`
- **Issue:** record 的 compact constructor 不做防御性拷贝，若传入 mutable map 则共享引用不安全。

### P1-16 | RegistrySnapshot.getChain() allocates on every call
- **File:** `registry/RegistrySnapshot.java:47-53`
- **Issue:** disabledSet 非空时 `stream().filter().toList()` 在每次请求的热路径上分配新 List。

### P1-17 | deepThinkingModel not validated against candidates
- **File:** `registry/LlmClientFactory.java:134-136`
- **Issue:** `defaultModel` 有校验但 `deepThinkingModel` 无校验，引用不存在的 candidate 将运行时 NPE。

### P1-18 | Collectors.toUnmodifiableMap without merge function
- **File:** `strategy/CapabilityStrategyRegistry.java:26`, `EmbeddingCapabilityStrategy.java:37`, `RerankCapabilityStrategy.java:37`, `registry/LlmClientFactory.java:72`
- **Issue:** 重复 key 时抛 `IllegalStateException` 但堆栈在 Spring 构造器注入深处，难以定位。

### P1-19 ~ P1-21 | Resilient clients DRY violations
- **File:** `resilience/ResilientChatClient.java`, `ResilientEmbeddingClient.java`, `ResilientRerankClient.java`
- **Issue:** metrics+resilience+exception 模式在 6 个方法中复制粘贴。`chatWithTools` 完整复制 `chat()`。两个 `rerank` 重载完全相同结构。
- **Fix:** 在 `AbstractResilientClient` 添加 `executeResilient()` 模板方法。

---

## P2 MEDIUM (34 issues)

<details>
<summary>Click to expand full P2 list</summary>

| # | File | Dimension | Issue |
|---|------|-----------|-------|
| 1 | GenericOpenAiProviderRegistrar.java:31 | Boundary | `environment` 字段非 final，无 null guard |
| 2 | GenericOpenAiProviderRegistrar.java:26 | SOLID | `@Configuration` 无 `@Bean` 方法，语义不清 |
| 3 | GenericOpenAiProviderRegistrar.java:44-46 | Type Safety | Raw `Map.class` binding，应用 `Bindable.mapOf()` |
| 4 | GenericOpenAiProviderRegistrar.java:66-71 | Extensibility | endpoint key 硬编码，应遍历 `LlmCapability.values()` |
| 5 | GenericOpenAiProvider.java:41-47 | Boundary | `createClient()` 无输入校验 |
| 6 | LlmAutoConfiguration.java:62-63 | Exception | 无条件抛 IllegalStateException，应用 `@ConditionalOnBean` |
| 7 | EndpointConfig.java:42-43 | Logic | dead null check（constructor 已 normalize） |
| 8 | CandidateProperties.java:11-49 | Quality | Mutable POJO 无 `toString()` |
| 9 | ModelGroup.java:22-87 | Quality | Mutable POJO 无 `toString()` |
| 10 | ProviderConfig.java:47-49 | Boundary | null apiKey 静默通过，云 provider 会运行时失败 |
| 11 | ChatModelAdapter.java:3 | Style | wildcard import |
| 12 | LlmMetrics.java:41 | Observability | tag name `type` 歧义 |
| 13 | ResilienceConfig.java:28-30 | DRY | 双重默认值位置（compact constructor + static constant） |
| 14 | ChatModelAdapter.java:78-116 | Complexity | extractChatRequest 4 层嵌套，应拆分 |
| 15 | RerankRequest.java:10-15 | Boundary | 无 compact constructor 校验 |
| 16 | ChatRequest.java:11-18 | Boundary | `input` 可为 null |
| 17 | RerankResult.java:6-13 | Boundary | `originalIndex` 可负、`score` 可 NaN |
| 18 | RerankCapable.java:14-16 | Boundary | `topN <= 0` 静默返回空列表 |
| 19 | LlmResponse.java:14-29 | Boundary | `content` 可为 null |
| 20 | ToolCallingCapable.java:22 | Quality | `List<Object> tools` 无类型安全 |
| 21 | MessageInformation.java:32 | Performance | debug-only metadata 用 LinkedHashMap |
| 22 | Timeout values hardcoded | Extensibility | 所有 client 超时值为 magic number |
| 23 | GenericEmbeddingClient.java:74 | Logic | `embedBatch` 忽略 `EmbeddingType` 参数 |
| 24 | BailianEmbeddingClient DCL | Concurrency | volatile float[] 引用安全但未注释说明 |
| 25 | GenericEmbeddingClient.java:101 | Quality | 未使用的 URL 拼接 |
| 26 | GenericChatClient dual HTTP | Design | 两个 HTTP 客户端库（java.net.http + OkHttp） |
| 27 | GenericChatClient.java:159-169 | Exception | SSE parse error 静默吞掉 |
| 28 | BailianRerankClient.java:94 | Exception | catch Exception 应改为 catch IOException |
| 29 | LlmClientFactory.java:166-170 | Exception | 异常仅 log.getMessage()，丢失 stack trace |
| 30 | LlmClientRegistry.java:71-85 | Concurrency | refresh() 丢失运行时 disabledSet |
| 31 | BailianRerankClientFactory.java:30 | Quality | DashScope URL 硬编码 |
| 32 | ChatCapabilityStrategy.java:43 | Exception | unsafe cast 无类型检查 |
| 33 | LlmClientFactory.java:71-72 | SOLID | provider map re-index 无唯一性校验 |
| 34 | RetryPolicy.java:76 | Performance | Thread.sleep 阻塞调用线程（未文档化） |

</details>

---

## P3 LOW (23 issues)

<details>
<summary>Click to expand full P3 list</summary>

| # | File | Dimension | Issue |
|---|------|-----------|-------|
| 1 | GenericOpenAiProviderRegistrar.java:39-91 | Quality | 方法 ~50 行，略超 40 行 guideline |
| 2 | GenericOpenAiProvider.java:16-48 | SOLID | 纯委托类，抽象层是否足够有价值 |
| 3 | ModelCandidate.java:21-23 | SOLID | 双层 sealed hierarchy 限制未来扩展 |
| 4 | CapabilityClient.java:30-31 | Resource | AutoCloseable no-op default 可能误导 |
| 5 | ChatRequest.java:42-49 | Quality | toDouble/toInt helper 可共享 |
| 6 | LlmCapability.java:21 | Quality | RERANKING vs Rerank* 命名不一致 |
| 7 | LlmProvider.java:24 | SOLID | config() 返回具体类型限制扩展 |
| 8 | GenericChatClient 254 lines | Quality | 接近 300 行上限 |
| 9 | Abstract base classes | Boundary | 未校验 candidate/providerId 非 null |
| 10 | API key fields | Security | 明文 String 字段，考虑 char[] 或降低可见性 |
| 11 | BailianEmbeddingClient.java:169 | Type Safety | raw Map.class 反序列化 |
| 12 | AbstractEmbeddingClient.java:43-47 | Performance | embedBatch 默认顺序调用无警告 |
| 13 | LlmClientRegistry.java:52-56 | Quality | size=0 时两条日志 |
| 14 | RegistrySnapshot.java:42 | Boundary | disabled candidate 返回误导性 "not registered" |
| 15 | GenericOpenAiProviderRegistrar.java:31 | Quality | mutable environment 字段无 null guard |
| 16 | LlmClientFactory.java:183-186 | Quality | 深层 null-check chain + magic number |
| 17 | FallbackEvent.java:24 | Quality | Throwable 在 record 中可能导致序列化问题 |
| 18 | RetryPolicy.java:110-123 | Exception | TimeoutException 未显式处理 |
| 19 | ResilientChatClient.java:81-82 | Design | circuit breaker 仅在 subscription 时检查 |
| 20 | AbstractResilientClient.java:36-37 | Boundary | isAvailable() 仅为建议性检查 |
| 21 | ResilientToolCallingChatClient.java:50-81 | SOLID | 30 行委托样板，应继承 AbstractResilientClient |
| 22 | FallbackExecutor.java:145-173 | Performance | 嵌套 operator chain O(N) 深度 |
| 23 | LlmCircuitBreakerAdapterRegistry.java:24 | Memory | adapters map 无限增长 |

</details>

---

## Positive Highlights

1. **ISP 合规优秀** — `ChatCapable`/`EmbeddingCapable`/`RerankCapable`/`ToolCallingCapable` 各 1-3 个方法，零接口超 5 方法阈值
2. **Sealed hierarchy 设计精良** — `ModelCandidate` 密封接口 + 三种具体类型支持 exhaustive pattern matching
3. **Lock-free registry** — `AtomicReference<RegistrySnapshot>` + copy-on-write 模式，读路径零锁
4. **Strategy pattern OCP** — 新增 capability 只需添加 `CapabilityStrategy` 实现类，零修改现有代码
5. **Record immutability** — 配置类（`LlmConfig`/`RetryConfig`/`CircuitBreakerProperties` 等）全用 record + compact constructor null-safe 默认值
6. **异常层级正确** — `FallbackEligibility` 正确区分用户错误（ClientException）和基础设施错误（RemoteException/IOException）
7. **无禁止模式** — 全模块零 `@Transactional`、`IllegalArgumentException`、`System.out`、`Executors.newXxx()`、`new Thread()`、JPA/Hibernate
8. **构造器注入一致** — 除 `LlmAutoConfiguration` 外全部使用构造器注入

---

## Recommended Fix Priority

### Phase 1 (P0 — must fix before merge)
1. `ProbeHandler.java` — 修复 double timeout 和 unconditional subscription
2. `HttpClientErrorHandler.java` — 消除 sneaky-throw，包装为 RemoteException
3. `GenericChatClient.java` — 统一 HTTP 客户端，修复资源泄漏
4. `LlmAutoConfiguration.java` — 改为构造器注入

### Phase 2 (P1 — should fix in same PR)
1. 所有 client 构造器添加 `Objects.requireNonNull` 校验
2. record DTO 添加 compact constructor 校验（`ChatRequest`/`RerankRequest`/`RerankResult`/`LlmResponse`）
3. `RegistrySnapshot` compact constructor 确保不可变
4. `Resilient*` 客户端提取 `executeResilient()` 模板方法消除 DRY 违反
5. 补充日志（Generic clients init、HttpClientErrorHandler、LlmClientFactory）

### Phase 3 (P2 — follow-up PR)
1. 超时配置外部化
2. `deepThinkingModel` 启动校验
3. `toUnmodifiableMap` 添加 merge function
4. 其他 P2 项按需排期
