# LLM Unified SPI 模块代码审查报告

> 审查对象：`src/main/java/com/smart/rag/infrastructure/llm/` 全部实现代码（60 个文件，~4000 行）
>
> 审查日期：2026-06-12
>
> 参考文档：[LLM Unified SPI 重构设计文档](../design/llm-unified-spi-refactoring.md)、[LLM Unified SPI 测试文档](../design/llm-unified-spi-testing.md)
>
> 审查方法：6 个并行子代理分别审查接口契约、数据模型、配置层、注册/工厂、客户端实现、弹性层，去重合并后产出本报告
>
> 审查维度：正确性、并发安全、资源管理、异常处理、配置安全、接口设计、可观测性、与设计文档一致性

## 总体评价

LLM Unified SPI 模块架构清晰：接口层（ChatCapable/EmbeddingCapable/RerankCapable）→ 策略层 → 工厂/注册表 → 弹性包装层，分层合理。支持 OpenAI-compatible 通配和 Bailian 厂商特化两种客户端，通过 YAML 配置驱动注册，设计意图明确。

但代码中存在 **11 个 P0（必须修复）、23 个 P1（应该修复）、20+ 个 P2（建议优化）**。最严重的系统性问题是：**HTTP 错误包装逻辑破坏了重试语义**（5 个客户端全部受影响）、**熔断器状态机存在竞态条件**（可从 OPEN 直接跳回 CLOSED）、以及 **ProviderConfig.isAvailable() 比设计文档更严格导致 ollama 等无 apiKey 的 provider 被静默跳过**。

### 问题统计

| 维度 | P0 | P1 | P2 |
|------|----|----|-----|
| 正确性（编译/运行时错误） | 3 | 2 | 1 |
| 异常处理 / 重试语义 | 1 | 4 | 0 |
| 并发安全（熔断器状态机） | 3 | 0 | 1 |
| 资源管理（泄漏） | 2 | 0 | 1 |
| 配置正确性 | 2 | 4 | 3 |
| 接口设计 | 0 | 2 | 5 |
| 数据模型（不可变性/null 安全） | 0 | 5 | 4 |
| 可观测性 | 0 | 3 | 2 |
| 代码质量 | 0 | 3 | 3+ |
| **合计** | **11** | **23** | **20+** |

---

## 🔴 P0 — 必须修复

### P0-1 catch-and-wrap 打破重试语义 — 所有 HTTP 错误变为不可重试

**文件**：`GenericChatClient.java:83-87`、`GenericEmbeddingClient.java:96-100`、`GenericRerankClient.java:73-78`、`BailianEmbeddingClient.java:148-152`、`BailianRerankClient.java:68-72`

**问题**：5 个客户端全部 `catch (Exception e)` 后包装为 `RemoteException(LLM_STREAM_ERROR)`。`RetryPolicy.isRetryable()` 只重试 `IOException` 和 `RemoteException(LLM_RATE_LIMITED)`。

**影响**：
- 网络抖动（`IOException`）被包装 → **不重试**，直接触发熔断器计数
- 5xx 服务端错误（`RestClientResponseException`）→ **不重试**，直接计为熔断器失败
- 429 限流 → **不重试**（应映射为 `LLM_RATE_LIMITED`）
- 一次瞬态 503 立即触发熔断器打开，而非退避重试

**修复**：提取共享 `HttpClientErrorHandler`，按 HTTP 状态码分类处理：

```java
public static RemoteException translate(String url, Exception e) {
    if (e instanceof IOException ioe) {
        // 不包装，让 RetryPolicy.isRetryable() 的 instanceof IOException 生效
        throw new RuntimeException(ioe);
    }
    if (e instanceof RestClientResponseException rcre) {
        int status = rcre.getStatusCode().value();
        String body = rcre.getResponseBodyAsString();
        if (status == 429) {
            return new RemoteException(LLM_RATE_LIMITED, "Rate limited: " + url, rcre);
        }
        if (status >= 500) {
            return new RemoteException(LLM_TRANSIENT_ERROR, "Server error " + status + ": " + body, rcre);
        }
        return new RemoteException(LLM_STREAM_ERROR, "Client error " + status + ": " + body, rcre);
    }
    return new RemoteException(LLM_STREAM_ERROR, "Unexpected error: " + url, e);
}
```

