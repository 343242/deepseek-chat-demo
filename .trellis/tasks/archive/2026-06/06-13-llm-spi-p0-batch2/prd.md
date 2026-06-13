# PRD: Fix LLM SPI P0 Batch 2 — Retry Semantics, Circuit Breaker, Resource Management

## 背景

代码审查第二批 P0 修复：HTTP 错误包装破坏重试、熔断器状态机竞态、资源泄漏、ToolCallingCapable 条件实现。

## 修复范围

### P0-1 + P0-9: catch-and-wrap 打破重试语义 + 错误响应体丢弃

**影响文件**（5 个客户端）:
- `src/main/java/com/smart/rag/infrastructure/llm/client/generic/GenericChatClient.java`
- `src/main/java/com/smart/rag/infrastructure/llm/client/generic/GenericEmbeddingClient.java`
- `src/main/java/com/smart/rag/infrastructure/llm/client/generic/GenericRerankClient.java`
- `src/main/java/com/smart/rag/infrastructure/llm/client/bailian/BailianEmbeddingClient.java`
- `src/main/java/com/smart/rag/infrastructure/llm/client/bailian/BailianRerankClient.java`

**现状**: 所有客户端 `catch (Exception e)` 后包装为 `RemoteException(LLM_STREAM_ERROR)`。IOException 被包装后 RetryPolicy.isRetryable() 无法识别为可重试。429/5xx 等状态码未区分。错误响应体通过 `e.getMessage()` 丢失。

**修复**: 创建 `HttpClientErrorHandler` 工具类，按异常类型分类处理：

```java
public final class HttpClientErrorHandler {

    /**
     * 处理 HTTP 客户端异常：
     * - IOException: 不包装，直接抛出（让 RetryPolicy.isRetryable() 的 instanceof IOException 生效）
     * - RestClientResponseException: 按 HTTP 状态码映射为对应的 RemoteException
     * - RemoteException: 直接重新抛出
     * - 其他 Exception: 包装为 RemoteException(LLM_STREAM_ERROR)
     */
    public static RuntimeException translate(String operation, String url, Exception e) {
        if (e instanceof RemoteException) throw (RemoteException) e;
        if (e instanceof IOException io) return sneaky(io);
        if (e instanceof RestClientResponseException rcre) {
            int status = rcre.getStatusCode().value();
            String body = rcre.getResponseBodyAsString();
            RemoteErrorCode code = switch {
                case 429 -> LLM_RATE_LIMITED;
                case >= 500 -> LLM_TRANSIENT_ERROR;
                default -> LLM_STREAM_ERROR;
            };
            throw new RemoteException(code,
                operation + " failed: HTTP " + status + " from " + url + ": " + body, rcre);
        }
        throw new RemoteException(LLM_STREAM_ERROR,
            operation + " failed: " + url + " - " + e.getMessage(), e);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(E e) throws E { throw e; }
}
```

每个客户端的 catch 块改为：
```java
catch (IOException | RemoteException e) { throw e; }
catch (Exception e) { throw HttpClientErrorHandler.translate("Chat", url, e); }
```

同时修复 SSE 流式路径（GenericChatClient chatStream()）中 IOException 被包装的问题（P1-H4）。

**注意**: 对于 RestClientResponseException 需要用 `instanceof` 检查，因为 Spring 6 的异常层次结构中 `HttpStatusCodeException` 已废弃，用 `RestClientResponseException` 作为基类。

---

### P0-2 + P0-4: 熔断器状态机修复

**文件**: `src/main/java/com/smart/rag/infrastructure/fallback/ModelCircuitBreakerRegistry.java`（内部类 ModelCircuitBreaker）

**P0-2 现状**: `recordSuccess()` 无条件 `state = CircuitBreakerState.CLOSED`，可从 OPEN 直接跳回 CLOSED。

**P0-4 现状**: `CircuitBreaker.recordProbeSuccess()` 中 `stateOf()` 和 `recordSuccess()` 是两次独立 synchronized 调用，存在 TOCTOU 竞态。

**修复**:

1. 在 `ModelCircuitBreaker` 中添加原子方法：
```java
synchronized boolean tryRecoverFromHalfOpen() {
    if (state != CircuitBreakerState.HALF_OPEN) return false;
    state = CircuitBreakerState.CLOSED;
    failureCount = 0;
    activeHalfOpenProbes = 0;
    return true;
}
```

2. `recordSuccess()` 添加状态守卫：
```java
synchronized void recordSuccess() {
    failureCount = 0;
    activeHalfOpenProbes = 0;
    if (state == CircuitBreakerState.HALF_OPEN) {
        state = CircuitBreakerState.CLOSED;
    }
}
```

3. `ModelCircuitBreakerRegistry` 中暴露 `tryRecoverFromHalfOpen(candidateId)` 方法。

4. `CircuitBreaker` 适配器的 `recordProbeSuccess()` 改为调用原子方法。

**相关文件**: `src/main/java/com/smart/rag/infrastructure/llm/resilience/CircuitBreaker.java`（适配器）

