# PRD — Phase D: 消息总线收尾（legacy DLQ 退役 + 迁移补全）

## Goal

落地 `docs/design/messaging-bus.md` §9 Phase D 的全部剩余项：退役自建 legacy Redis DLQ、
将 `DocumentSupersedeService` 迁移到消息总线、`TracePropagator` 真实实现、消费端指标补全。
Phase 0/A/B/C 已落地，本父任务为消息总线设计的收尾。

## Background

现状（2026-06-18 已对仓库逐项核实）：

- **已落地**：Phase 0（always-on 无条件装配，NoOpMessageBus 已移除）、Phase A（SPI 全套 +
  RocketMQ 5.x Producer/Push/Simple 核心 + 熔断器 + Lua 幂等 + SimpleConsumer 滑动窗口背压；
  §3.1 O-01 六项必须指标全在）、Phase B（`EtlDocumentConsumer`，RAG 索引 SimpleConsumer+FIFO）、
  Phase C（`ChatMessagePublisher`/`ChatMessageSaveConsumer` + `UsageRecordConsumer`，chat save/usage）、
  §6.4 健康检查、§5.12 运维脚本 `scripts/init-rocketmq-topics.sh`。
- **未落地（Phase D 全部）**：
  - legacy Redis DLQ 三件套（`MessageDeadLetterQueue` / `DeadLetterRetryScheduler` /
    `ChatConversationHelper` 的 enqueue 点）仍在线；且因 `saveMessagesAndNotify` 内部
    `catch → enqueue → 吞异常`，bus 的 `%DLQ%` 对 chat-save 形同虚设。
  - `DocumentSupersedeService` 仍是 `@EventListener` + `@Async("etlIoExecutor")`。
  - `TracePropagator` 仍是 `NO_OP`（traceId 不跨消息传播）。
  - 消费端指标缺 `messaging.consumer.lag` / `messaging.consumer.receive.last.success` /
    `messaging.consumer.assigned.groups`。
  - §5.6 `DeadLetterOperations` 是 `UNSUPPORTED` 桩。

### 关键约束：7 天 soak 门控

D-2/D-3（删 legacy DLQ 代码）有硬前置：legacy DLQ 7 天滚动窗口 0 新条目 + Redis 队列排空。
当前 enqueue 点在线，计时未起算。故 Phase D 拆两批：

- **第一批（无门控，立即做）**：D-4（切断 enqueue → 启动 soak）、D-1、D-6、D-5
- **第二批（gated）**：D-2/D-3

## Requirements

- **R1**：chat-save 落库失败改由 RocketMQ broker 重试 + `%DLQ%` 接管，不再写 legacy Redis DLQ。
- **R2**：`DocumentSupersedeService` 异步路径走消息总线，与 `EtlDocumentConsumer` 边界清晰、无重复索引。
- **R3**：消息跨 producer/consumer 传播 traceId（MDC + W3C TraceContext），替换 `NO_OP`。
- **R4**：消费端健康指标补齐 lag / receive.last.success / assigned.groups。
- **R5**：legacy DLQ 在 soak 通过后彻底删除（代码 + 健康检查/装配残留引用）。

## Task Map

| 子任务 | 范围 | 门控 | 优先级 |
|--------|------|------|--------|
| `d4-legacy-dlq-cutover` | 切断 `ChatConversationHelper` enqueue，异常抛出由 bus 接管 | 无（启动 soak） | **P1，先做** |
| `d1-document-supersede-migration` | `DocumentSupersedeService` `@Async` → bus | 无 | P2 |
| `d6-trace-propagator` | `TracePropagator` 真实实现 | 无 | P2 |
| `d5-consumer-metrics` | lag / receive.last.success / assigned.groups | 无 | P2 |
| `d23-legacy-dlq-removal` | 删 `MessageDeadLetterQueue` + `DeadLetterRetryScheduler` + 残留引用 | D-4 后 7 天 soak 0 新条目 + 队列排空 | P2，blocked |

## Sequencing

1. **D-4 先行**（P1）：切断 enqueue → 启动 7 天 soak 计时 → bus `%DLQ%` 真正接管 chat-save。
2. **D-1 / D-6 / D-5 并行**（互不依赖，可任意顺序；建议 D-6 与 D-4 同批，均涉 chat 主链路可观测性）。
3. **D-2/D-3**：观察 `legacy.dlq.size` gauge + health detail `legacyDlqSize` 连续 7 天 0 → 排空 Redis 队列 → 删除代码。

## Cross-Child Acceptance Criteria

- [ ] D-4 落地后，模拟 chat-save 落库失败 → bus consumer 返回 FAILURE → broker 重试 → 耗尽入 `%DLQ%{save-group}`；`legacy.dlq.size` 不再增长。
- [ ] D-1 落地后，文档覆盖事件经消息总线投递，与 `EtlDocumentConsumer` 无重复索引 / 无消费组争抢。
- [ ] D-6 落地后，producer 端 traceId 注入 message properties，consumer 端 MDC 恢复一致（端到端测试验证）。
- [ ] D-5 落地后，三项指标在 `/actuator/metrics` 可查询。
- [ ] D-2/D-3 落地后，`grep -rn MessageDeadLetterQueue src/main/java` 0 命中，`grep DeadLetterRetryScheduler` 0 命中，健康检查不再暴露 `legacyDlqSize`。
- [ ] 全部子任务完成后，`docs/design/messaging-bus.md` §9 Phase D 退出条件逐项满足。

## Out of Scope

- **§5.6 `DeadLetterOperations` 真实实现**（scan/replay/count，当前 `UNSUPPORTED` 桩）——属 S-02 ops-only 接口，**本轮推迟**到 Phase 2+ 或按需单开任务。
- EX-01 拦截器 SPI、EX-02 动态配置（设计文档已标 Phase 2+）。
- 事务消息、SQL92 过滤（§2.2 非目标）。

## Notes

- 子任务详细 `design.md` / `implement.md` 在各自激活时补齐；**D-4 因 HIGH 风险 + 先行，本轮直接给出全套规划**。
- D-4 编辑 `saveMessagesAndNotify` 的 gitnexus impact 分析为 **HIGH**（3 直接调用方，命中 chat 主链路），实现前需用户确认。
- 设计文档权威来源：`docs/design/messaging-bus.md` §9 Phase D。