---

### P0-2 熔断器 recordSuccess() 无状态守卫 — 可从 OPEN 直接跳回 CLOSED

**文件**：`ModelCircuitBreakerRegistry.java:49-53`（内部类 `ModelCircuitBreaker`）

**问题**：
```java
synchronized void recordSuccess() {
    failureCount = 0;
    activeHalfOpenProbes = 0;
    state = CircuitBreakerState.CLOSED;  // ← 无状态检查！
}
```

任何意外的 `recordSuccess()` 调用都会从 OPEN 直接关闭熔断器，完全绕过恢复门控。结合 P0-4（TOCTOU 竞态），并发场景下熔断器的三态模型形同虚设。

**修复**：加状态守卫：

```java
synchronized void recordSuccess() {
    if (state == CircuitBreakerState.HALF_OPEN) {
        state = CircuitBreakerState.CLOSED;
    }
    failureCount = 0;
    activeHalfOpenProbes = 0;
}
```

---

### P0-3 ProbeHandler.wrap() 丢弃探测结果，原始流无超时保护

**文件**：`ProbeHandler.java:45-52`

**问题**：当 `SharedProbeRegistry` 有 in-flight probe 时，`ProbeResult` 被 `onErrorResume(e -> Mono.empty())` 丢弃。原始流在无 `first-byte timeout` 的情况下启动。

**影响**：
- 探测失败时，原始流仍继续执行，可能无限挂起
- HALF_OPEN 状态下的并发流无超时保护
- 探测成功的信息被丢弃，无法用于决策

**修复**：等待 in-flight probe 结果后，根据结果决定是否继续：

```java
// 等待 in-flight probe 完成
return inFlightProbe.flatMap(probeResult -> {
    if (probeResult.isFailed()) {
        return Mono.error(new ProbeTimeoutException("In-flight probe failed"));
    }
    // 即使探测成功，仍用 delegate.wrapWithProbe() 保护原始流
    return delegate.wrapWithProbe(raw, probeRegistry, probeTimeoutMs);
});
```

---

### P0-4 CircuitBreaker.recordProbeSuccess() TOCTOU 竞态

**文件**：`CircuitBreaker.java:91-95`

**问题**：`stateOf()` 和 `recordSuccess()` 是两次独立的 `synchronized` 获取。中间另一线程可 `recordFailure()` 将 HALF_OPEN→OPEN，然后 `recordSuccess()` 强制 CLOSED。

**修复**：实现原子的 `recordSuccessIfHalfOpen()`：

```java
// ModelCircuitBreaker 中
synchronized boolean recordSuccessIfHalfOpen() {
    if (state != CircuitBreakerState.HALF_OPEN) {
        return false;
    }
    state = CircuitBreakerState.CLOSED;
    failureCount = 0;
    activeHalfOpenProbes = 0;
    return true;
}
```

---

### P0-5 ProviderConfig.isAvailable() 拒绝无 apiKey 的 provider（如 ollama）

**文件**：`ProviderConfig.java:19-22`

**问题**：
```java
// 实现
return url != null && !url.isBlank() && apiKey != null && !apiKey.isBlank();

// 设计文档 §10.3
return url != null && !url.isBlank() && (apiKey == null || !apiKey.isBlank());
```

设计文档明确允许 `apiKey == null`（ollama 等本地模型不需要 key）。实现更严格，**所有 ollama 候选被静默跳过**，且无日志提示。

**修复**：
```java
public boolean isAvailable() {
    return url != null && !url.isBlank()
        && (apiKey == null || !apiKey.isBlank());
}
```

---

