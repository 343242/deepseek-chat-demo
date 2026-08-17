# Publisher reliability: outbox + non-blocking fallback + send retry + usage observability

## Goal

将三个 publisher（`ChatMessagePublisher` / `ChatUsageTracker` / `EtlDispatchServiceImpl`）的
消息投递可靠性从"内存层尽力而为"升级为"DB 事务级最终一致"，消除双故障静默丢失、
请求线程阻塞、过度降级三个核心缺陷。同时补齐 usage 路径的可观测性。

> delegate 是 `RedisStreamMessageBus`（child 1 已交付，RocketMQ 已删除）。本任务对 MQ 实现透明——
> `OutboxMessageBus` 装饰 delegate，publisher 业务逻辑零改动。
>
> **跨 child 契约**（parent `08-05-redis-stream-mq-migration/prd.md`）：
> - child 1 在 `MessageBusManagement` 新增 `isCircuitBreakerOpen(topic)`，供 `SharedCircuitBreakerGate` 回退读。
> - child 1 占用 `MessagingErrorCode` 的 `400012`（`STREAM_OPERATION_FAILED`），本任务从 `400013` 起。

## 背景 / 当前缺陷（2026-08-05 核实）

三个 publisher 的 `messageBus.send()` 失败时降级路径各不相同，均有真实缺陷：

| Publisher | 当前降级 | 缺陷 |
|-----------|---------|------|
| `ChatMessagePublisher` | 同步 `saveWithBoundedRetry`（200/1000/3000ms） | 阻塞请求线程最坏 4.2s（SSE 收尾线程）；MQ+DB 双故障=静默丢失（仅 log+counter） |
| `ChatUsageTracker` | 仅 `log.error`，无降级无计数 | MQ 故障期间 token 用量全丢且不可观测 |
| `EtlDispatchServiceImpl` | 回退 `etlIoExecutor` 线程池 | 丢失重试/DLQ/FIFO 保障；与正常 ETL 抢线程池资源 |

共性根因：`send()` 是 fire-and-forget，无持久化缓冲——MQ 不可达时消息只能靠内存层兜底。

## Requirements

### R1 — Outbox 模式（核心）
- 新增 `outbox` 表（Flyway `V23`，V10–V22 已占用），持久化待发消息。
- 新增 `OutboxMessageBus implements MessageBus`，装饰真实 bus（`RedisStreamMessageBus`，child 1 交付）：
  - `send()`：INSERT outbox 行 → 查共享熔断门控 → 尽力即时投递 → 成功则删除行；失败则留给 relay。
  - `subscribe()` / `shutdown()`：直接委托真实 bus；`sendAsync()` 同样经 outbox 托管（INSERT + future 由投递任务完成），避免静默绕过持久化（见 design §6.1）。
- 新增 `OutboxRelay`（`SmartLifecycle`）：定时扫描 `pending` 行，经真实 bus 投递，
  成功删除，失败递增 `attempts` + 退避 `next_retry_at`，超 `maxAttempts` 标记 `dead`。
- 三个 publisher **业务逻辑零改动**（仅 catch 降级路径改为 counter，见 §7/R2）；注入的 `MessageBus` bean 换成 `OutboxMessageBus`。
- 三个 topic 全覆盖：`chat_message_save` / `chat_usage_record` / `rag_index_document`。
- **`payload_type` 列**（评审 P1-4）：outbox 表持久化 `payload_type`（=`payload.getClass().getName()`），
  relay 重建时用 `Class.forName()` 反序列化——chat/usage/etl 三类 payload 类不同，无此列 relay 无法
  按 `JacksonMessageCodec.decode(byte[], Class<T>)` 的要求确定目标类。
- **`tag` 列**（评审 P1-8）：持久化 `MessageEnvelope.tag`（当前三 publisher 不用，但传输元数据不应丢）。
- **`hash_key` 列**：outbox 表持久化 `hash_key`，relay 重建 envelope 时恢复——避免 `rag_index_document` 的有序分区键丢失。

### R2 — 非阻塞降级（有界 executor）
- `OutboxMessageBus.send()` 的即时投递失败后**不阻塞请求线程**：返回前仅完成 DB INSERT（~1ms），
  即时投递与 relay 回收均在独立 executor / 定时线程上异步进行。
- **即时投递 executor 必须有界**（评审"错误处理"P1）：MQ 故障期间热路径持续 send，无界 executor
  （含虚拟线程 per-task）会无限堆积"2 次 send + sleep"任务，耗内存并对故障 Redis 无效击打。
  用有界队列（core/max/queue 可配）+ 拒绝策略；**拒绝 = 行留 relay，天然不丢**。spawn 前二次检查熔断门控，
  OPEN 时连任务都不提交。retryExecutor 关闭丢弃队列任务是安全的（行留 PG，relay 兜底）。
