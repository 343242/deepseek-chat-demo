# Implementation Plan — Outbox 装饰器 + Relay + Redisson 协调

> 前置阅读：`prd.md`、`design.md`（含修正后的 §2 有界 executor、§3.1 RedissonLeadership、§3.2
> drain 异常隔离 + 熔断门控冻结 attempts、§3.3 BackoffSchedule、§3.4 共享熔断广播契约、
> §4 payload_type/tag 列、§5 traceId、§8 错误码 400013）。
>
> 执行顺序：存储层 → BackoffSchedule → 熔断门控 → 装饰器 → RedissonLeadership → relay →
> 装配 → 移除旧 catch → 清理 → 错误码 → 验证。
>
> **跨 child 依赖**：delegate 是 `RedisStreamMessageBus`（child 1）。本任务依赖 child 1：
> (a) `MessageBusManagement.isCircuitBreakerOpen(topic)` 已新增（parent 契约）；
> (b) `SendCircuitBreaker` 构造器可注入 gate + topic（P1-6.2 冻结点）；
> (c) `send()` 对 traceparent "已存在不覆盖"（§5）。

## Step 1 — Flyway 迁移：outbox 表（V23，非 V10）

**文件**：`src/main/resources/db/migration/V23__outbox.sql`

- V10–V22 已占用，本任务用 **V23**。
- 按 design.md §4 DDL 创建 `outbox` 表：含 `payload_type`/`tag`/`hash_key`/`updated_at` 列，删 `sent_at`。
- 索引：
  - `idx_outbox_claim`（部分索引 `WHERE status IN ('pending','claiming')`）。
  - `idx_outbox_dead_cleanup`（部分索引 `WHERE status='dead'`）——评审"性能"P3，避免 dead 清理全表扫。
- 幂等：`CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`。

**验证**：`./mvnw flyway:migrate` 无报错；`grep -r 'V10.*outbox' .` = 0。

## Step 2 — 实体 + Mapper

**文件**：
- `infrastructure/messaging/outbox/OutboxEntry.java` — `@TableName("outbox")`，含
  `payloadType`/`tag`/`hashKey`/`updatedAt` 字段。`payload`/`headers` 用 `String`（JSONB 文本）。
- `infrastructure/messaging/outbox/OutboxMapper.java` — `extends BaseMapper<OutboxEntry>`：
  - `claimPending(limit, now, claimingTimeoutSeconds)` — `SELECT ... WHERE (status='pending'
    AND next_retry_at<=now) OR (status='claiming' AND now - updated_at >
    (#{claimingTimeoutSeconds}||' seconds')::interval) ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED`
    （评审 P2：**绑定参数**，不再硬编码 `INTERVAL '5 minutes'`）。
  - `markClaiming(ids, now)` — 批量 `UPDATE SET status='claiming', updated_at=now WHERE id IN (...)`
    （**刷新 updated_at**，否则超时回收依赖的 updated_at 停留在 INSERT 时间）。
  - `deleteByIds(ids)` — **批量 DELETE WHERE id IN (...)**（评审"性能"P4，合并短事务）。
  - `bumpAttempts(id, attempts, nextRetryAt)` — `UPDATE SET status='pending', attempts=?,
    next_retry_at=?, updated_at=now WHERE id=?`。
  - `deferForRetry(ids, nextRetryAt)` — **新增（P1-7）**：`UPDATE SET status='pending',
    next_retry_at=? WHERE id IN (...)`（**不动 attempts**，gate OPEN 时冻结用）。
  - `markDead(id, reason)`、`selectPendingCount()` / `selectOldestCreatedAt()`（gauge 用）。

## Step 3 — OutboxConfig + BackoffSchedule 配置

`MessagingProperties` 新增：
- `OutboxConfig`（design.md §10 全字段，含 `cbLocalCacheTtlMs`/`claimingTimeoutSeconds`/
  `maxBatchesPerPoll`/`gateDeferInterval`/有界 executor 三参数/`cleanupCron`）。
- `backoffMs`（`long[]`，顶层 `app.messaging.backoff-ms`）——**由 child 1 引入并定义该配置段**；
  本任务 `OutboxRelay` 注入 `BackoffSchedule` bean 复用（design §3.3），不重复定义。
`application.yml` `app.messaging.outbox.*` 段（`backoff-ms` 由 child 1 的 yml 提供）。

**`BackoffSchedule`**（design.md §3.3 — **child 1 已引入，本任务仅注入复用**）：
- `infrastructure/messaging/BackoffSchedule.java` 由 child 1 创建（`@Component` 读 `app.messaging.backoff-ms`，
  `long next(int attempt)` 封顶最后一档）。relay 与 child 1 `RetrySweeper` 共用同一 bean。