### P0-6 无启动配置验证 — default-model 引用不存在的候选时才报错

**文件**：`LlmClientFactory.java:85-133`

**问题**：`default-model` 和 `deep-thinking-model` 引用存储为原始字符串，不验证是否匹配现有候选 ID。`buildSnapshot()` 将 `group.getDefaultModel()` 直接存入 `defaultClients` map，不检查 `clientsById` 是否包含该 key。设计文档 §10.4 明确要求启动时验证。

**影响**：错误在运行时通过 `LlmClientRegistry.getDefault()` 抛出 `RemoteException`，而非 fail-fast。

**修复**：
```java
// buildSnapshot() 中，构建完 clientsById 后
for (LlmCapability cap : LlmCapability.values()) {
    String defaultId = group.getDefaultModel();
    if (defaultId != null && !clientsById.containsKey(defaultId)) {
        throw new IllegalStateException(
            cap + ".default-model references unknown candidate: " + defaultId);
    }
    // 同样验证 deep-thinking-model
}
```

---

### P0-7 ChatRequest.fromDefaults() 整数 YAML 值触发 ClassCastException

**文件**：`ChatRequest.java:35-38`

**问题**：
```java
Double temperature = (Double) defaults.get("temperature");
Integer maxTokens = (Integer) defaults.get("maxTokens");
Double topP = (Double) defaults.get("topP");
```

Spring Boot 绑定 `temperature: 1` 为 `Integer` 而非 `Double`。`(Double)` 强转抛 `ClassCastException`。同理 `maxTokens: 100.0` 会抛。

**修复**：
```java
private static Double toDouble(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof Number n ? n.doubleValue() : null;
}

private static Integer toInt(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof Number n ? n.intValue() : null;
}
```

---

### P0-8 JDK HttpClient 从未关闭 — 线程池泄漏

**文件**：`GenericChatClient.java:241-246`（仅关闭了 OkHttpClient）、`GenericEmbeddingClient`（无 close 覆写）、`GenericRerankClient`（无 close 覆写）、`BailianEmbeddingClient`（无 close 覆写）、`BailianRerankClient`（无 close 覆写）

**问题**：`RestClient` 底层的 `JdkClientHttpRequestFactory` 持有 `HttpClient` 内部线程执行器。`close()` 未调用 `requestFactory.destroy()`。由于客户端由 `LlmClientFactory` 创建（非 Spring 管理），`@PreDestroy` 不生效。

**影响**：每次注册表重建（配置刷新）泄漏一批线程池。

**修复**：所有具体客户端存储 `JdkClientHttpRequestFactory` 引用，close() 中销毁：

```java
private JdkClientHttpRequestFactory requestFactory;

@Override
public void close() throws Exception {
    if (requestFactory != null) {
        requestFactory.destroy();
    }
}
```

---

### P0-9 Provider 错误响应体被丢弃

**文件**：所有客户端 catch 块

**问题**：
```java
catch (Exception e) {
    throw new RemoteException(LLM_STREAM_ERROR,
        "Chat call failed: " + e.getMessage(), e);  // getMessage() 不含 response body
}
```

`RestClientResponseException.getMessage()` 只含状态码和 URL，不含 provider 返回的错误详情（`model not found`、`invalid api key`、`context length exceeded` 等）。

**修复**：
```java
if (e instanceof RestClientResponseException rcre) {
    String body = rcre.getResponseBodyAsString();
    log.error("HTTP {} from {}: {}", rcre.getStatusCode(), url, body);
    throw new RemoteException(LLM_STREAM_ERROR,
        "HTTP " + rcre.getStatusCode() + ": " + body, rcre);
}
```

---

### P0-10 ResilientChatClient 无条件实现 ToolCallingCapable

**文件**：`ResilientChatClient.java:27`

**问题**：所有 `ChatCapable` 都被包装为 `ResilientChatClient`，它始终 `implements ToolCallingCapable`。`instanceof ToolCallingCapable` 永远为 true，guard 模式失效。delegate 不支持工具调用时 `chatWithTools()` 抛 `UnsupportedOperationException`。

