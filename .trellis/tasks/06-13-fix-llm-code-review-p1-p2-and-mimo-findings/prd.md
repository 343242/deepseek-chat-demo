# PRD: Fix LLM Code Review P1/P2 and Mimo Findings

## Context

两次审查发现的剩余问题需要修复：

1. **本会话独立审查** — 在 `06-13-code-review-infrastructure-llm-module-63-files/review-report.md` 已修复 5 个 P0 与绝大多数 P1 后，仍有 3 个 P1 与 6 个 P2 是新发现的（NEW-P1-*、NEW-P2-*）。
2. **Mimo 第二轮审查** — 4 个新问题：错误码语义滥用、双 HTTP 客户端、关闭顺序不确定、关闭无超时。

先前的 P0（ProbeHandler 双超时、HttpClientErrorHandler sneaky-throw、LlmAutoConfiguration 字段注入）已在 commit `467b43c` / `16c4255` 修复，本次不再涉及。

## Scope

修复 7 个 P1 + 6 个 P2 + 4 个 Mimo 发现，共 17 项。范围限定在 `src/main/java/com/smart/rag/infrastructure/llm/` 模块。

## Spec References

- `.trellis/spec/backend/code-review-checklist.md` — 维度 3（资源管理）、4（边界条件）、7（异常处理）
- `.trellis/spec/backend/quality-guidelines.md` — Forbidden Patterns、Concurrency Rules
- `.trellis/spec/backend/error-handling.md` — 三级异常体系、IErrorCode

## Fixes

### P1 — 合并前必须

**P1-1 | `AbstractResilientClient:82-83` 裸 RuntimeException**
- 维度 7（异常处理）— 违反"异常链不断 + 必须在异常体系内"。
- 修复：将 `throw new RuntimeException(e)` 改为 `throw new RemoteException(RemoteErrorCode.LLM_STREAM_ERROR, "Unexpected checked exception from LLM action", e)`。
- 原因：circuitBreaker.execute 声明 `throws Exception`，理论上 action 可抛 checked 异常；当前裸 RuntimeException 绕过 GlobalExceptionHandler。

**P1-2 | Record DTO 缺 compact constructor 校验**
- 维度 4（边界条件）— record 直构可绕过 Builder.build() 校验。
- 文件：`ChatRequest.java`、`RerankRequest.java`、`RerankResult.java`、`LlmResponse.java`。
- 修复：每个 record 添加 compact constructor：
  - `ChatRequest`：`Objects.requireNonNull(input, "input must not be null")` + `history = List.copyOf(history)` + `extraParams = Map.copyOf(extraParams)`。
  - `RerankRequest`：query、documents 非空 + `List.copyOf`。
  - `RerankResult`：`originalIndex >= 0` + `score` 必须有限（`!Double.isNaN(score) && !Double.isInfinite(score)`），否则 `ClientException`。
  - `LlmResponse`：content 非空（允许空串），`tokenUsage`/`toolCalls`/`metadata` 用 `Objects.requireNonNull` + 默认值。
- 校验失败时抛 `ClientException(ClientErrorCode.BAD_REQUEST, "<中文消息>")`。

**P1-3 | `GenericChatClient` OkHttp 实例未共享（合并 Mimo 3.1 / 3.2）**
- 维度 3（资源管理）— 每个 GenericChatClient 一个 OkHttp 实例 = N 套连接池 + N 套 dispatcher。
- 修复：
  1. 在 `HttpClientFactory` 新增 `sharedOkHttpClient(Duration connectTimeout, Duration readTimeout)` 方法，缓存单例（按 timeout 参数 key）。
  2. `GenericChatClient` 的 `callFactory` 字段类型从 `Call.Factory` 改为 `OkHttpClient`（消除 close 时的 instanceof 不确定性）。
  3. `close()` 中 OkHttp 部分改为：仅 `dispatcher.executorService().shutdown()` + `connectionPool().evictAll()`，不调用 `http.close()` 关闭 OkHttpClient（共享单例不可由使用方关闭）。
  4. 共享 OkHttpClient 的生命周期由 `HttpClientFactory` 自身管理（`@PreDestroy` 中关闭）。

### P2 — 同 PR 建议

**P2-1 | `LlmClientFactory.createRawClient:177-183` 静默吞异常返回 null**
- 维度 7 — 配置错误的 candidate 不 fail-fast。
- 修复：保持当前 log.error 行为，但额外通过 `LlmMetrics` 上报一个 `llm.client.init.failures` counter（candidateId tag），让监控可告警。