- 移除 `ChatMessagePublisher.saveWithBoundedRetry`（同步降级路径）——outbox 行即为持久化保证。
- 移除 `EtlDispatchServiceImpl.dispatchViaThreadPool`（线程池兜底）——outbox→relay→MQ→consumer
  单一投递路径，保留 FIFO/重试/DLQ 语义。

### R3 — 发送侧有限重试
- 即时投递路径（outbox INSERT 之后的那次 best-effort send）：最多 2 次尝试，间隔 100ms。
- 仅覆盖 MQ 瞬时抖动（网络毛刺 / 主从切换）；硬故障由 relay 退避重试兜底。
- 与现有 per-topic `SendCircuitBreaker` 互补：熔断器 OPEN 时跳过即时投递（快速返回），
  行留 relay 处理。

### R4 — Usage 可观测性
- `ChatUsageTracker` catch 块新增 Micrometer counter `chat.usage.publish_failed`。
- `OutboxRelay` 指标：`messaging.outbox.relay.delivered` / `.failed` / `.dead` counter，
  `messaging.outbox.pending` gauge（按 topic tag），`messaging.outbox.oldest_age_seconds` gauge，
  `messaging.outbox.leader_active` gauge。

### R5 — Outbox DLQ + 清理（attempts 语义修正）
- relay 重试耗尽（`attempts >= maxAttempts`）→ 行标记 `status=dead` + `failure_reason`，
  记 `messaging.outbox.dead` counter + ERROR 日志。
- **`attempts` 仅统计"对看似可达 MQ 的真实投递尝试"**（评审 P1-7）：relay 在共享熔断门控
  `isOpen(topic)` 为真时**不 send、不递增 attempts**，只顺延 `next_retry_at`（间隔可配）。这样连续
  MQ 故障期间积压行的 attempts 冻结、不转 dead——消除"MQ 停 >~2h → 积压行耗尽退避转 dead → 7 天后
  清理 = 静默丢失"的语义硬伤。`dead` 只属于反复真实投递失败的毒消息。
- `outbox` 表 `dead` 行由定时任务清理（保留 7 天），走 `idx_outbox_dead_cleanup` 部分索引。

### R6 — Relay Leader Election（RedissonLeadership 组件）
- **抽出 `RedissonLeadership` 组件**（评审 P0-1/P0-2/"通用性"P1）：用 `volatile boolean leader`
  标志（持锁线程获取后置 true、解锁/异常退出置 false）作为唯一权威，`tryDrainIfLeader()` 只查该标志——
  **不能用 `RLock.isHeldByCurrentThread()`**（它比对获取锁线程的 threadId，drain 跑在 scheduler 线程
  上恒为 false，会导致 election 失效或 relay 永不 drain）。
- 多实例中仅 1 个实例跑 relay drain（`RLock` 持续持有 + 看门狗续约）；`leaderThread` daemon + 命名。
- **正常 stop() 解锁**（评审 P0-2）：`running=false → finally unlock`（修正早期"条件写反永不 unlock"，
  follower 无需等看门狗 30s）。unlock 吞 `IllegalMonitorStateException`（锁已失），恢复中断标志。
- **drain 异常隔离**（评审 P0-3）：`tryDrainIfLeader()` 整层 try/catch——`scheduleAtFixedRate` 任务
  抛未捕获异常会永久抑制后续执行，一次 DB 抖动不能杀死 relay。
- **drain-until-empty**（评审"性能"P1）：单次 poll 内循环 claim 直到清空（上限 `maxBatchesPerPoll`
  防饿死），峰值恢复速率从 6.4 msg/s 提升到 ~64 msg/s。
- leader 崩溃 → 看门狗超时（~30s）→ 其它实例抢占接管，最大回收延迟 ≤ pollInterval + 看门狗超时。
- `RedissonClient` 不可用时退化为"每实例都扫"（正确性不变，仅 DB 压力回升）。
- 用 `tryLock(5, -1, SECONDS)`（行为可预测、可测）而非 `lock(-1)`。