**修复**：条件性生产子类，仅当 delegate 支持时添加 `ToolCallingCapable`：

```java
// LlmClientFactory.wrapWithResilience()
ChatCapable resilient = new ResilientChatClient(delegate, cb, retry, metrics);
if (delegate instanceof ToolCallingCapable tc) {
    return new ResilientToolCallingClient(resilient, tc, metrics);
}
return resilient;
```

---

### P0-11 EmbeddingCapabilityStrategy 构造参数数量不匹配 — 无法编译

**文件**：`EmbeddingCapabilityStrategy.java:57`

**问题**：`wrapWithResilience()` 传 5 个参数（raw, cb, retry, probe, metrics）给 `ResilientEmbeddingClient` 的 4 参数构造器（delegate, circuitBreaker, retryPolicy, metrics）。

**修复**：移除 `probe` 参数：
```java
return new ResilientEmbeddingClient((EmbeddingCapable) raw, cb, retry, metrics);
```

---

## 🟡 P1 — 应该修复

### 配置正确性

#### P1-C1 RetryConfig 多余的 @ConfigurationProperties 创建幽灵 bean

**文件**：`RetryConfig.java:13`

**问题**：`RetryConfig` 仅作为 `ResilienceConfig` 的嵌套属性使用，但 `@ConfigurationPropertiesScan("com.smart.rag")` 会将其注册为独立 bean——一个从未被注入的幽灵 bean。

**修复**：移除 `@ConfigurationProperties` 注解。

---

#### P1-C2 @EnableConfigurationProperties 与 @ConfigurationPropertiesScan 重复注册

**文件**：`LlmAutoConfiguration.java:24`

**问题**：`SmartRagApplication` 已有 `@ConfigurationPropertiesScan("com.smart.rag")` 注册 `LlmConfig`，`LlmAutoConfiguration` 又通过 `@EnableConfigurationProperties(LlmConfig.class)` 重复注册。

**修复**：选择一种机制，移除另一种。

---

#### P1-C3 CandidateProperties 无必填字段验证

**文件**：`CandidateProperties.java:8-15`

**问题**：`id`、`provider`、`model` 三个必填字段无 `@NotBlank` 或手动 null 检查。设计文档 §10.4 要求验证。

**修复**：在 `ModelGroup.toModelCandidates()` 中添加验证：
```java
if (raw.getId() == null || raw.getId().isBlank())
    throw new IllegalArgumentException("candidate.id is required");
```

---

#### P1-C4 ResilienceConfig.resolve*() 每次调用创建新默认实例

**文件**：`ResilienceConfig.java:20-35`

**问题**：`resolveRetryConfig()`、`resolveCircuitBreaker()`、`resolveProbe()` 在配置为空时每次创建新 record 实例。这些方法在 `LlmClientFactory.buildSnapshot()` 中 per-candidate 调用。

**修复**：缓存为 static 常量：
```java
private static final CircuitBreakerProperties DEFAULT_CB = new CircuitBreakerProperties(null, null, null);
```

---

### 数据模型

#### P1-D1 MessageInformation.metadata() 暴露可变 Map

**文件**：`MessageInformation.java:55`

**问题**：`MessageInformation` 声明为不可变（final class, all fields final），但 `metadata()` 返回原始可变 Map 引用。`msg.metadata().put()` 可静默破坏不可变契约。

**修复**：
```java
public Map<String, Object> metadata() {
    return metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
}
```

---

#### P1-D2 ChatRequest.Builder 存储可变 List/Map 引用

**文件**：`ChatRequest.java:48-53`

**问题**：`Builder.history(List)` 和 `extraParams(Map)` 直接存储调用方引用。调用方后续修改 list/map 会改变已构建的 record。

**修复**：
```java
public Builder history(List<ChatMessage> h) {
    this.history = h != null ? List.copyOf(h) : List.of();
    return this;
}
```

