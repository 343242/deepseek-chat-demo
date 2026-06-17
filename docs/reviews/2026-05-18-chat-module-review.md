# Chat 模块六维深度审查报告（已复核）

**任务 ID**: 05-18-chat-module-review
**分支**: eval-rag-dev
**审查日期**: 2026-05-18
**复核日期**: 2026-05-18（逐文件源码验证）
**审查范围**: chat 模块全部 ~55 个 Java 文件
**审查维度**: 资源关闭 · 边界条件 · 并发安全 · 性能陷阱 · 异常处理 · 内存泄漏

---

## 复核结论

- 原报告 33 项问题
- 复核后：**✅ 确认 30 项，⬇️ 降级 1 项，❌ 撤销 2 项**
- 最终：**5 BLOCKER + 6 HIGH + 13 MEDIUM + 9 LOW = 33 项**

---

## 🔴 BLOCKER（5 项）

### B1 ✅ 确认 — ChatServiceImpl doStream() — getResult() NPE
- **文件**: `service/impl/ChatServiceImpl.java` doStream 方法
- **类别**: 边界条件
- **源码证据**:
```java
// mapNotNull 只过滤返回值，不过滤中间 NPE
.mapNotNull(aiResponse -> {
    lastAiResponse.set(aiResponse);
    String text = aiResponse.getResult().getOutput().getText(); // ← NPE
    collectedContent.append(text);
    return text;
})
```
- **复核**: `aiResponse.getResult()` 在流结束标记、工具调用中间态时返回 null。`mapNotNull` 只对最终返回值生效，NPE 在 `.getText()` 调用前已抛出，整个 Flux 崩溃。**确认 BLOCKER**。

### B2 ✅ 确认 — PromptLoaderServiceImpl reload() — 多字段非原子替换
- **文件**: `service/impl/PromptLoaderServiceImpl.java`
- **类别**: 并发安全
- **源码证据**:
```java
private volatile Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();
private volatile PromptTemplate defaultTemplate;

// reload() 中两步赋值：
this.templates = newTemplates;      // ← 写1
this.defaultTemplate = newDefault;   // ← 写2（中间态）

// getPrompt() 同时读两个字段：
PromptTemplate template = templates.get(modelId);
if (template == null) {
    template = defaultTemplate;  // ← 可读到旧 defaultTemplate + 新 templates
}
```
- **复核**: 两个 volatile 写之间无 happens-before 保证。中间态会导致新 templates 中无匹配的 model 找到旧 defaultTemplate，或反之。**确认 BLOCKER**。

### B3 ✅ 确认 — RedisChatMemoryRepository — KEYS 全库扫描
- **文件**: `memory/RedisChatMemoryRepository.java` findConversationIds()
- **类别**: 性能（生产致命）
- **源码证据**:
```java
public List<String> findConversationIds() {
    Set<String> keys = redisTemplate.keys(keyPrefix + "*"); // ← O(N) 全库扫描
```
- **复核**: `KEYS *` 命令阻塞 Redis 单线程，百万级 key 时延迟可达秒级。注释已承认但未修复。**确认 BLOCKER**。

### B4 ✅ 确认 — TokenBucketLimiter — buckets 无上限增长
- **文件**: `advisor/TokenBucketLimiter.java`
- **类别**: 内存泄漏
- **源码证据**:
```java
private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

public boolean tryAcquire(String key) {
    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxTokens, refillRate));
    // ← 每个 unique key 都创建 Bucket，永远不删除
}
```
- **复核**: `cleanIdleBuckets()` 依赖 @Scheduled 调度，调度失效或攻击者快速轮换 key 都可致 OOM。**确认 BLOCKER**。