## Step 4 — SharedCircuitBreakerGate（共享 OPEN 熔断门控 + 本地缓存）

**文件**：`infrastructure/messaging/outbox/SharedCircuitBreakerGate.java`

- `isOpen(topic)`：**2s 本地 `ConcurrentHashMap` 缓存**（design.md §3.4）。miss 时读 Redis
  `RBucket(cbSignalPrefix+topic)`；Redis 异常 → 回退 `busManagement.isCircuitBreakerOpen(topic)`
  （parent 跨 child 契约）；防御性二级回退：从 `circuitBreakerState().get(topic)` 推导
  （`"open".equals(state)`）。**评审 P2：回退只调一次**，put 后单次 return。
- `broadcastOpen(topic)` / `broadcastClosed(topic)`：**先更新本地缓存，再 try/catch 写 Redis**
  （评审 P1-6.3：Redis 挂时降级为仅本地缓存，`catch RedisException` 记 warn，不抛——不破坏 send 链路）。

**SendCircuitBreaker 改造（评审 P1-6.2 — 广播触发点契约）**：
- 构造器加 `@Nullable SharedCircuitBreakerGate gate` + `String topic`（nullable 防循环依赖）。
- `tripOpen()`（`SendCircuitBreaker.java:80`）末尾：`if (gate != null) gate.broadcastOpen(topic)`。
- `recordSuccess()` 从 `HALF_OPEN → CLOSED`（`SendCircuitBreaker.java:51-61`）末尾：
  `if (gate != null && prev == HALF_OPEN) gate.broadcastClosed(topic)`。
- 这是 child 1 `RedisStreamMessageBus` 装配点注入 gate 的冻结点（跨 child）。

**确认 `MessageBusManagement.isCircuitBreakerOpen(topic)`**：parent 契约由 child 1 新增。若 child 1
尚未落地，gate 内防御性从 `circuitBreakerState().get(topic)` 推导（不阻塞本任务）。

## Step 5 — OutboxMessageBus 装饰器

**文件**：`infrastructure/messaging/outbox/OutboxMessageBus.java`

`send(envelope)`：
1. `enabled=false` → `delegate.send(envelope)`。
2. 编码 payload → JSON。
3. **INSERT outbox 含 `hash_key`/`payload_type`/`tag`**：
   `payload_type = envelope.payload().getClass().getName()`，`tag = envelope.tag()`，
   `hash_key = envelope.hashKey()`。
4. `cbGate.isOpen(topic)` → OPEN 跳过即时投递（行留 relay）。
5. **spawn 前二次检查 gate**（命中即不提交任务，省队列位）→
   `retryExecutor.execute(() -> sendWithRetry(...))`：`immediateRetryCount` 次重试（间隔
   `immediateRetryIntervalMs`），成功 `deleteById(id)`。
6. 返回 `entryId`。

**retryExecutor（评审"错误处理"P1 — 必须有界，非虚拟线程 per-task）**：
`ThreadPoolExecutor(core=immediate-executor-core, max=immediate-executor-max,
queue=ArrayBlockingQueue(immediate-executor-queue), 拒绝=捕获后 log.debug)`，线程命名
`outbox-immediate-N`。**拒绝语义天然正确**：拒绝 = 行不投递、继续留 PG，relay 回收，不丢。
`@PreDestroy`：`shutdown() + awaitTermination(5s)`；超时丢弃队列任务安全（行留 PG）。

`subscribe/shutdown/deadLetterOperations`：委托 delegate。
`sendAsync(envelope)`（design §6.1 — **托管，非委托**）：INSERT outbox → `cbGate.isOpen` 则
`CompletableFuture.completedFuture(outboxId)`；否则 `retryExecutor.execute(() -> { sendWithRetry →
future.complete(delegateId) / 重试耗尽 future.completeExceptionally })`。复用 `send()` 的
`retryExecutor` + `sendWithRetry`，几乎无增量代码。executor 拒绝 → `completeExceptionally` + 行留 relay。
`sendAfterCommit`：默认实现走 `send()`，**显式注释**调用方不应在持有业务事务时调用（design §6）。

## Step 6 — RedissonLeadership + OutboxRelay

**文件 1**：`infrastructure/messaging/outbox/RedissonLeadership.java`（design.md §3.1）

- **volatile `leader` 标志**（唯一权威），`heldLock` 仅持锁后赋值。
- `start()`：`redisson == null` → `leader=true`（降级每实例都扫）；否则起 daemon + 命名线程
  （`outbox-relay-leader`）跑 `holdLeadership()`。