---

#### P1-D3 Builder 允许 null history

**文件**：`ChatRequest.java:49`

**问题**：`Builder.history(null)` 设置 history 为 null。其他构造路径使用 `List.of()`。`req.history().stream()` 会 NPE。

**修复**：同 P1-D2，null 时归一化为 `List.of()`。

---

#### P1-D4 AbstractModelCandidate 必填字段无 null 检查

**文件**：`AbstractModelCandidate.java:29-36`

**问题**：`id`、`provider`、`model` 存储为 plain String，无 `Objects.requireNonNull`。YAML 配置错误时 null 静默传播到 HTTP 客户端构造，NPE 远离源头。

**修复**：setter 或 `validate()` 方法中添加 requireNonNull。

---

#### P1-D5 LlmResponse 的 null/empty 歧义

**文件**：`LlmResponse.java:16-24`

**问题**：`toolCalls` 和 `responseMetadata` 可以是 null 或 empty，无文档约定。调用方必须同时检查 null 和 empty。

**修复**：compact constructor 归一化 null → `List.of()` / `Map.of()`。

---

### 注册/工厂

#### P1-R1 空注册表静默启动

**文件**：`LlmClientRegistry.java:63-65`

**问题**：`init()` 记录客户端数量但从不抛异常。0 个客户端时应用正常启动，所有 LLM 调用在运行时才报 "No default client"。

**修复**：`refresh()` 后检查 `snapshotRef.get().size() == 0`，抛 `IllegalStateException` 并给出配置指引。

---

#### P1-R2 被禁用的默认客户端产生误导性错误

**文件**：`LlmClientRegistry.java:108-111`

**问题**：默认客户端被运行时禁用后，`getDefaultClient()` 返回 null，错误信息为 "No default client"——与"未配置"无法区分。

**修复**：检查 `defaultClients.get(capability)` 是否在 `disabledSet` 中，抛出 "default client is disabled" 的明确信息。

---

#### P1-R3 destroy() 静默吞掉 close() 异常

**文件**：`LlmClientRegistry.java:72-75`

**问题**：`client.close()` 异常被空 catch 吞掉。HTTP 客户端、线程池的资源泄漏在生产日志中不可见。

**修复**：
```java
catch (Exception e) {
    log.warn("Failed to close client {}: {}", client.candidateId(), e.getMessage());
}
```

---

### 接口设计

#### P1-I1 Strategy 接口缺乏泛型，导致 unchecked cast

**文件**：`CapabilityStrategy.java:33-40`

**问题**：`createClient()` 返回 `CapabilityClient`（根类型），每个 `wrapWithResilience()` 都做 unchecked cast 到具体子类型。编译期无法捕获类型错误。

**修复**：
```java
public interface CapabilityStrategy<C extends CapabilityClient> {
    C createClient(...);
    CapabilityClient wrapWithResilience(C raw, ...);
}
```

---

#### P1-I2 chatStream 返回 Flux\<String\>，丢失 metadata

**文件**：`ChatCapable.java:28`

**问题**：`chat()` 返回丰富的 `LlmResponse`（content, tokenUsage, toolCalls, responseMetadata）。`chatStream()` 只返回 `Flux<String>`——文本片段。流式 token 使用量、tool call deltas、finish reason 全部丢失。

**影响**：需要流式元数据的调用方被迫回退到阻塞方式或自行解析 SSE。

**建议**：定义 `StreamEvent` sealed interface 或保持现状但明确文档说明限制。

---

### 客户端实现

#### P1-H1 LLM_STREAM_ERROR 用于非流式操作

**文件**：`GenericEmbeddingClient.java:99`、`GenericRerankClient.java:77`、`BailianEmbeddingClient.java:151`、`BailianRerankClient.java:70`

**问题**：`RemoteErrorCode.LLM_STREAM_ERROR`（301008，"模型流式调用失败"）用于 embedding 和 rerank 失败。这些不是流式操作。污染指标，错误分类不可靠。

