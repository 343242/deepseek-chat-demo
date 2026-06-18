# PRD — D-4: legacy DLQ 切断（ChatConversationHelper 不再 enqueue）

## Goal

移除 `ChatConversationHelper` 对 `MessageDeadLetterQueue` 的依赖：落库失败时不再写 legacy Redis DLQ，
异常抛出让 RocketMQ broker 重试 + `%DLQ%` 接管。本步同时启动 Phase D 第二批（D-2/D-3）的 7 天 soak 计时。

## Background

当前 `saveMessagesAndNotify`（`ChatConversationHelper.java:103-130`）：

```java
try { transactionTemplate.executeWithoutResult(... 双消息写入 + onNewMessages ...); }
catch (Exception e) {
    log.error(...);
    deadLetterQueue.enqueue(new DeadLetterEntry(...));   // 吞异常，不 rethrow
}
```

问题：catch 吞掉异常 → 3 个调用方都看到"成功"：

- `ChatMessageSaveConsumer:59`（bus consumer）→ listener 返回 SUCCESS → broker ack → bus `%DLQ%` **永不触发**
- `ChatMessagePublisher:82`（同步降级路径）→ 误以为落库成功
- `DeadLetterRetryScheduler:38`（legacy 重试器）→ 靠它兜底，但这正是要退役的机制

结果：legacy Redis DLQ 仍在扛全部 chat-save 失败兜底，RocketMQ 死信机制对 chat-save 无效，
D-2/D-3 的 soak 永远无法起算。

## Requirements

- **R1**：`saveMessagesAndNotify` 落库失败时异常传播（不再吞咽、不再 enqueue legacy DLQ）。
- **R2**：移除 `ChatConversationHelper` 对 `MessageDeadLetterQueue` 的字段/构造器依赖与 `DeadLetterEntry` 引用。
- **R3**：bus consumer 路径：异常 → `PushConsumerListener` 捕获 → `ConsumeResult.FAILURE` → broker 按 `maxDeliveryAttempts=16` 重试 → 耗尽入 `%DLQ%{save-group}`。
- **R4**：同步降级路径（`ChatMessagePublisher.publishMessageSave`）：`bus.send` 失败后对 `saveMessagesAndNotify` 做**有限重试**（3 次，退避 200ms/1s/3s，仅瞬时 DB 异常 `DataAccessException`/`TransactionSystemException`），覆盖 DB 瞬时故障（连接抖动/死锁）；重试耗尽或 DB 硬故障 → 记 ERROR + `chat.save.fallback_failed` 告警计数（不传播；丢的是历史持久化，内容已 SSE 投递）。不保留 legacy Redis DLQ（会与 soak 门控冲突）。
- **R5**：legacy retry scheduler（`DeadLetterRetryScheduler`）调用路径不受影响（它有自己的 catch+enqueue，D-3 才删）。
- **R6**：`legacy.dlq.size` gauge 在 D-4 后停止增长（soak 观测起点）。

## Acceptance Criteria

- [ ] `grep -n 'deadLetterQueue\|DeadLetterEntry' src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java` → 0 命中
- [ ] `ChatConversationHelperTest` 更新：落库失败时不再 enqueue，异常传播（移除旧 enqueue 断言）
- [ ] 确认 `ChatMessageSaveConsumerTest:136`（"saveMessagesAndNotify 抛异常时从 handler 传播出去，不静默吞咽"）对改造后真实链路成立
- [ ] `ChatMessagePublisherTest` 补三类用例：(a) 瞬时 DB 异常重试后成功；(b) DB 硬故障重试耗尽 → 不传播、记 ERROR、`chat.save.fallback_failed` +1；(c) 非瞬时异常不重试直接告警
- [ ] 验证：mock DB 失败 → `legacy.dlq.size` 不增长；bus consumer 触发 broker 重试（`messaging.retry.count` 递增）
- [ ] `./mvnw compile` + 相关模块测试绿
- [ ] gitnexus impact HIGH 风险已告知用户并获确认
- [ ] `gitnexus_detect_changes` 确认改动范围仅预期符号

## Notes

- **HIGH 风险**（gitnexus impact：3 直接调用方 `ChatMessageSaveConsumer.start` / `DeadLetterRetryScheduler.retryFailedMessages` / `ChatMessagePublisher.publishMessageSave`，命中 chat 主链路 + `processResult` + `executeStream`）。详见 `design.md`。
- 本任务**不删** `MessageDeadLetterQueue` / `DeadLetterRetryScheduler`（D-2/D-3 gated）。`MessagingHealthIndicator` 的 `legacyDlqSize` detail **保留**（soak 观测用）。
- 设计依据：`docs/design/messaging-bus.md` §9 Phase D Step 4。
