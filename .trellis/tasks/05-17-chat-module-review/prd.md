# PRD — Chat 模块 Code Review 修复

> 来源：对 chat 模块 22 个核心文件的 code review（3 CRITICAL / 6 SUGGESTION）

## 背景

对 chat 模块进行全面 code review，涵盖：ChatController、ChatServiceImpl、ModelRouter、Advisor 链（RateLimit/ContentFilter/ConversationContext）、TokenBucketLimiter、FallbackChainResolver、StreamRetryHandler、SandboxService、ToolRegistry、RedisChatMemoryRepository 等核心文件。

整体架构优秀，职责划分清晰，设计模式运用得当。发现 3 个必须修复的线程安全和安全问题，6 个改进建议。

## 审查范围

| 包路径 | 文件数 | 说明 |
|--------|--------|------|
| `chat/controller/` | 1 | ChatController |
| `chat/service/` | 6 | ChatServiceImpl, ChatService, ChatRequestSpecFactory, ChatAdvisorChainFactory, ChatUsageTracker, ChatConversationHelper |
| `chat/provider/` | 2 | ModelRouter, DeepSeekModelProvider |
| `chat/client/` | 1 | ChatClientRegistry |
| `chat/advisor/` | 4 | RateLimitAdvisor, ContentFilterAdvisor, ConversationContextAdvisor, TokenBucketLimiter |
| `chat/fallback/` | 5 | FallbackChainResolver, StreamRetryHandler, FallbackEligibility, ChatFallbackProperties, FallbackAutoConfiguration |
| `chat/memory/` | 1 | RedisChatMemoryRepository |
| `chat/tool/` | 2 | ToolRegistry, SandboxService |
| `chat/dto/` | 3 | ChatRequest, ChatResponse, FallbackMeta |

## 修复项

### Phase 1: CRITICAL 修复（3 项）

| ID | 文件 | 行号 | 问题 | 修复方案 |
|----|------|------|------|----------|
| C1 | `TokenBucketLimiter.java` | 102, 116, 124 | `synchronized` 在虚拟线程下会导致 Pinned Thread，高并发可能耗尽平台线程池 | 改用 `ReentrantLock`，见下方代码 |
| C2 | `ChatController.java` | 50-58 | `chatStreamGet` 手动构造 ChatRequest，绕过 `@Valid` 校验 | 废弃 GET 接口或添加手动校验 |
| C3 | `ChatServiceImpl.java` | 194 | `StringBuilder` 在 Flux 异步流中非线程安全 | 改用 `StringBuffer` |

### Phase 2: SUGGESTION 修复（6 项）

| ID | 文件 | 行号 | 问题 | 修复方案 |
|----|------|------|------|----------|
| S1 | `RateLimitAdvisor.java` | 52 | 错误消息泄露 `conversationId` 内部标识符 | 移除 conversationId，只保留友好提示 |
| S2 | `SandboxService.java` | 73 | `e.getMessage()` 可能包含系统临时文件路径 | 生产环境返回通用错误消息，详细错误只记日志 |
| S3 | `ChatConversationHelper.java` | 70-72 | `DuplicateKeyException` 捕获可能破坏外层事务 | 确认调用链是否在事务内，必要时调整 |
| S4 | `FallbackChainResolver.java` | 71 | 环检测依赖 Set 去重，配置错误时静默跳过无告警 | 检测到重复时记录 WARN 日志 |
| S5 | `ContentFilterAdvisor.java` | 76 | 模型输出被替换后用户无感知 | 在响应 metadata 中添加 `contentFiltered: true` 标记 |
| S6 | `RedisChatMemoryRepository.java` | - | 反序列化 Message 对象时未限制未知属性 | 启用 `FAIL_ON_UNKNOWN_PROPERTIES = true` |

## 详细修复方案

### C1: TokenBucketLimiter — ReentrantLock 改造

**当前代码（有问题）：**
```java
private static class Bucket {
    synchronized boolean tryConsume(long requested) {
        refill();
        lastAccessed = Instant.now();
        if (tokens < requested) return false;
        tokens -= requested;
        return true;
    }

    synchronized long availableTokens() {
        refill();
        return tokens;
    }

    synchronized boolean isIdle(Instant threshold) {
        return lastAccessed.isBefore(threshold);
    }
}
```