**修复**：使用 `LLM_TRANSIENT_ERROR`（301009）处理网络/5xx，新建 `LLM_PROVIDER_ERROR` 处理 4xx。

---

#### P1-H2 ChatRequest.input() 未验证

**文件**：`GenericChatClient.java:170`

**问题**：`input` 为 null 时，JSON body 发送 `content:null`，上游 API 返回不透明的 400。

**修复**：
```java
if (request == null || request.input() == null || request.input().isBlank()) {
    throw new IllegalArgumentException("ChatRequest.input must not be null or blank");
}
```

---

#### P1-H3 ChatRequest.extraParams 被静默忽略

**文件**：`GenericChatClient.java:168-178`

**问题**：`ChatRequest` 定义了 `Map<String, Object> extraParams`，但 `buildRequestBody()` 从不包含它们。`response_format`、`seed`、`logprobs` 等 provider 特定参数被静默丢弃。

**修复**：
```java
if (request.extraParams() != null && !request.extraParams().isEmpty()) {
    body.putAll(request.extraParams());
}
```

---

#### P1-H4 SSE 流中 IOException 被包装，破坏重试

**文件**：`GenericChatClient.java:109-113`

**问题**：流式路径中 `IOException` 被包装为 `RemoteException(LLM_STREAM_ERROR)`。`RetryPolicy.retryStream()` 的 `isRetryable()` 返回 false。

**修复**：IOException 直接通过 `sink.error(e)` 传播。

---

#### P1-H5 RerankRequest.query() 未 null 检查

**文件**：`GenericRerankClient.java:57`、`BailianRerankClient.java:64`

**问题**：`rerank()` 检查 documents 是否为空，但不检查 query。null query 发送 `query:null`，API 返回 400。

**修复**：方法入口添加 `Objects.requireNonNull(request.query(), "query must not be null")`。

---

#### P1-H6 rerank(request, topN) 总是请求全部结果再截断

**文件**：`AbstractRerankClient.java:35-38`

**问题**：默认实现调用 `rerank(request)` 返回所有结果，再 `.limit(topN)`。对于 50 个文档只需 top 5 的场景，浪费 API 带宽和延迟。两个 rerank API 都支持服务端 `top_n`。

**修复**：在 `BailianRerankClient` 和 `GenericRerankClient` 中覆写 `rerank(request, topN)`，将 `top_n` 传给 API。

---

#### P1-H7 阻塞 chat 60s 超时，长推理模型不够

**文件**：`GenericChatClient.java:58`

**问题**：`restClient` 读超时 60s。复杂推理模型响应经常超过 60s。流式路径 120s，阻塞路径 60s，不一致。无法 per-candidate 配置。

**修复**：通过 `CandidateProperties` 或 `ModelCandidate` 使超时可配置，默认提升到 120-180s。

---

### 弹性层

#### P1-E1 RetryPolicy.isRetryable() 过于狭窄

**文件**：`RetryPolicy.java:126-134`

**问题**：`RemoteException` 是 `RuntimeException`（非 `IOException`）。`LLM_STREAM_ERROR`、`LLM_PROVIDER_UNAVAILABLE`、`MODEL_TIMEOUT` 等瞬态码全部不重试。Provider 将 HTTP 503/504 包装为 `RemoteException` 后立即失败。

**修复**：扩展重试条件：
```java
if (re.getErrorCode() == LLM_RATE_LIMITED
    || re.getErrorCode() == LLM_PROVIDER_UNAVAILABLE
    || re.getErrorCode() == MODEL_TIMEOUT) {
    return true;
}
```
或反转逻辑为白名单非重试码。

---

#### P1-E2 ResilientChatClient.chatWithTools() 不记录 token 使用量

**文件**：`ResilientChatClient.java:116`

**问题**：`chatWithTools()` 记录延迟但跳过 `metrics.recordTokens()`。对比 `chat()` 同时记录 prompt/completion tokens。Agent/工具调用——最昂贵的 LLM 操作——token 使用不可见。

