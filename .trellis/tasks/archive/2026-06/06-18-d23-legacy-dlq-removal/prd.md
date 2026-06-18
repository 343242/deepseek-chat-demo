# PRD — D-2/D-3: legacy DLQ 代码删除（gated by 7 天 soak）

## Goal

7 天 soak 通过后，删除 legacy Redis DLQ 全部代码及残留引用，完成 `MessageDeadLetterQueue` /
`DeadLetterRetryScheduler` 的彻底退役。

## ⚠️ Gate（禁止提前执行）

**前置条件（全部满足才能 `task.py start`）**：

- [ ] D-4 已落地并合并（enqueue 已切断）
- [ ] `legacy.dlq.size` gauge + health `legacyDlqSize` detail 连续 **7 天** 滚动窗口 0 新条目
- [ ] Redis 队列 `DeadLetterEntry.QUEUE_KEY` 已排空（`size()=0`）
- [ ] `DeadLetterRetryScheduler` 连续 7 天日志无 `Retrying N dead-letter entries`（N>0）

**任一不满足 → 禁止 start。** 本子任务不要在本轮启动。

## Requirements

- **R1**：删除 `chat/service/MessageDeadLetterQueue.java`
- **R2**：删除 `chat/service/DeadLetterRetryScheduler.java`
- **R3**：删除 `DeadLetterEntry`（若仅被上述引用）
- **R4**：清理 `MessagingHealthIndicator` 的 `legacyDlqSize` detail + `MessageDeadLetterQueue` 注入（`:3/23/26/28/39/50/63`）
- **R5**：清理 `MessagingAutoConfiguration` 的 `MessageDeadLetterQueue` 注入（`:3/45/46`）
- **R6**：CI grep lint 防止 `MessageDeadLetterQueue` 被误回引（设计文档 §10 风险项）

## Acceptance Criteria

- [ ] `grep -rn 'MessageDeadLetterQueue' src/main/java` → 0
- [ ] `grep -rn 'DeadLetterRetryScheduler' src/main/java` → 0
- [ ] `grep -rn 'DeadLetterEntry' src/main/java` → 0（或仅在保留范围内）
- [ ] 健康检查 `/actuator/health` 不再含 `legacyDlqSize`
- [ ] `legacy.dlq.size` gauge 注册代码一并移除
- [ ] 编译 + 全量测试绿
- [ ] 删除前再跑一次 `gitnexus_impact` 确认爆炸半径

## Notes

- 本子任务**不要**在本轮 start。待 D-4 上线 7 天后由 `legacy.dlq.size` 数据驱动决定。
- `DeadLetterEntry.QUEUE_KEY` 的 Redis key 在删除代码后可手动清理（一次性运维）。
- 设计依据：`docs/design/messaging-bus.md` §9 Phase D Step 2/3 + §10 风险表。
