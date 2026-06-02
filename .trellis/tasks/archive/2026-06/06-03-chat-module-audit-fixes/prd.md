# PRD: Chat 模块审查问题修复

**任务**: 06-03-chat-module-audit-fixes
**来源**: docs/reviews/2026-06-02-chat-module-full-review.md
**范围**: `src/main/java/com/smart/rag/chat/`

---

## 背景

对 chat 模块进行了 15 维度审查，发现 8 个 P1、1 个 P0、17 个 P2、5 个 P3 问题。本任务修复 P0 和 P1 问题，酌情修复高价值 P2。

## 验证修正

- **B-1 已移除**: Jakarta `@NotBlank` 已含 trim 语义，空白字符串不会通过校验。报告该条不成立。

---

## P0 修复（必须）

### C-1: 流式路径 usage metadata 永久丢失 + 死代码

**文件**: `mode/MultiTurnModeStrategy.java:112-132`

**问题**: `doFinally` 传 `null` 作为 `lastResp`，`if (lastResp != null)` 永远 false，usage 永远丢失。

**修复**:
1. 移除 `onStreamComplete` 的 `ChatResponse lastResp` 参数
2. 简化签名为 `(ctx, content, signal)`
3. 删除 dead branch（`if (lastResp != null)` 分支）
4. `onStreamComplete` 内统一走无 usage 的持久化路径

---

## P1 修复（必须）

### C-2: 全链路降级异常链断裂

**文件**: `service/impl/ChatServiceImpl.java:145-148`

**问题**: `throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "...")` 丢弃了 `lastException`（root cause）。

**修复**: `BusinessException` 已有 3 参构造器 `(ErrorCode, String, Throwable)`，改为：
```java
throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
    "所有模型均不可用...", lastException);
```

---

### E-1: 语义异常返回 HTTP 200

**文件**: `controller/ChatExceptionHandler.java`

**问题**: `ContentFilteredException`、`ModelNotFoundException`、`ProviderNotFoundException` 返回 `ResponseEntity.ok()`。

**设计取舍**: 代码注释说明 "200 + 非零 code" 是前端约定。但 WAF/CDN/监控层无法区分。

**修复方案**（保持前端兼容）:
1. `ContentFilteredException` → `HttpStatus.BAD_REQUEST` (400)
2. `ModelNotFoundException` → `HttpStatus.NOT_FOUND` (404)
3. `ProviderNotFoundException` → `HttpStatus.SERVICE_UNAVAILABLE` (503)
4. 保持 `GlobalResponse.code` 为业务错误码不变（前端无需改动）

---

### S-1: 删除操作使用 POST

**文件**: `controller/ModelParamsController.java:42`, `controller/PromptController.java:43`

**修复**:
- `@PostMapping("/{modelId}/params/delete")` → `@DeleteMapping("/{modelId}/params")`
- `@PostMapping("/{modelId}/delete")` → `@DeleteMapping("/{modelId}")`

注意：这是 REST API，需确认前端调用方同步修改。

---

### RCR-1: 消息持久化失败无补偿

**文件**: `service/ChatConversationHelper.java:119-123`

**修复**:
1. 在 `catch` 块中将失败的消息写入 Redis dead-letter 队列（key: `chat:dead-letter:{conversationId}`）
2. 添加 `@Scheduled(fixedDelay = 60000)` 定时任务重试 dead-letter 中的消息
3. 增加 `saveMessagesAndNotify` 失败的 Micrometer counter 指标

---

### M-1: 两个 ModeStrategy 大量重复代码

**文件**: `mode/SimpleModeStrategy.java`, `mode/MultiTurnModeStrategy.java`

**修复**:
1. 抽取抽象基类 `AbstractModeStrategy`，包含 `execute()` 和 `executeStream()` 模板方法
2. 子类只需覆写 `buildAdvisorChain()`
3. `onStreamComplete` 提升到基类（去掉 `lastResp` 参数后逻辑统一）

---

### T-1: ChatServiceImpl 不可纯单元测试

**文件**: `service/impl/ChatServiceImpl.java`

**修复**:
1. 将 `SecurityUtils.getCurrentUserId()` 封装为注入的 `UserContextProvider` 接口 + 实现类
2. 将 MDC 操作封装为 `MdcPropagator` 工具类（`capture()` / `restore(map)` / `clear()`）
3. 提取 `ChatFallbackOrchestrator` 封装降级链 + 熔断器 + eligibility 逻辑（解决 14 参构造器问题）

---

### M-3: ChatModeStrategy default 方法抛 BusinessException

**文件**: `mode/ChatModeStrategy.java:41-53`

**修复**: 改抛 `UnsupportedOperationException`，与 Java 惯例一致。

---

## 高价值 P2 修复（建议一并处理）

### X-1: MDC 传播在 Flux 线程切换后失效

**文件**: `service/impl/ChatServiceImpl.java:226-234`

**修复**: 配合 T-1 的 `MdcPropagator`，改用 `Flux.contextWrite()` + `io.micrometer:context-propagation`。

### C-4: 缓存失效在事务外

**文件**: `service/impl/ModelParamsServiceImpl.java:60-77`

**修复**: 使用 `TransactionSynchronizationManager.registerSynchronization(afterCommit -> paramsCache.invalidate(modelId))`。`SystemPromptServiceImpl` 同理。

### X-3: sendChunk terminated 检查在锁外

**文件**: `service/SseStreamBridge.java:40-51`

**修复**: 将 `terminated.get()` 检查移入 `synchronized (emitter)` 块内。

### C-3: getToolCallbacks 返回内部数组

**文件**: `tool/ToolRegistry.java:62`

**修复**: `return callbacks.clone();`

---

## 实施顺序

1. **C-1** (P0) — 移除 dead code，简化 `onStreamComplete`
2. **M-3** (P1) — 改 `UnsupportedOperationException`，简单改动
3. **C-2** (P1) — 添加 cause 参数，一行改动
4. **E-1** (P1) — 修改 HTTP status mapping
5. **S-1** (P1) — 改 DeleteMapping
6. **C-3** (P2) — clone 数组
7. **X-3** (P2) — 移 terminated 检查入 synchronized
8. **C-4** (P2) — 事务提交后失效缓存
9. **T-1** (P1) — 提取 UserContextProvider + MdcPropagator + ChatFallbackOrchestrator
10. **X-1** (P2) — 配合 T-1 的 MdcPropagator 改 contextWrite
11. **M-1** (P1) — 抽取 AbstractModeStrategy
12. **RCR-1** (P1) — dead-letter 队列 + 定时重试 + 指标

## 不在本任务范围

- I-1 (幂等键) — 需前端配合，单独任务
- P-1 (UsageService 异步写) — 需要引入队列基础设施
- B-2/B-3 (边界条件) — 低优先级
- SC-1/SC-2/SC-3 (可扩展性) — 低优先级

## 风险评估

- **S-1**: 前端调用方需同步修改 URL 和 HTTP method。需确认前端是否有调用。
- **E-1**: 前端如果依赖 HTTP 200 判断成功（而非 `GlobalResponse.code`），会受影响。需与前端确认。
- **M-1**: 重构策略模式可能影响 AGENT 模式（未来扩展）。设计需预留。
- **RCR-1**: 引入 Redis dead-letter 需确认 Redis 可用性和容量规划。