**修复**：添加与 `chat()` 相同的 token 记录逻辑。

---

#### P1-E3 SSE 流错误码与非流式一致

**文件**：同 P0-1、P1-H1

**问题**：流式和非流式路径使用相同的错误包装逻辑，问题重叠。修复 P0-1 后此问题自动解决。

---

## 🔵 P2 — 建议优化

### 接口设计

| # | 文件 | 问题 | 建议 |
|---|---|---|---|
| P2-I1 | `ToolCallingCapable.java:24` | `tools` 参数 `List<Object>` 无类型安全 | 改为 `List<Map<String, Object>>` 或引入 `ToolDefinition` record |
| P2-I2 | `RerankCapable.java:15` | 默认 `rerank(request, topN)` 假设结果已按 score 排序，但接口未约定 | 文档化约定，或默认实现排序后再截断 |
| P2-I3 | `CapabilityClient.java:32` | `close()` 默认空操作，资源泄漏风险 | 改为 abstract，强制实现 |
| P2-I4 | `CapabilityClient.java:30` | `capability()` 运行时标签可与编译时类型不一致（YAML 配错） | `LlmClientFactory.buildSnapshot()` 中验证 enum 与实际接口类型匹配 |
| P2-I5 | `RerankRequest.java:3-8` | record 组件无 null/empty 验证 | compact constructor 中添加 `Objects.requireNonNull` |

### 数据模型

| # | 文件 | 问题 | 建议 |
|---|---|---|---|
| P2-D1 | `AbstractModelCandidate` | 缺少 `equals`/`hashCode`/`toString` | 基于 `id` 实现，添加 `toString` 显示 id/model/provider |
| P2-D2 | `RerankRequest.java:9` | `documents` 无防御性拷贝 | compact constructor: `documents = List.copyOf(documents)` |
| P2-D3 | `AbstractModelCandidate:32` | `params()` 暴露可变 Map | 返回 `Collections.unmodifiableMap(params)` |
| P2-D4 | `ChatRequest.java:24-30` | `of()`/`withSystem()` 绕过 Builder，null 安全路径不一致 | 添加 compact constructor 归一化 null |

### 注册/工厂

| # | 文件 | 问题 | 建议 |
|---|---|---|---|
| P2-R1 | `ChatCapabilityStrategy` | 无 `ProviderClientFactory` 委派（Embedding/Rerank 有），违反 OCP | 添加与 Embedding/Rerank 一致的 factory 发现机制 |
| P2-R2 | `EmbeddingCapabilityStrategy:57` + `LlmClientFactory:147-150` | 为 embedding 客户端创建无用的 ProbeHandler | 在工厂层按 capability 门控 probe 构建 |
| P2-R3 | 3 个 Strategy `wrapWithResilience` | unchecked cast 无验证，错误类型会炸掉整个快照重建 | 使用 `instanceof` pattern matching，per-candidate 错误处理 |
| P2-R4 | `LlmClientFactory.java:103` | 无重复候选 ID 检测，不同 model group 同 id 静默覆盖 | `put()` 后检查返回值，WARN 日志 |
| P2-R5 | `RegistrySnapshot.java:53-58` | `getChain()` 每次调用 stream().filter().toList()（disabledSet 非空时） | 构造时缓存过滤后的 chain |
| P2-R6 | `LlmClientRegistry.java:130-134` | CAS lambda 内 `log.info()` 在失败重试时产生重复日志 | 移到 lambda 外 |

### 客户端实现

| # | 文件 | 问题 | 建议 |
|---|---|---|---|
| P2-H1 | `GenericChatClient.java:130-137` | SSE 流中 JSON 解析错误被静默吞掉 | 添加 `log.warn("Failed to parse SSE data: {}", data, e)` |
| P2-H2 | `BailianEmbeddingClient.java:170-178` | 共享可变零向量按引用返回 | `Arrays.copyOf(zeroVector, zeroVector.length)` |
| P2-H3 | 所有 5 个具体客户端 | 每个实例创建新 `ObjectMapper`（反射开销大） | 使用共享 static final 或注入 Spring bean |
| P2-H4 | `GenericRerankClient.java:89` vs `BailianRerankClient.java:82` | parse 方法 catch 范围不一致（IOException vs Exception） | 统一为 IOException |