- `holdLeadership()`：`tryLock(5, -1, SECONDS)`（评审 P2，比 `lock(-1)` 行为更可预测）；
  获取后 `leader=true`；内层 `while (running) sleep(pollInterval)`；**finally 解锁**
  （`leader=false` + `unlockQuietly` 吞 `IllegalMonitorStateException`）——修正 P0-2 条件写反。
  `InterruptedException` → 恢复中断标志（`Thread.currentThread().interrupt()`）+ break。
- `isLeader()`：只查 `leader`，**不碰 `isHeldByCurrentThread()`**——修正 P0-1 线程归属错误。
- `stop()`：`running=false` + `leaderThread.interrupt()`。

> **评审"通用性"P2**：PRD 原说"复用 EtlDispatchServiceImpl 锁模式"，但该类是短命 tryLock(30s)
> + finally unlock，与 relay 持续持锁是两种语义——本组件是重写，非复用。抽出后 P0-1/P0-2 可单测。

**文件 2**：`infrastructure/messaging/outbox/OutboxRelay.java`

### 调度入口（design.md §3.2 — 异常隔离 + drain-until-empty）
- `start()`：`leadership.start()`；`scheduler.scheduleAtFixedRate(this::tryDrainIfLeader, pollInterval, pollInterval)`。
- `tryDrainIfLeader()`：
  ```
  try {
    if (leadership.isLeader()) drainUntilEmpty();
  } catch (Throwable e) {
    log.warn("drain failed, will retry next poll", e);   // P0-3：吞掉，防 scheduler 永久停摆
  }
  ```
  **关键（评审 P0-3）**：`scheduleAtFixedRate` 任务抛未捕获异常 → 后续执行被永久抑制。
  整层 try/catch，一次 DB 抖动不能杀死 relay 直到进程重启。

### drainUntilEmpty（design.md §3.2 — drain-until-empty + 熔断门控冻结 attempts）
```
for batchNo in 0..maxBatchesPerPoll-1:     // P1 性能：单 poll 内循环 claim，上限防饿死
  rows = claimBatch()                       // tx1: claimPending + markClaiming(刷新 updated_at)
  if rows.isEmpty(): break
  processBatch(rows)
```
`processBatch`：
```
deliveredIds = []
for row in rows:
  if cbGate.isOpen(row.topic): continue      // ★ P1-7：gate OPEN → 不 send 不递增 attempts
  try:
    delegate.send(rebuildEnvelope(row))
    deliveredIds.add(row.id); metrics.delivered++
  catch Throwable: bumpOrMarkDead(row)       // 真实失败 → 递增 attempts
if !deliveredIds: tx2.deleteByIds(deliveredIds)              // P4 性能：批量 DELETE
deferIds = [r.id for r in rows if cbGate.isOpen(r.topic)]
if !deferIds: tx2.deferForRetry(deferIds, now + gateDeferInterval)  // 不动 attempts
```
- `bumpOrMarkDead`：`nextAttempt = attempts+1`；`>= maxAttempts` → `markDead` + counter；
  否则 `bumpAttempts(nextAttempt, now + backoffSchedule.next(nextAttempt))` + counter。
- 指标：delivered/failed/dead/pending/oldest_age/leader_active。

### envelope 重建（design.md §4 + §5）
```java
Class<?> payloadType = Class.forName(row.payloadType());
new MessageEnvelope<>(null, row.topic(), row.tag(),
    codec.decode(row.payload(), (Class) payloadType),   // payload_type 驱动
    row.hashKey(), row.dedupKey(), row.headers(),
    row.createdAt().toEpochMilli())
```

- `stop()`：`running=false`，`leadership.stop()`，`scheduler.shutdown() + awaitTermination(5s)`
  （评审"资源释放"P2，带超时；drain 可能在 send() 阻塞，shutdown 等其完成）。

## Step 7 — 装配
**文件**：`MessagingAutoConfiguration.java`
- delegate bus（`RedisStreamMessageBus`，child 1）降为内部 bean。
- `@Bean BackoffSchedule backoffSchedule(...)`（读 `app.messaging.backoff-ms`）。
- `@Bean @Primary MessageBus outboxMessageBus(@Qualifier("redisStreamMessageBus") MessageBus delegate, outboxMapper, codec, ..., cbGate, ...)`。**构造顺序（design §6.2）**：delegate 形参用 `@Qualifier`/bean 名显式注入内部 bean，**不要字段注入 `@Autowired MessageBus`**（会注入 `@Primary` 自己 → 循环）。
- `@Bean OutboxRelay outboxRelay(..., redisson, backoffSchedule, ...)`。
- `@Bean SharedCircuitBreakerGate`（`@Nullable RedissonClient`）。
- `@Bean RedissonLeadership`（`@Nullable RedissonClient`，`@Nullable`→降级）。
- `SendCircuitBreaker` 实例化点注入 gate + topic（child 1 装配点冻结）。