**P2-2 | `LlmClientRegistry.disable/enable/refresh` 重复 6 字段构造**
- 维度 10（DRY）。
- 修复：在 `RegistrySnapshot` 新增 `withDisabledSet(Set<String>)` 工厂方法；disable/enable/refresh 统一调用此方法。

**P2-3 | `BailianEmbeddingClient:51,232-241` DCL 改为无锁**
- 维度 5（并发优先级 1：无锁数据结构）。
- 修复：将 `volatile float[] zeroVector` + DCL 改为构造器初始化（`candidate.dimension()` 构造期已知），删除 `getZeroVector()` 方法和 synchronized 块。

**P2-4 | `GenericOpenAiProviderRegistrar:34` environment 非 final**
- 维度 5（可见性）。
- 修复：字段加 `volatile`（因 EnvironmentAware setter 由 Spring 调用，不能用 final）。

**P2-5 | `LlmClientFactory:195-198` 每 candidate 创建 ProbeHandler**
- 维度 9（KISS）— ProbeHandler 无状态。
- 修复：改为单例 Bean 注入。LlmClientFactory 构造器增加 `@Nullable ProbeHandler probeHandler` 参数。

**P2-6 | `GenericChatClient:137-139` chatStream IOException 不经 ErrorHandler**
- 维度 7 — 错误码不一致。
- 修复：`sink.error(HttpClientErrorHandler.translate("Chat Stream", url, e))`。

### Mimo 发现

**Mimo 7.1 | LLM_STREAM_ERROR 被滥用为解析失败错误码**
- 5 处：`AbstractRerankClient.java:87`、`GenericEmbeddingClient.java:134,147`、`GenericChatClient.java:253`（已是 226）、`BailianEmbeddingClient.java:217`。
- 修复：
  1. 在 `RemoteErrorCode` 新增 `LLM_RESPONSE_PARSE_ERROR(301010, "模型响应解析失败")`。
  2. 5 处 JSON 解析失败的 `RemoteException(LLM_STREAM_ERROR, ...)` 全部改为 `RemoteException(LLM_RESPONSE_PARSE_ERROR, ...)`。
  3. 保留 `LLM_STREAM_ERROR` 仅用于真正的流式传输错误（连接中断、SSE 解析异常等）。

**Mimo 3.1/3.2/3.3** — 已与 P1-3 合并处理（共享 OkHttpClient + 字段类型确定 + destroy 并行关闭）。

**Mimo 3.3 单独部分 | `LlmClientRegistry.destroy` 无超时**
- 修复：
  1. `destroy()` 改为通过 `ScopedTasks`（COLLECT_ALL 策略）并行关闭所有 client。
  2. 全局 `joinUntil(Duration.ofSeconds(30))` 超时；超时后 log.warn 并强制返回（让 JVM 自然清理残余资源）。
  3. 单 client `close()` 不加超时（保持简单，遵循 spec 第 3 节"线程池必须 shutdown"）。

## Constraints

- 遵循 `.trellis/spec/backend/`：
  - 禁止 `@Autowired` 字段注入、`IllegalArgumentException`、`System.out`、`new Thread()`、`Executors.newXxx()`
  - 使用 `ClientException`/`RemoteException` 中文消息
  - 构造器注入
- 不破坏既有公共 API（`LlmClientRegistry.get/getDefault/getChain` 等）
- 修改后 `mvn test -pl . -Dtest='CircuitBreakerTest,ResilientChatClientTest,LlmClientRegistryTest,ChatModelAdapterTest,RetryPolicyTest'` 必须全绿
- 错误码新增必须更新 `.trellis/spec/backend/error-handling.md` 中 `RemoteErrorCode` 范围表

## Out of Scope

- P3 项（命名一致性、toString 等）— 留作 follow-up
- 重构 GenericChatClient 为统一 HTTP 库（已通过共享 OkHttpClient 缓解，不重写）
- 新增 record DTO 校验测试用例（在 check.jsonl 中通过 `mvn test` 隐式覆盖）

## Verification

- `mvn compile` 全绿
- `mvn test -Dtest='*Llm*,*Circuit*,*Resilient*,*Registry*,*Chat*,*Embedding*,*Rerank*'` 全绿
- `git grep -n "LLM_STREAM_ERROR"` 仅出现在真正流式错误路径（parse 路径已切换）
- `git grep -n "throw new RuntimeException(e)" src/main/java/com/smart/rag/infrastructure/llm/` 返回 0 行
- 所有修改文件满足 spec 10 个维度（自检通过）
