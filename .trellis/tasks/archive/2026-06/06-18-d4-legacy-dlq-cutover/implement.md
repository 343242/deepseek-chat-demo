# Implement — D-4: legacy DLQ 切断

## 前置（Gate）

- [ ] gitnexus impact 分析 HIGH 已告知用户并获确认
- [ ] 工作树状态确认（`git status`），记录起点
- [ ] `task.py start` 已将 D-4 设为 active

## 步骤

### 1. ChatConversationHelper（核心改动）

- [ ] 移除 `deadLetterQueue` 字段（`:35`）
- [ ] 移除构造器参数（`:42`）与赋值（`:47`）
- [ ] `saveMessagesAndNotify`（`:103-130`）移除外层 try/catch，保留 `transactionTemplate.executeWithoutResult(status -> {...})` 事务体不变
- [ ] 清理 import：`MessageDeadLetterQueue`、`DeadLetterEntry`（grep 确认无其他引用再删）
- [ ] 验证：`grep -n 'deadLetterQueue\|DeadLetterEntry' src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java` → 空

### 2. ChatMessagePublisher（同步降级路径 + 有限重试）

- [ ] `publishMessageSave` 的 `catch(MessagingException)` 内改为调 `saveWithBoundedRetry(...)`（`:82` 附近）
- [ ] 实现 `saveWithBoundedRetry`：3 次重试，退避 200ms/1s/3s；**仅**对瞬时 DB 异常（`DataAccessException` / `TransactionSystemException`）重试；其他 `RuntimeException` 不重试
- [ ] 实现 `reportFallbackFailure`：记 ERROR（`ConversationIdUtil.mask` 脱敏）+ 自增 `chat.save.fallback_failed` 计数器（标签 `result=exhausted`）；不传播到 `processResult` / `executeStream.doFinally`
- [ ] `ChatMessagePublisher` 构造器注入 `@Nullable MeterRegistry`（无 registry 时跳过计数仅记日志）
- [ ] `sleepNoThrow` 封装 `Thread.sleep`，`InterruptedException` 恢复中断标志后 break（不重试）

### 3. 测试

- [ ] `ChatConversationHelperTest`（`:122/131/144`）：移除 enqueue 断言；改为断言落库失败抛异常（或交由 consumer test 覆盖失败路径）
- [ ] `ChatMessagePublisherTest`（`:114`）：补两类用例——(a) 瞬时 DB 异常（`DataAccessException`）重试后成功；(b) DB 硬故障重试耗尽 → 不传播、记 ERROR、`chat.save.fallback_failed` 计数 +1；(c) 非瞬时异常（如 NPE）不重试、直接告警
- [ ] 确认 `ChatMessageSaveConsumerTest:136`（异常传播）对改造后真实链路成立——必要时补一个用真实 `ChatConversationHelper`（mock `transactionTemplate` 抛）的端到端用例

### 4. 验证命令

```bash
./mvnw -q compile
./mvnw -q test -Dtest='ChatConversationHelperTest,ChatMessagePublisherTest,ChatMessageSaveConsumerTest'
grep -n 'deadLetterQueue\|DeadLetterEntry' src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java   # 期望空
```

### 5. 收尾

- [ ] `gitnexus_detect_changes()` 确认改动范围仅预期符号（`saveMessagesAndNotify`、`ChatConversationHelper` ctor、`ChatMessagePublisher.publishMessageSave`）
- [ ] commit（**不 push**，等用户）；commit message 引用 design.md 的权衡说明
- [ ] 任务归档前更新 `docs/design/messaging-bus.md` §9 Phase D Step 4 状态（如需）

## Review Gates

- 改 `ChatConversationHelper` 前：**用户确认 HIGH 风险**（本步骤 1 开工前停一次）
- 测试绿后：用户确认再 commit

## Rollback Point

- 步骤 1-2 完成但测试红 → `git checkout -- src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java src/main/java/com/smart/rag/chat/service/ChatMessagePublisher.java`，回 design 重判。
