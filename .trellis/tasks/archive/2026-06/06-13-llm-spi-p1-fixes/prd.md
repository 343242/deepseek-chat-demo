# PRD: Fix LLM SPI P1 — Data Model, Retry, Metrics, Config Cleanup

## 背景

P0 全部修复完成后，修复审查报告中确认的 P1 问题。聚焦数据模型不可变性、重试策略扩展、指标记录、配置层清理。

## 修复范围

### P1-E2: ResilientChatClient.chatWithTools() 不记录 token 使用量

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/resilience/ResilientChatClient.java`

**现状**: `chat()` 方法记录 prompt/completion tokens，`chatWithTools()` 仅记录延迟。

**修复**: 在 `chatWithTools()` 的 metrics 块中添加与 `chat()` 相同的 token 记录：
```java
if (metrics != null) {
    metrics.recordChatLatency(candidateId(), start, "success");
    if (response.tokenUsage() != null) {
        metrics.recordTokens(candidateId(), "prompt", response.tokenUsage().promptTokens());
        metrics.recordTokens(candidateId(), "completion", response.tokenUsage().completionTokens());
    }
}
```

---

### P1-E1: RetryPolicy.isRetryable() 过于狭窄

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/resilience/RetryPolicy.java`

**现状**: 仅重试 IOException、ProbeTimeoutException、LLM_RATE_LIMITED。HTTP 5xx 映射为 LLM_TRANSIENT_ERROR 后不重试。

**修复**: 扩展 RemoteException 可重试条件，加入瞬态错误码：
```java
if (e instanceof RemoteException re) {
    return re.getErrorCode() == RemoteErrorCode.LLM_RATE_LIMITED
        || re.getErrorCode() == RemoteErrorCode.LLM_TRANSIENT_ERROR;
}
```

---

### P1-D1: MessageInformation.metadata() 暴露可变 Map

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/MessageInformation.java`

**修复**: 返回不可变视图：
```java
public Map<String, Object> metadata() {
    return metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
}
```
构造器中防御性拷贝：
```java
this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
```

---

### P1-D2/D3: ChatRequest.Builder 存储可变引用 / 允许 null history

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/ChatRequest.java`

**修复**:
```java
public Builder history(List<MessageInformation> h) {
    this.history = h != null ? List.copyOf(h) : List.of();
    return this;
}

public Builder extraParams(Map<String, Object> ep) {
    this.extraParams = ep != null ? Map.copyOf(ep) : Map.of();
    return this;
}
```

---

### P1-D4: AbstractModelCandidate 必填字段无 null 检查

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/AbstractModelCandidate.java`

**修复**: 在 setter 或 validate() 中添加：
```java
public void validate() {
    Objects.requireNonNull(id, "candidate id is required");
    Objects.requireNonNull(provider, "candidate provider is required");
    Objects.requireNonNull(model, "candidate model is required");
}
```
在 `ModelGroup.toModelCandidates()` 转换后调用 `validate()`。

---

### P1-D5: LlmResponse 的 null/empty 歧义

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/LlmResponse.java`

**修复**: compact constructor 归一化：
```java
public LlmResponse {
    if (toolCalls == null) toolCalls = List.of();
    if (responseMetadata == null) responseMetadata = Map.of();
}
```

---

### P1-R1: 空注册表静默启动

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/registry/LlmClientRegistry.java`

**修复**: init() 中 refresh 后检查：
```java
@PostConstruct
public void init() {
    refresh();
    int size = snapshotRef.get().size();
    if (size == 0) {
        log.warn("LlmClientRegistry initialized with 0 clients — check app.llm configuration");
    }
    log.info("LlmClientRegistry initialized: {} clients registered", size);
}
```
注意：不抛异常（开发环境可能不配置 LLM），但 warn 级别日志可检测问题。

---

### P1-H1: LLM_STREAM_ERROR 用于非流式操作

**文件**: `GenericEmbeddingClient.java`, `GenericRerankClient.java`, `BailianEmbeddingClient.java`, `BailianRerankClient.java`

**现状**: 所有 embedding/rerank 客户端使用 `LLM_STREAM_ERROR`。

**修复**: 这些客户端已改为使用 `HttpClientErrorHandler`（P0-1 修复），其中 429 → LLM_RATE_LIMITED, 5xx → LLM_TRANSIENT_ERROR。但解析失败（parseResponse catch）仍使用 `LLM_STREAM_ERROR`，应改为更合适的错误码。检查是否有 `LLM_CALL_ERROR` 或类似通用错误码可用，如没有则保留 STREAM_ERROR 但添加注释说明。

---

### P1-C1: RetryConfig 多余的 @ConfigurationProperties

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/config/RetryConfig.java`

**修复**: 移除 `@ConfigurationProperties` 注解（它是 ResilienceConfig 的嵌套属性，不需要独立注册）。

---

### P1-C2: @EnableConfigurationProperties 与 @ConfigurationPropertiesScan 重复

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/config/LlmAutoConfiguration.java`

**修复**: 移除 `@EnableConfigurationProperties(LlmConfig.class)`，因为主应用已有 `@ConfigurationPropertiesScan("com.smart.rag")`。

---

### P1-C4: ResilienceConfig.resolve*() 每次调用创建新默认实例

**文件**: `src/main/java/com/smart/rag/infrastructure/llm/config/ResilienceConfig.java`

**修复**: 缓存默认实例为 static 常量：
```java
private static final RetryConfig DEFAULT_RETRY = new RetryConfig(null, null, null, null);
private static final CircuitBreakerProperties DEFAULT_CB = new CircuitBreakerProperties(null, null, null);
private static final ProbeProperties DEFAULT_PROBE = new ProbeProperties(null, null);
```

---

## 不在范围内

- P0 全部 — 已在 batch 1/2 修复
- P2 问题 — 按需后续处理
- P1-I1（Strategy 泛型） — 涉及接口重构，风险较高，单独处理
- P1-I2（chatStream 返回 Flux<String>） — 设计决策，非 bug
- P1-H2~H7 — 输入验证和功能增强，优先级低于上述修复

## 验收标准

1. chatWithTools() 记录 prompt/completion token 使用量
2. RetryPolicy 重试 LLM_TRANSIENT_ERROR（HTTP 5xx）
3. MessageInformation.metadata() 返回不可变 Map
4. ChatRequest.Builder 防御性拷贝 history 和 extraParams
5. AbstractModelCandidate 必填字段 null 检查
6. LlmResponse compact constructor 归一化 null → empty
7. 空注册表启动时 warn 日志
8. RetryConfig 无 @ConfigurationProperties 注解
9. LlmAutoConfiguration 无 @EnableConfigurationProperties
10. ResilienceConfig 使用缓存默认实例
11. `mvn compile -q` 编译通过
