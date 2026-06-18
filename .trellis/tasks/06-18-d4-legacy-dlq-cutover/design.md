# Design — D-4: legacy DLQ 切断

## Impact Analysis（gitnexus，已跑）

- **Risk: HIGH** ⚠️（实现前必须告知用户并获确认）
- 直接调用方（depth 1）：`ChatMessageSaveConsumer.start`、`DeadLetterRetryScheduler.retryFailedMessages`、`ChatMessagePublisher.publishMessageSave`
- 间接受影响（depth 2-3）：`MultiTurnModeStrategy.onStreamComplete` / `executeStream`、`ChatServiceImpl.processResult` / `chat`、`ChatController`
- 受影响执行流：`chat`（ChatController）、`processResult`、`retryFailedMessages`
- 受影响模块：Mode（indirect）、Service（direct）、Impl（indirect）

## 异常流改造

### 现状

`saveMessagesAndNotify`：`catch(Exception){ log; enqueue; }` → 吞异常 → 所有调用方看到成功。

### 目标

`saveMessagesAndNotify`：**移除外层 try/catch**，让 `transactionTemplate.executeWithoutResult` 的
异常自然传播。事务模板失败时已自动回滚（无半写入），broker 重试是干净的。

> 决策：直接移除 try/catch（而非 `catch{ log; throw; }`）。调用方已有日志职责
> （consumer 的 `PushConsumerListener` 在 catch(Exception) 内 ERROR 日志；publisher 降级处补日志）。
> 方法内 `transactionTemplate.executeWithoutResult` 事务边界不动。

### 3 个调用方的处理

| 调用方 | 现状 | D-4 后 | 改动 |
|--------|------|--------|------|
| `ChatMessageSaveConsumer:59` | 期望异常传播（被 helper 吞掉） | 异常 → `PushConsumerListener` catch(Exception) → `ConsumeResult.FAILURE` → broker 重试 | **无代码改动**（行为自动正确）；测试已覆盖 |
| `ChatMessagePublisher:82`（同步降级） | 在 `catch(MessagingException)` 内调 `saveMessagesAndNotify` | `saveMessagesAndNotify` 现会抛 → 需在降级调用外加 try/catch 记 ERROR | **需改 `ChatMessagePublisher`** |
| `DeadLetterRetryScheduler:38` | 自己 `try/catch`，失败 re-enqueue | `saveMessagesAndNotify` 抛 → 被 scheduler catch → re-enqueue（legacy 行为不变，D-3 删） | **无代码改动** |

### ChatConversationHelper 改动

1. 移除字段 `private final MessageDeadLetterQueue deadLetterQueue;`
2. 移除构造器参数 `MessageDeadLetterQueue deadLetterQueue` 及赋值 `this.deadLetterQueue = deadLetterQueue;`
3. `saveMessagesAndNotify`：移除外层 try/catch（保留 `transactionTemplate.executeWithoutResult(status -> {...})`）
4. 清理 import：`MessageDeadLetterQueue`、`DeadLetterEntry`（若仅此处用）

### ChatMessagePublisher 改动（同步降级路径 + 有限重试）

> 设计决策（方案 ①+⑤）：降级路径对 `saveMessagesAndNotify` 做**有限重试**覆盖瞬时 DB 故障，
> 耗尽后记 ERROR + `chat.save.fallback_failed` 告警计数。不再保留 legacy Redis DLQ（会与 soak 门控冲突）。