---

### P0-3: ProbeHandler.wrap() 丢弃探测结果

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/resilience/ProbeHandler.java:44-66`

**现状**: in-flight probe 失败时 `onErrorResume(e -> Mono.empty())` 吞掉错误，原始流无超时保护启动。

**修复**:
```java
if (inFlight != null) {
    return Mono.<ProbeResult>fromFuture(() -> inFlight)
        .timeout(Duration.ofMillis(probeTimeoutMs))
        .flatMap(probeResult -> {
            if (probeResult.isFailed()) {
                return Mono.error(new ProbeTimeoutException(
                    "In-flight probe failed for " + candidateId));
            }
            return Mono.empty();
        })
        .thenMany(raw)
        .timeout(Duration.ofMillis(probeTimeoutMs));
}
```

注意：需要确认 `ProbeResult` 是否有 `isFailed()` 方法，如没有需要检查现有 API 来判断探测是否失败。如果 ProbeResult 不可用此信息，则在 onProbeSuccess 回调中处理。

---

### P0-8: JDK HttpClient 从未关闭

**影响文件**（5 个客户端）:
- `GenericChatClient.java` — close() 仅处理 OkHttp
- `GenericEmbeddingClient.java` — 无 close()
- `GenericRerankClient.java` — 无 close()
- `BailianEmbeddingClient.java` — 无 close()
- `BailianRerankClient.java` — 无 close()

**修复**: 每个客户端存储 `JdkClientHttpRequestFactory` 引用，在 close() 中销毁。

对于 GenericChatClient：
```java
private final JdkClientHttpRequestFactory requestFactory;

@Override
public void close() {
    if (requestFactory != null) {
        requestFactory.destroy();
    }
    if (callFactory instanceof OkHttpClient ok) {
        ok.dispatcher().executorService().shutdown();
        ok.connectionPool().evictAll();
    }
}
```

对于其他 4 个客户端，添加 close() 方法处理各自的 `JdkClientHttpRequestFactory`。

同时，`LlmClientRegistry.destroy()` 中应该记录 close() 异常（P1-R3）：
```java
catch (Exception e) {
    log.warn("Failed to close client {}: {}", client.candidateId(), e.getMessage());
}
```

---

### P0-10: ResilientChatClient 无条件实现 ToolCallingCapable

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/resilience/ResilientChatClient.java`

**现状**: 类声明 `implements ChatCapable, ToolCallingCapable`，所有 ChatCapable 都被包装为支持工具调用。delegate 不支持时 `chatWithTools()` 抛 UnsupportedOperationException。

**修复方案**: 移除 ResilientChatClient 的 `implements ToolCallingCapable`。在 `LlmClientFactory` 中，当 delegate 支持 ToolCallingCapable 时用装饰器包装：

在 `ResilientChatClient` 中移除 `ToolCallingCapable`，`chatWithTools()` 方法保留但改为检查 delegate：
```java
public LlmResponse chatWithTools(ChatRequest request, List<Object> tools) {
    if (!(delegate instanceof ToolCallingCapable tc)) {
        throw new UnsupportedOperationException(
            candidateId() + " does not support tool calling");
    }
    // ... existing implementation ...
}
```

新增 `ResilientToolCallingChatClient` 薄装饰器：
```java
public class ResilientToolCallingChatClient implements ChatCapable, ToolCallingCapable {
    private final ResilientChatClient delegate;

    // delegate all ChatCapable methods to delegate
    // delegate chatWithTools to delegate.chatWithTools()
}
```

在 `ChatCapabilityStrategy.wrapWithResilience()` 中：
```java
ResilientChatClient resilient = new ResilientChatClient(raw, cb, retry, probe, metrics);
if (raw instanceof ToolCallingCapable) {
    return new ResilientToolCallingChatClient(resilient);
}
return resilient;
```

---

## 不在范围内

- P0-5, P0-6, P0-7 — 已在 batch 1 修复
- P0-11 — 审查误报，不存在此问题
- P1/P2 问题 → 后续批次

## 验收标准

1. IOException 在 5 个客户端中不被包装为 RemoteException，保持原始类型传播
2. RestClientResponseException 按 HTTP 状态码分类映射（429→LLM_RATE_LIMITED, 5xx→LLM_TRANSIENT_ERROR, 4xx→LLM_STREAM_ERROR）
3. 错误响应体包含在 RemoteException 的 message 中
4. ModelCircuitBreaker.recordSuccess() 不会从 OPEN 直接跳回 CLOSED
5. recordProbeSuccess() 使用原子操作，无 TOCTOU 竞态
6. ProbeHandler.wrap() 在 in-flight probe 失败时传播错误，原始流有超时保护
7. 5 个客户端的 close() 正确销毁 JdkClientHttpRequestFactory
8. LlmClientRegistry.destroy() 记录 close() 异常
9. ResilientChatClient 不再无条件实现 ToolCallingCapable
10. `mvn compile -q` 编译通过