## Step 8 — Publisher catch 路径改动（修正：非"零改动"）

**文件 1**：`ChatMessagePublisher.java`
- 删 `saveWithBoundedRetry`/`reportFallbackFailure`/`sleepNoThrow`/`DEFAULT_BACKOFF_MS`/`backoffMs`字段。
- `publishMessageSave` catch 改为 `counter("chat.save.publish_failed")`。

**文件 2**：`EtlDispatchServiceImpl.java`
- 删 `dispatchViaThreadPool`。
- `dispatchAsync` catch 改为 `counter("rag.etl.publish_failed")`。
- 确认 `etlIoExecutor` 是否仅用于 fallback（grep）；若是则移除依赖。

**文件 3**：`ChatUsageTracker.java`
- catch 内加 `counter("chat.usage.publish_failed")`。

**验证**：`grep -rn 'saveWithBoundedRetry\|dispatchViaThreadPool\|reportFallbackFailure\|DEFAULT_BACKOFF_MS' src/main` = 0。
## Step 9 — OutboxCleanupScheduler

`@Scheduled(cron = "${app.messaging.outbox.cleanup-cron:0 0 4 * * *}")`（cron 外部化，评审"扩展性"硬编码）删 `status='dead' AND created_at < now()-${dead-retention-days} days`，
走 `idx_outbox_dead_cleanup`（Step 1 已建）。claiming 超时行由 relay 的 `claimPending` 查询
自动回收，不归此任务。

## Step 10 — MessagingErrorCode 扩展

`OUTBOX_INSERT_FAILED(400013)`（评审 P1-5：避让 child 1 的 `400012 = STREAM_OPERATION_FAILED`）。

## Step 11 — 测试

design.md §11：
1. `OutboxMessageBusTest`（含 hash_key/payload_type/tag 列写入；有界 executor 拒绝时行留 relay）。
2. `OutboxMessageBusSendAsyncTest`（**新增 §6.1**：sendAsync 经 outbox；成功 complete(delegateId)；
   重试耗尽 completeExceptionally 但行留 relay；OPEN 立即 complete(outboxId)；executor 拒绝 completeExceptionally + 行留 relay）。
3. `RedissonLeadershipTest`（**新增**：isLeader 标志；stop 后 finally unlock ≤瞬时不等 30s；
   崩溃接管 ≤30s；Redisson null 降级；daemon + 命名）。
4. `OutboxRelayTest`（claiming 超时回收；事务外 send；退避；dead；
   **drain 异常不杀调度（P0-3）**；**drain-until-empty**；**gate OPEN 冻结 attempts（P1-7）**）。
5. `OutboxRelayLeaderTest`（持续持锁 + 崩溃接管 ≤30s）。
6. `OutboxRelayConcurrencyTest`（SKIP LOCKED 互斥；重复投递由消费端幂等兜底，文档化）。
7. `OutboxHashKeyTest`（relay 重建后 hashKey 保留）。
8. `OutboxPayloadTypeTest`（**新增 P1-4**：chat/usage/etl 三类 payload 按 payload_type 往返反序列化）。
9. `OutboxTagTest`（**新增 P1-8**：tag 经 INSERT + relay 重建后保留）。
10. `OutboxTracePropagationTest`（relay 投递 traceparent = publisher 的）。
11. `SharedCircuitBreakerGateTest`（2s 缓存 + Redis 降级只调一次；
    **broadcast try/catch 降级（P1-6.3）**；**SendCircuitBreaker→gate 联动（P1-6.2）**）。
12. `BackoffScheduleTest`（**新增**：next 封顶最后一档；配置驱动）。
13. `ChatUsageTrackerTest`/`ChatMessagePublisherTest`（counter）。

## Review Gates

- **Gate A**（Step 7 后）：app 启动，日志确认 `OutboxMessageBus` 装配 + relay 持续持锁
  （`leader_active=1`）；leader 线程名 `outbox-relay-leader` 且 daemon。
- **Gate B**（Step 8 后）：停 MQ（delegate），发 chat 消息，确认请求线程不阻塞、outbox 有 pending 行；
  **持续停 >2h（或模拟 gate 常驻 OPEN）确认 attempts 冻结、无行转 dead（P1-7 验证）**；
  起 MQ，relay 补投。traceId 验证：消费端收到的 traceparent = publisher 的（非 relay 的）。
- **Gate C**（Step 11 后）：全量 `./mvnw test` 绿。

## Rollback Points

- `APP_MESSAGING_OUTBOX_ENABLED=false` → `OutboxMessageBus` 透传 delegate。
- Redisson 不可用 → `RedissonLeadership` 退化每实例都扫、熔断退本地态。正确性不变。
- 即时投递 executor 拒绝 → 行留 relay（不丢）。