```java
} catch (MessagingException e) {
    log.warn("Message bus unavailable, falling back to synchronous save", e);
    saveWithBoundedRetry(conversationId, userMessage, assistantContent,
        candidateId, totalTokens, elapsedMs);
}

/**
 * 同步降级路径有限重试：仅对瞬时 DB 故障（DataAccessException / TransactionSystemException）重试，
 * 覆盖现实主流的 DB 失败模式（连接抖动 / 死锁）。硬故障重试耗尽后记 ERROR + 告警计数。
 * <p>
 * 策略：3 次，指数退避 200ms / 1s / 3s。降级路径罕见（仅 bus 不可达时触发），阻塞可接受。
 * 实现可选 RetryTemplate（若 spring-retry 在 classpath）或简单 for-loop + Thread.sleep——本任务取后者（零新依赖）。
 */
private void saveWithBoundedRetry(String conversationId, String userMessage,
                                   String assistantContent, String candidateId,
                                   int totalTokens, long elapsedMs) {
    long[] backoffMs = {200, 1000, 3000};
    for (int attempt = 0; ; attempt++) {
        try {
            conversationHelper.saveMessagesAndNotify(
                conversationId, userMessage, assistantContent, candidateId, totalTokens, elapsedMs);
            return;  // 落库成功
        } catch (DataAccessException | TransactionSystemException e) {
            // 瞬时 DB 故障：未到末次则退避后重试
            if (attempt >= backoffMs.length - 1) {
                reportFallbackFailure(conversationId, e);  // 重试耗尽 → DB 硬故障
                return;
            }
            log.warn("Fallback save transient DB failure, retry in {}ms (attempt {}): conversationId={}",
                backoffMs[attempt + 1], attempt + 1, ConversationIdUtil.mask(conversationId));
            sleepNoThrow(backoffMs[attempt + 1]);
        } catch (RuntimeException e) {
            // 非瞬时 DB 异常（逻辑错误等）：不重试，直接告警
            reportFallbackFailure(conversationId, e);
            return;
        }
    }
}

/** 重试耗尽 / 不可重试：记 ERROR（脱敏）+ 自增 chat.save.fallback_failed 告警计数。数据已 SSE 投递，丢的是历史持久化。 */
private void reportFallbackFailure(String conversationId, RuntimeException e) {
    log.error("Fallback save failed (bus + DB both unavailable): conversationId={}",
        ConversationIdUtil.mask(conversationId), e);
    if (meterRegistry != null) {
        meterRegistry.counter("chat.save.fallback_failed", "result", "exhausted").increment();
    }
}
```

- `ChatMessagePublisher` 构造器注入 `@Nullable MeterRegistry`（无 registry 时仅记日志，跳过计数）。
- `sleepNoThrow` 封装 `Thread.sleep`，`InterruptedException` 恢复中断标志后 `break`（不重试）。

## 行为变更与权衡

- **失去的兜底（收窄后）**：同步降级路径加了有限重试（3 次，覆盖瞬时 DB 故障），真正丢数据的窗口从"任何 DB 失败"收窄到"DB 硬宕且重试耗尽"——此时记 ERROR + `chat.save.fallback_failed` 告警计数。bus 不可达由熔断器 + 健康检查 + `messaging.send.count{result=fail}` 监控；该窗口运维可告警、可接受（丢的是会话历史持久化，assistant 内容已 SSE 投递，用户可重发）。这是设计文档 §9 Phase D Step 4 "日志告警"的具体化。
- **不保留 legacy Redis DLQ 作 double-failure 兜底**：若保留它接收 double-failure 条目，soak 的"7 天 0 新条目"门控永远满足不了，D-2/D-3 无法推进。有限重试 + 计数告警是更干净的替代。
- **consumer 路径增强**：bus `%DLQ%` 终于对 chat-save 生效（之前被吞异常屏蔽）。`messaging.retry.count` /
  `messaging.dead.count` 开始真实反映 chat-save 失败。
- **soak 启动**：enqueue 切断后 `legacy.dlq.size` 停止增长，7 天计时开始。

## 兼容性

- `MessageDeadLetterQueue` bean 仍存在（`MessagingHealthIndicator`/`MessagingAutoConfiguration` 仍注入），D-2 才删。
  `ChatConversationHelper` 不再注入它 → 无 Spring 装配冲突（bean 多一个消费者无所谓）。
- `DeadLetterEntry` 若仅 `ChatConversationHelper` 引用，移除后该类暂时孤儿（D-2 删）；
  若 `DeadLetterRetryScheduler`/`MessageDeadLetterQueue` 仍引用则保留。实现时 grep 确认。

## 回滚

- 单 commit，`git revert` 即可恢复 enqueue + 吞异常行为。
- 回滚触发条件：D-4 后 chat-save 失败率异常上升且 bus 重试/DLQ 未生效
  （监控 `messaging.retry.count` / `messaging.dead.count`）。