### R7 — 共享 OPEN 熔断信号（Redisson）
- 某实例本地熔断 trip OPEN 时，经 Redis 广播（`SET cb:{topic} 1 EX {cooldown}`）。
- **广播触发点契约**（评审 P1-6.2，跨 child 冻结点）：`SendCircuitBreaker.tripOpen()` →
  `gate.broadcastOpen(topic)`；`recordSuccess()` 从 HALF_OPEN→CLOSED → `gate.broadcastClosed(topic)`。
  gate nullable 注入（防循环依赖）。
- **广播降级**（评审 P1-6.3）：`broadcastOpen/Closed` 先更新本地缓存、再 try/catch 写 Redis，
  Redis 挂时降级为仅本地缓存——不破坏 send 链路。
- 其它实例即时投递前读共享信号，OPEN 则跳过（避免对已挂 MQ 无效 send）；**回退只调一次**（评审 P2）。
- `isOpen()` 本地缓存 2s（热路径不每消息一次 RTT）。
- Redis 不可用时回退各实例本地 `SendCircuitBreaker` 内存态（当前行为，降级安全）；防御性二级回退从
  `circuitBreakerState()` map 推导。
- `FOR UPDATE SKIP LOCKED` 作为 leader 切换瞬间的最后防线保留。

### R8 — 共享退避 BackoffSchedule（评审"通用性"P1）
- **`BackoffSchedule` 由 child 1 引入**（`infrastructure/messaging/BackoffSchedule.java`，配置驱动
  `app.messaging.backoff-ms` + `next(attempt)`）；relay 与 child 1 `RetrySweeper` 共用同一 bean，
  消除退避表三份独立实现（`ChatMessagePublisher.DEFAULT_BACKOFF_MS` 随 R2 删除、child 1 常量、relay 常量）。
  本任务仅注入复用，不重复定义配置段。

## Constraints

- **不使用 `@Transactional`**（项目规范，见 `database-guidelines.md`）；outbox INSERT 用 MyBatis-Plus
  `BaseMapper.insert`，relay 批量回收用自定义 mapper `FOR UPDATE SKIP LOCKED`。
- **不使用 JPA**——实体用 MyBatis-Plus `@TableName` + `BaseMapper`。
- **Redisson 仅做运行时协调**（leader election + 共享熔断信号）；outbox 消息体持久化在 PG，
  Redisson 不可用时优雅降级（relay 退化为每实例都扫、熔断退化为本地态），不阻断功能。
- relay 并发互斥靠 PG `FOR UPDATE SKIP LOCKED`（原生行锁，精确且无需续约），不靠 Redisson 锁。
- payload 序列化复用现有 `MessagePayloadCodec`（`JacksonMessageCodec`），存 JSONB 列；
  **`payload_type` 列持久化类名**以驱动 relay 反序列化（`decode(byte[], Class<T>)` 需显式类型）。
- 错误码遵循 `error-handling.md`：新增 outbox 相关码走 `MessagingErrorCode`（400001–400011 段，
  当前用到 400011，新增从 **`400013`** 起——`400012` 已被 child 1 `STREAM_OPERATION_FAILED` 占用）。
- outbox 不参与业务事务（publisher 侧无业务写）——outbox 行是独立的持久化缓冲，
  非"事务性事件发布"。`sendAfterCommit` 默认实现经 `send()` 走 outbox 即可，需显式注释
  "调用方不应在持有业务事务时调用"。
- claim 查询的 claiming 超时阈值**绑定配置参数**（`claiming-timeout-seconds`，单源），不再 SQL 硬编码
  `INTERVAL '5 minutes'`；claim 的 UPDATE 必须刷新 `updated_at`。

## Acceptance Criteria
- [ ] `V23__outbox.sql` 迁移脚本创建 `outbox` 表（含 `payload_type`/`tag`/`hash_key`/`updated_at` 列
      + `idx_outbox_claim` + `idx_outbox_dead_cleanup`），本地 `flyway migrate` 通过。
- [ ] `OutboxMessageBus` 装配为 `MessageBus` bean（`@Primary`），`subscribe()` 透传 delegate bus；
      `send()` INSERT 含 `payload_type`/`tag`/`hash_key`；**`sendAsync()` 经 outbox 托管**（非裸委托，避免静默绕过持久化，design §6.1）。
      `@Primary` bean 构造器形参显式注入 delegate（`@Qualifier`/bean 名），不用字段注入 `@Autowired MessageBus`（防自注入循环，design §6.2）。
- [ ] `OutboxRelay` 实现 `SmartLifecycle`，持续持锁（看门狗）；claim 短事务→事务外 send→批量 DELETE
      短事务（不在事务内做 MQ IO）。