### 弹性层

| # | 文件 | 问题 | 建议 |
|---|---|---|---|
| P2-E1 | `CircuitBreaker.java:70-71` | success 无条件重置 failureCount，间歇性故障模型（80% 失败率）永远不触发熔断 | 仅在 HALF_OPEN→CLOSED 时重置，或实现滑动窗口失败率 |
| P2-E2 | `ChatModelAdapter.java:86-107` | 仅含 SystemMessage（无 UserMessage）时产生 null input | 添加 guard |

### 配置层

| # | 文件 | 问题 | 建议 |
|---|---|---|---|
| P2-C1 | `LlmConfig.java:33-35` | `getCapabilityGroup()` 冗余 null 检查（compact constructor 已保证非 null） | 移除死代码 |
| P2-C2 | `EndpointConfig.java:27-30` | `get()` 冗余 null 检查 | 移除死代码 |
| P2-C3 | `RetryConfig.java:15-18` | 数值配置无上限（maxAttempts 可设 1000+） | 添加合理上限 |
| P2-C4 | `LlmConfig.java:12-13` | Javadoc 引用不存在的 `ModelCandidateConverter` | 更新为 `CandidateProperties` + `ModelGroup.toModelCandidates()` |
| P2-C5 | `GenericOpenAiProviderRegistrar.java:31` | `@Configuration` 在 `BeanDefinitionRegistryPostProcessor` 上导致不必要的 CGLIB 代理 | 改为 `@Component` |
| P2-C6 | `GenericOpenAiProviderRegistrar.java:47-49` | `Binder.bind(prefix, Map.class)` 丢失泛型 | 改为 `Map<String, Object>` |

### 其他

| # | 文件 | 问题 | 建议 |
|---|---|---|---|
| P2-M1 | `ChatModelAdapter.java:5-6,10-11` | 重复 import | 删除 |
| P2-M2 | `AbstractResilientClient.java:35-37` | `isAvailable()` 有副作用（触发 OPEN→HALF_OPEN 转换） | Javadoc 文档化 |
| P2-M3 | `FallbackEvent.java:19` | `cause` 类型为 `Throwable`，破坏 JSON 序列化 | 改为 `String errorMessage` |

---

## 修复优先级建议

### 第一批（阻塞性 / 必须立即修复）
1. **P0-11** — 构造参数不匹配，模块无法编译
2. **P0-5** — ollama 等无 apiKey provider 被静默跳过
3. **P0-7** — 整数 YAML 值触发 ClassCastException

### 第二批（生产可靠性）
4. **P0-1** — catch-and-wrap 打破重试语义（影响全部 5 个客户端）
5. **P0-2/3/4** — 熔断器状态机竞态（3 个相关问题一起修）
6. **P0-6** — 启动配置验证
7. **P0-8** — HttpClient 泄漏
8. **P0-9** — 错误响应体丢失
9. **P0-10** — ToolCallingCapable 无条件实现

### 第三批（可观测性 + 正确性）
10. **P1-E2** — chatWithTools() token 指标缺失
11. **P1-H1** — 错误码分类（STREAM_ERROR 用于非流式）
12. **P1-R3** — destroy() 异常吞掉
13. **P1-D1~D5** — 数据模型不可变性/nul 安全
14. **P1-C1~C4** — 配置层清理

### 第四批（设计优化 + P2）
15. **P1-I1/I2** — Strategy 泛型、StreamEvent
16. **P1-H2~H7** — 客户端验证和配置
17. **P2** — 按需修复，建议在 P0/P1 完成后批量处理
