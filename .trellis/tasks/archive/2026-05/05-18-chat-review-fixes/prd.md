# Chat 模块六维审查修复 PRD

**任务 ID**: 05-18-chat-review-fixes
**关联审查**: `docs/reviews/2026-05-18-chat-module-review.md`
**分支**: eval-rag-dev
**创建日期**: 2026-05-18

## 背景

对 chat 模块 55 个 Java 文件进行六维深度审查（资源关闭/边界条件/并发安全/性能陷阱/异常处理/内存泄漏），发现 32 个问题（4 BLOCKER + 6 HIGH + 13 MEDIUM + 9 LOW）。

按修复优先级分为 4 个 Phase，优先处理生产安全相关的 BLOCKER 和 HIGH。

---

## Phase 1: BLOCKER 修复（4 项）

### 1.1 B1 — ChatServiceImpl doStream() — getResult() NPE

**文件**: `service/impl/ChatServiceImpl.java` doStream 方法
**类别**: 边界条件
**问题**: `mapNotNull` 中 `aiResponse.getResult().getOutput().getText()` 链式调用，流结束标记时 `getResult()` 返回 null → NPE → Flux 崩溃
**修复**:
```java
.mapNotNull(aiResponse -> {
    lastAiResponse.set(aiResponse);
    Generation gen = aiResponse.getResult();
    if (gen == null || gen.getOutput() == null) {
        return null; // mapNotNull 自动跳过
    }
    String text = gen.getOutput().getText();
    if (text != null) {
        collectedContent.append(text);
    }
    return text;
})
```
**测试**: 新增 doStream 空结果测试

### 1.2 B2 — PromptLoaderServiceImpl — 多字段非原子替换

**文件**: `service/impl/PromptLoaderServiceImpl.java`
**类别**: 并发安全
**问题**: `templates` 和 `defaultTemplate` 两个 volatile 字段独立赋值，中间态不一致
**修复**: 使用不可变 holder record + 单一 volatile 原子替换
```java
private volatile PromptState state = new PromptState(Map.of(), null);

record PromptState(Map<String, PromptTemplate> templates, PromptTemplate defaultTemplate) {}

// reload 时一步替换
this.state = new PromptState(
    Collections.unmodifiableMap(newTemplates), newDefault);

// 读取时使用单一快照
PromptState snapshot = this.state;
PromptTemplate template = snapshot.templates().get(modelId);
if (template == null) template = snapshot.defaultTemplate();
```

### 1.3 B3 — RedisChatMemoryRepository — KEYS → SCAN

**文件**: `memory/RedisChatMemoryRepository.java` findConversationIds()
**类别**: 性能（生产致命）
**问题**: `redisTemplate.keys(keyPrefix + "*")` O(N) 全库扫描
**修复**: 改用 `ConversationsScanCallback` 迭代扫描
```java
@Override
public List<String> findConversationIds() {
    List<String> ids = new ArrayList<>();
    try (Cursor<String> cursor = redisTemplate.scan(
            ScanOptions.scanOptions().match(keyPrefix + "*").count(100).build())) {
        cursor.forEachRemaining(key -> {
            String id = key.substring(keyPrefix.length());
            if (!id.isEmpty()) {
                ids.add(id);
            }
        });
    }
    return ids;
}
```
注意：`StringRedisTemplate.scan()` 需要用 `RedisConnectionFactory` + `RedisConnection.scan()` 实现

### 1.4 B4 — TokenBucketLimiter — buckets 无上限

**文件**: `advisor/TokenBucketLimiter.java`
**类别**: 内存泄漏
**问题**: ConcurrentHashMap 只增不减，攻击向量
**修复**: 添加硬上限 + 超限时 warn
```java
private static final int MAX_BUCKETS = 10_000;

@Override
public boolean tryAcquire(String key) {
    if (buckets.size() >= MAX_BUCKETS) {
        log.warn("Token bucket limit reached ({}), rejecting new key: {}",
                MAX_BUCKETS, key.length() > 20 ? key.substring(0, 20) + "..." : key);
        return false;
    }
    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxTokens, refillRate));
    return bucket.tryConsume(1);
}
```

---

## Phase 2: HIGH 修复（6 项）

### 2.1 H2 — ModelRegistryRefresher — 两步非原子

**文件**: `service/ModelRegistryRefresher.java`
**修复**: 将 `modelToProvider` 作为 `replaceAll` 的一部分原子更新，或在 `ChatClientRegistry` 内部维护 providerIndex

### 2.2 H3 — TokenBucketLimiter refill() — 时钟回拨

**文件**: `advisor/TokenBucketLimiter.java` Bucket.refill()
**修复**:
```java
private void refill() {
    Instant now = Instant.now();
    if (now.isBefore(lastRefillTime)) {
        // NTP 时钟回拨，重置基准时间
        log.warn("Clock moved backwards detected, resetting refill baseline");
        lastRefillTime = now;
        return;
    }
    double elapsedSeconds = Duration.between(lastRefillTime, now).toNanos() / 1_000_000_000.0;
    long tokensToAdd = (long) (elapsedSeconds * refillRate);
    if (tokensToAdd > 0) {
        tokens = Math.min(tokens + tokensToAdd, maxTokens);
        lastRefillTime = now;
    }
}
```

### 2.3 H4 — ContentFilterAdvisor — 去重复 DFA 扫描

**文件**: `advisor/ContentFilterAdvisor.java` before()
**修复**:
```java
List<String> found = contentFilterService.findAll(userMessage);
if (!found.isEmpty()) {
    log.warn("Sensitive words detected in user input: {} word(s) found", found.size());
    throw new ContentFilteredException(BLOCKED_MESSAGE);
}
```

### 2.4 H5 — ZhipuModelProvider — 缓存 sharedApi

**文件**: `provider/ZhipuModelProvider.java`
**修复**: 构造函数中创建 `sharedApi`，`createClient()` 复用

### 2.5 H6 — MiniMaxModelProvider — 同 H5

**文件**: `provider/MiniMaxModelProvider.java`
**修复**: 同 H5

### 2.6 H7（原 RequestContext sanitize 性能）移入 P1 修复

---

## Phase 3: P1 补充（1 项）

### 3.1 H7 — RequestContext sanitize() 正则预编译

**文件**: `context/RequestContext.java`
**修复**: 将 4 个正则编译为 `private static final Pattern`

---

## Phase 4: MEDIUM 精选修复（8 项，跳过低风险项）

### 4.1 M1 — ChatUsageTracker recordUsage 异常提升
### 4.2 M3 — ChatConversationHelper savePartialResponse 补异常栈
### 4.3 M4 — PromptLoaderServiceImpl hasKey+set → setIfAbsent
### 4.4 M6 — ContentFilterAdvisor after() chatResponse null 防护
### 4.5 M8 — RedisChatMemoryRepository getBytes() 显式 UTF-8
### 4.6 M9 — DefaultUserProfileResolver userId null 防护
### 4.7 M11 — CagProperties volatile 字段
### 4.8 M12 — DeepSeekModelProvider fetchModels 补异常栈

---

## 验收标准

- [ ] Phase 1: 4 BLOCKER 全部修复，编译通过 + 新增测试全绿
- [ ] Phase 2: 6 HIGH 全部修复，编译通过 + 测试全绿
- [ ] Phase 3: 1 项修复
- [ ] Phase 4: 8 MEDIUM 修复
- [ ] 每个 Phase 完成后 git commit + push
- [ ] commit message 格式：`fix(chat): [description]`