- [ ] **`RedissonLeadership` 组件**：`isLeader()` 由 `volatile leader` 标志驱动（非 `isHeldByCurrentThread()`）；
      正常 stop() 在 finally 中 unlock（不等看门狗 30s）；leader 线程 daemon + 命名。
- [ ] **drain 异常隔离（P0-3）**：drain 内任意异常被 `tryDrainIfLeader()` 吞掉并记日志，后续调度不中断。
- [ ] **drain-until-empty**：单次 poll 内循环 claim 直到清空（上限 `maxBatchesPerPoll`）。
- [ ] Relay Leader Election：仅持锁实例跑 drain；leader 崩溃后其它实例在看门狗超时（~30s）内接管（Testcontainers Redis 验证）。
- [ ] **attempts 冻结语义（P1-7）**：gate `isOpen(topic)` 为真时 relay 不 send、不递增 attempts、只顺延
      `next_retry_at`；模拟 MQ 连续故障 >2h，积压行 attempts 不增长、无行转 dead。
- [ ] 共享 OPEN 熔断：某实例 trip → Redis 出现 `messaging:cb:{topic}` key → 其它实例即时投递被跳过；
      `isOpen()` 本地缓存 2s、回退只调一次；Redis 挂时回退本地熔断；`broadcastOpen/Closed` try/catch 降级不抛。
- [ ] 三个 publisher 业务逻辑零改动；catch 降级路径改为 counter（`publish_failed`）；`messageBus.send()` 正常路径经 outbox 即时投递成功后行被删除。
- [ ] MQ 不可达时：send() 不阻塞请求线程（返回时仅完成 INSERT）；即时投递 executor 有界，拒绝时行留 relay；relay 在 MQ 恢复后投递成功。
- [ ] `ChatMessagePublisher.saveWithBoundedRetry`/`DEFAULT_BACKOFF_MS` 与 `EtlDispatchServiceImpl.dispatchViaThreadPool` 已移除（grep = 0）；同步降级语义由 outbox 接管。
- [ ] 即时投递路径含 2 次有限重试（间隔 100ms），共享熔断 OPEN 时跳过。
- [ ] `ChatUsageTracker` 失败计数器 `chat.usage.publish_failed` 注册；MQ 故障期间 counter 递增。
- [ ] relay 指标（delivered/failed/dead/pending/oldest_age/leader_active）注册到 MeterRegistry。
- [ ] relay `maxAttempts`（默认 16）耗尽 → 行标记 `dead` + `failure_reason` + counter + ERROR 日志（仅毒消息）。
- [ ] `payload_type` 反序列化：relay 按 `payload_type` 用 `Class.forName` + `codec.decode` 正确还原 chat/usage/etl 三类 payload。
- [ ] `tag` 持久化：relay 重建 envelope 时恢复 tag（null 与非 null 均保留）。
- [ ] `hash_key` 持久化：relay 重建 envelope 时恢复 hashKey（`rag_index_document` 有序分区键不丢失）。
- [ ] `BackoffSchedule` 由 child 1 引入、relay 注入复用同一 bean（`app.messaging.backoff-ms` 单源）。
- [ ] 清理任务 cron 可配置（`@Scheduled(cron = "${app.messaging.outbox.cleanup-cron:0 0 4 * * *}")`，评审"扩展性"硬编码）。
- [ ] claim 查询 claiming 超时阈值绑定配置（单源），claim UPDATE 刷新 `updated_at`。
- [ ] at-least-once 语义明确文档化：重复投递（即时投递与 relay 并发、send 成功 DELETE 失败）由消费端 SETNX + DB 唯一约束兜底；`FOR UPDATE SKIP LOCKED` 仅尽力减少，不消除。
- [ ] 单测全绿：`OutboxMessageBusTest`、`OutboxMessageBusSendAsyncTest`、`RedissonLeadershipTest`、`OutboxRelayTest`、`OutboxRelayLeaderTest`、
      `SharedCircuitBreakerGateTest`、`OutboxHashKeyTest`、`OutboxPayloadTypeTest`、`OutboxTagTest`、
      `OutboxTracePropagationTest`、`BackoffScheduleTest`、即时重试测试、usage 计数器测试。

## Out of Scope

- RedisStreamMessageBus 实现（child 1 `08-05-redis-stream-message-bus`，本任务依赖其交付）。
- 重写 `IdempotentHandler`（消费端 SETNX 幂等，与 publisher 侧无关，保持不变）。
- 重写 `DeadLetterOperations` SPI（消费端 DLQ 运维，独立任务）。
- outbox 行跨业务事务的 2PC / xa 语义（本场景 publisher 侧无业务写，不需要）。