**修复后：**
```java
private static class Bucket {
    private final ReentrantLock lock = new ReentrantLock();

    boolean tryConsume(long requested) {
        lock.lock();
        try {
            refill();
            lastAccessed = Instant.now();
            if (tokens < requested) return false;
            tokens -= requested;
            return true;
        } finally {
            lock.unlock();
        }
    }

    long availableTokens() {
        lock.lock();
        try {
            refill();
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    boolean isIdle(Instant threshold) {
        lock.lock();
        try {
            return lastAccessed.isBefore(threshold);
        } finally {
            lock.unlock();
        }
    }
}
```

**验证方式：** 编译通过 + 单元测试（并发场景）

---

### C2: ChatController — GET 流式接口校验

**方案 A（推荐）：废弃 GET 接口**
```java
// 删除 chatStreamGet 方法，前端统一使用 POST /api/chat/stream
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatStreamPost(@Valid @RequestBody ChatRequest request) {
    return chatService.chatStream(request);
}
```

**方案 B：保留 GET 但添加手动校验**
```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatStreamGet(
        @RequestParam @NotBlank String model,
        @RequestParam @NotBlank String message,
        @RequestParam(required = false) String conversationId,
        @RequestParam(defaultValue = "SIMPLE") String mode,
        @RequestParam(defaultValue = "false") boolean ragEnabled,
        @RequestParam(defaultValue = "false") boolean enableThinking) {
    
    // 手动校验 mode 枚举值
    if (!ChatMode.isValid(mode)) {
        throw new BusinessException("无效的聊天模式: " + mode);
    }
    
    ChatRequest request = new ChatRequest(model, message, conversationId, ragEnabled, mode, enableThinking, null);
    return chatService.chatStream(request);
}
```

---

### C3: ChatServiceImpl — 线程安全的内容收集

**当前代码：**
```java
StringBuilder collectedContent = new StringBuilder();
// ...
.mapNotNull(aiResponse -> {
    collectedContent.append(text);  // 非线程安全
    return text;
})
```

**修复后：**
```java
StringBuffer collectedContent = new StringBuffer();
// ...
.mapNotNull(aiResponse -> {
    collectedContent.append(text);  // 线程安全
    return text;
})
```

---

### S1: RateLimitAdvisor — 错误消息脱敏

**修复后：**
```java
if (!rateLimiter.tryAcquire(conversationId)) {
    log.warn("Rate limit exceeded for conversation: {}", conversationId);
    throw new RateLimitExceededException("请求过于频繁，请稍后再试");
    // 移除 "当前对话: " + conversationId
}
```

---

### S2: SandboxService — 错误消息脱敏

**修复后：**
```java
} catch (IOException e) {
    log.error("Failed to create temp file for sandbox", e);
    return new SandboxResult(-1, "", "沙箱初始化失败，请稍后重试", false, 0);
    // 详细错误只记日志，不返回给用户
}
```

---

### S4: FallbackChainResolver — 环检测告警

**修复后：**
```java
for (String candidate : chain) {
    if (seen.contains(candidate)) {
        log.warn("Circular fallback detected for model '{}': '{}' already in chain, skipping",
                currentModel, candidate);
        continue;
    }
    if (seen.add(candidate)) {
        toExpand.add(candidate);
        if (seen.size() >= MAX_CHAIN_SIZE) {
            break;
        }
    }
}
```

---

### S5: ContentFilterAdvisor — 过滤标记

**修复后：**
```java
if (content != null && contentFilterService.containsSensitiveContent(content)) {
    String filtered = contentFilterService.replace(content);
    log.info("Filtered sensitive words in model output");
    AssistantMessage newMessage = new AssistantMessage(filtered);
    ChatGenerationMetadata metadata = generation.getMetadata();
    
    // 添加过滤标记到 metadata
    Map<String, Object> filteredMetadata = new HashMap<>(metadata.getMap());
    filteredMetadata.put("contentFiltered", true);
    ChatGenerationMetadata newMetadata = ChatGenerationMetadata.from(
            metadata.getFinishReason(), filteredMetadata);
    
    filteredGenerations.add(new Generation(newMessage, newMetadata));
}
```

---

### S6: RedisChatMemoryRepository — 反序列化安全

**修复后：**
```java
private ObjectMapper createSecureObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    mapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    return mapper;
}
```

## 约束

- 每个 Phase 独立 commit
- 编译通过后才能提交
- 不 push，用户手动 push
- 优先修复 C1（线程安全），其次 C2、C3

## 验证清单

- [ ] C1: TokenBucketLimiter 改用 ReentrantLock，编译通过
- [ ] C2: GET 流式接口校验修复或废弃
- [ ] C3: StringBuilder → StringBuffer
- [ ] S1-S6: 按需修复
- [ ] 所有修改通过 `mvn clean compile`
- [ ] 相关单元测试通过（如有）