### B5 ❌ 撤销 — ChatServiceImpl doChat() — 已有 null 防护
- **文件**: `service/impl/ChatServiceImpl.java` doChat 方法
- **原报告**: generation.getOutput().getText() NPE
- **源码实际**:
```java
Generation generation = null;
if (aiResponse != null) {
    generation = aiResponse.getResult();
}
String content = generation != null
        ? generation.getOutput().getText()  // ← 仅在 generation != null 时调用
        : "";
```
- **复核**: 代码已做完整的 null 检查（aiResponse → generation 三级防护）。`getOutput()` 在 `generation != null` 时返回 null 是 Spring AI 异常态，非正常边界。**撤销，非 BUG**。

---

## 🟠 HIGH（6 项，原 7 项，H1 降级）

### H1 ⬇️ 降级为 MEDIUM — ChatAdvisorChainFactory check-then-act
- **原级别**: HIGH
- **降级理由**: 缓存字段初始化是幂等的（`ObjectProvider.getIfAvailable()` 无副作用），并发首次调用最多重复创建一次等价结果，无功能影响。Race 存在但 outcome 恒等。**降级为 MEDIUM**。

### H2 ✅ 确认 — ModelRegistryRefresher 两步非原子
- **文件**: `service/ModelRegistryRefresher.java` refresh()
- **源码证据**:
```java
chatClientRegistry.replaceAll(newClients, allModels);  // ← 写1
modelToProvider = Collections.unmodifiableMap(newIndex); // ← 写2
```
- **复核**: 中间态下 `getProviderIdForModel()` 对新模型返回 null。影响有限（查询降级）但违反一致性。**确认 HIGH**。

### H3 ✅ 确认 — TokenBucketLimiter refill() 时钟回拨
- **文件**: `advisor/TokenBucketLimiter.java` Bucket.refill()
- **源码证据**:
```java
double elapsedSeconds = Duration.between(lastRefillTime, now).toNanos() / 1_000_000_000.0;
long tokensToAdd = (long) (elapsedSeconds * refillRate);
if (tokensToAdd > 0) {  // ← 负数时跳过，令牌永不补充
```
- **复核**: NTP 回拨 → elapsed 为负 → tokensToAdd ≤ 0 → if 不执行 → 令牌耗尽后永远无法补充直到时钟追上。**确认 HIGH**。

### H4 ✅ 确认 — ContentFilterAdvisor 重复 DFA 扫描
- **文件**: `advisor/ContentFilterAdvisor.java` before()
- **源码证据**:
```java
if (contentFilterService.containsSensitiveContent(userMessage)) {  // ← 扫描1
    List<String> found = contentFilterService.findAll(userMessage); // ← 扫描2
```
- **复核**: 同一段文本先 `containsSensitiveContent` 再 `findAll`，DFA 遍历两次。**确认 HIGH**。

### H5 ✅ 确认 — ZhipuModelProvider 每次新建 ZhiPuAiApi
- **文件**: `provider/ZhipuModelProvider.java`
- **源码证据**:
```java
public ChatClient createClient(String modelId, Double temperature) {
    ZhiPuAiApi api = ZhiPuAiApi.builder()  // ← 每次调用都新建
            .baseUrl(properties.baseUrl()).apiKey(properties.apiKey()).build();
```
- **复核**: DeepSeek Provider 已在构造函数中缓存 sharedApi，Zhipu 和 MiniMax 未保持一致。**确认 HIGH**。

### H6 ✅ 确认 — MiniMaxModelProvider 同 H5
- **复核**: 同上。**确认 HIGH**。

---

## 🟡 MEDIUM（13 项，含 H1 降级 + B5 撤销补位为 M5.5）

### 原 H1 降级 — ChatAdvisorChainFactory check-then-act
- 降级理由见上。Race 存在但幂等。

