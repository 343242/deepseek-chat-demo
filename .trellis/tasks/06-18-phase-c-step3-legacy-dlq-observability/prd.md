# Phase C Step 3: Legacy Redis DLQ 可观测性（排空机制已就绪）

## Goal
给 legacy Redis `MessageDeadLetterQueue` 补可观测性，让运维能确认 Phase D 的硬前置「legacy DLQ 7 天滚动窗口 0 新条目」。**排空机制本身已完整就位（`DeadLetterRetryScheduler` 每 60s drain），本任务不改排空逻辑，只补 size 查询 + metric + health detail。**

## 背景 / 代码现状（2026-06-18 核实）
- `DeadLetterRetryScheduler` `@Scheduled(fixedDelay=60_000)` → `deadLetterQueue.drain(50)` → 逐条 `saveMessagesAndNotify`（Phase 1 新签名）；失败重试/耗尽丢弃；汇报 `getAndResetFailureCount()`。**排空 OK，无需改。**
- `MessageDeadLetterQueue(@Nullable RedissonClient)`：API 仅 `enqueue` / `drain(int)`（破坏性）/ `getAndResetFailureCount`。**无 `size()`，无 metric，无 health 暴露。**
- `MessagingHealthIndicator`：只查 producer 连通性 / 活跃订阅 / 熔断器；**不看 legacy DLQ**。
- `RocketMQMessageBus` 的 `%DLQ%` + `metrics.recordDeadLetter` 是**新 bus 的 DLQ**，与 legacy Redis DLQ 无关，不在本任务范围。

## Decisions（已定）
- **D1**：新增 `MessageDeadLetterQueue.size()` → `long`（`redissonClient != null ? redissonClient.<DeadLetterEntry>getQueue(QUEUE_KEY).size() : 0L`）。非破坏性。
- **D2**：注册 Micrometer gauge `legacy.dlq.size`（绑定 `MessageDeadLetterQueue::size`）。注入 `MeterRegistry`（`@Autowired(required=false)`，与 RocketMQMessageBus 一致；null 时跳过注册）。实现位置选最贴近既有 gauge 习惯的写法（查仓库现有 Gauge 注册模式后定）。
- **D3**：`MessagingHealthIndicator` 增加 detail `legacyDlqSize`（来自 `MessageDeadLetterQueue.size()`）。**仅作 detail 展示，不单独触发 DOWN**（持续非零不代表不健康——scheduler 在排空；DOWN 仍只由 producer 不可达触发，避免抖动）。
- **D4**：`@Nullable RedissonClient`（test profile 无 Redisson）→ `size()` 返回 0、gauge 报 0，不报错。
- **D5**：`DeadLetterRetryScheduler` 现有日志（"Retrying N entries"）+ `getAndResetFailureCount()` 保留不动。

## Implementation Plan
1. `MessageDeadLetterQueue`：加 `size()`；注入 `MeterRegistry`（可选）注册 `legacy.dlq.size` gauge。
2. `MessagingHealthIndicator`：注入 `MessageDeadLetterQueue`，`doHealthCheck` 加 `withDetail("legacyDlqSize", size)`。
3. 测试：`MessageDeadLetterQueueTest`（size：redisson 在场→RQueue.size()；null→0；gauge 注册）；`MessagingHealthIndicatorTest`（detail 出现）。
4. `./mvnw test-compile` + 相关单测全绿。

## Acceptance Criteria
- [ ] `MessageDeadLetterQueue.size()` 非破坏性返回队列长度（null redisson → 0）。
- [ ] `legacy.dlq.size` gauge 注册到 MeterRegistry（Actuator `/metrics/legacy.dlq.size` 可查）。
- [ ] `MessagingHealthIndicator` 暴露 `legacyDlqSize` detail（`/health` 可见），且不改变现有 UP/DOWN 判定。
- [ ] `DeadLetterRetryScheduler` 排空行为零变更。
- [ ] 相关单测全绿；test-compile 通过。

## Out of Scope
- 排空逻辑改造（已就绪）。
- Phase D（删除 `MessageDeadLetterQueue`）—— 需本任务可观测性上线后，观察 7 天 0 新条目才可执行。
- 新 bus `%DLQ%` 相关（Phase A 已有 `metrics.recordDeadLetter`）。

## Technical Notes
- 前驱：Phase C Step 2（commit `7df4d48`）已让 chat save 经 bus；`DeadLetterRetryScheduler` 已用新 `saveMessagesAndNotify` 签名。
- 编辑符号前 `gitnexus_impact`（CLAUDE.md）；索引 stale（停在 `79fe7d9`），先 `npx gitnexus analyze`（或主会话已 fresh 则直接用）。
- 参考：`RocketMQMessageBus` 的 `metrics.recordDeadLetter` + `MessagingMetrics`（gauge/counter 注册模式）；`MessagingHealthIndicator` 现有 detail 写法。