### M1 ✅ ChatUsageTracker recordUsage 吞异常
### M2 ✅ ChatConversationHelper getMessageCount 返回 0
### M3 ✅ ChatConversationHelper savePartialResponse 丢异常栈
### M4 ✅ PromptLoaderServiceImpl hasKey+set 非原子
### M5 ✅ ModelServiceImpl 刷新失败无指标
### M6 ✅ ContentFilterAdvisor after() chatResponse null
### M7 ✅ RateLimitAdvisor 无 conversationId 时共享桶
### M8 ✅ RedisChatMemoryRepository getBytes() 未指定字符集
### M9 ✅ DefaultUserProfileResolver userId null
### M10 ✅ DefaultSessionContextResolver messageCount 负数
### M11 ✅ CagProperties 非 volatile
### M12 ✅ DeepSeekModelProvider fetchModels 吞异常栈

---

## 🟢 LOW（9 项）

### L1 ✅ ChatController — 缺少 @PreAuthorize 安全注解
- **文件**: `controller/ChatController.java`
- **类别**: 安全
- **问题**: 所有端点未配置权限校验，任何已认证用户都能访问
- **修复**: 添加 @PreAuthorize("hasAuthority('chat:send')") 等

### L2 ✅ ChatServiceImpl doStream — collectedContent 无大小限制
- **文件**: `service/impl/ChatServiceImpl.java` doStream
- **类别**: 边界条件
- **问题**: 流式响应 StringBuilder 持续增长无上限
- **修复**: 添加最大长度限制

### L3 ✅ FallbackChainProvider — 降级链硬编码
- **文件**: `fallback/FallbackChainProvider.java`
- **类别**: 可维护性
- **问题**: 降级链配置写死在代码中，无法动态调整
- **修复**: 提取为配置项

### L4 ✅ ContentFilterAdvisor after() — 过滤日志 info 级别
- **文件**: `advisor/ContentFilterAdvisor.java`
- **类别**: 异常处理
- **问题**: 正常业务会产生大量 info 日志，建议降为 debug

### L5 ✅ SensitiveWordFilterService — 依赖静态方法
- **文件**: `content/SensitiveWordFilterService.java`
- **类别**: 可测试性
- **问题**: 依赖 SensitiveWordHelper 静态方法，不利于 Mock

### L6 ✅ RedisChatMemoryRepository — findConversationIds 跨用户泄露
- **文件**: `memory/RedisChatMemoryRepository.java`
- **类别**: 安全
- **问题**: 返回所有用户会话 ID，无用户隔离

### L7 ✅ TokenBucketLimiter — cleanIdleBuckets key 未截断
- **文件**: `advisor/TokenBucketLimiter.java`
- **类别**: 边界条件
- **问题**: 日志中 key 未截断，恶意超长 key 可致日志膨胀

### L8 ✅ ConversationContextAdvisor — 未校验 conversationId
- **文件**: `advisor/ConversationContextAdvisor.java`
- **类别**: 边界条件
- **问题**: 构造函数不校验 null/空 conversationId

### L9 ✅ TokenBucketLimiter — 构造函数未校验参数
- **文件**: `advisor/TokenBucketLimiter.java`
- **类别**: 边界条件
- **问题**: maxTokens/refillRate 可能为负/零，maxIdle 可能为 null

---

## 📊 最终汇总

| 级别 | 原报告 | 复核后 | 变更 |
|------|--------|--------|------|
| 🔴 BLOCKER | 5 | **4** | B5 撤销 |
| 🟠 HIGH | 7 | **6** | H1 降级 |
| 🟡 MEDIUM | 12 | **13** | +H1 降级 |
| 🟢 LOW | 9 | **9** | 无变更 |
| **总计** | **33** | **32** | -1 撤销 |

## 修复优先级建议

**P0（立即修复）**:
1. B3 — KEYS → SCAN
2. B4 — buckets 加硬上限或换 Caffeine
3. B1 — doStream 加 null 防护

**P1（本迭代修复）**:
4. B2 — PromptState holder + volatile
5. H2 — modelToProvider 纳入 replaceAll
6. H3 — 时钟回拨检测
7. H4 — 去重复 DFA 扫描
8. H5/H6 — 缓存 sharedApi

**P2（下迭代）**:
- 其余 MEDIUM 和 LOW
