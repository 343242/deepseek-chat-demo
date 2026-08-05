# RedisStreamMessageBus — Redis Stream 实现 MessageBus SPI

> Parent: `08-05-redis-stream-mq-migration`

## Goal

新增 `RedisStreamMessageBus` 实现 `MessageBus` SPI，用 Redis 8 Stream（XADD/XREADGROUP/XACK/
XAUTOCLAIM）取代 `RocketMQMessageBus`。对三个 topic 提供**等价或更强**的可靠性语义：
FIFO 有序（业务层 RLock）、退避重试（ZSET 延迟队列）、maxDeliveryAttempts（应用层计数）、
DLQ（独立 stream）。

> 不在本任务范围：Outbox 装饰器（child 2）。
> **本任务直接替换 RocketMQ**——删除 `RocketMQMessageBus`/`PushConsumerListener`/
> `SimpleConsumerReceiveLoop`/`RocketMQSubscription`/`PushSubscription`/`SimpleSubscription`，
> 移除 `pom.xml` 的 `rocketmq-client-java`、`app.messaging.rocketmq.*` 配置段、
> `scripts/init-rocketmq-topics.sh`。无灰度开关、无两实现并存。
> 交付后 app 仅靠 RedisStreamMessageBus 跑通三个 topic。

## 背景 — RocketMQ 三特性在 Redis 下的等价物

依据前置分析（已核实代码），逐特性映射：

| RocketMQ 特性 | 当前用法 | Redis Stream 等价物 |
|--------------|---------|---------------------|
| messageGroup FIFO 有序（`rag_index_document`） | `setMessageGroup(documentId)` → broker 独占队列 | **业务层 `RLock`**（`EtlDispatchServiceImpl:82-94` 已存在）；bus 不做分区，consumer 持锁串行 |
| broker 退避重试（chat_save Push 模式，18 级） | 抛 `ConsumeResult.FAILURE` → broker 重投 | **ZSET 延迟队列 sweeper**（失败 XACK + ZADD retry-zset，sweeper 到期回灌主 stream） |
| maxDeliveryAttempts（应用层计数） | `SimpleConsumerReceiveLoop:266` Caffeine `AtomicInteger` | **沿用 Caffeine 计数**（per msgId），超限 → DLQ |
| %DLQ% + 运维（`DeadLetterOperations`） | `RocketMQMessageBus:342` XADD `%DLQ%`；SPI 现为 `UNSUPPORTED` 桩 | **独立 `dlq:{topic}` stream**；`DeadLetterOperations` 真正实现（XRANGE/XADD/XLEN）——首次落地 |

## Requirements

### R1 — send()（XADD）
- `XADD stream:{prefix}{topic} MAXLEN ~ {trim} * topic tag dedupKey hashKey headers payload bornTs`。
- 返回 stream entry ID 作为传输级 ID（替代 RocketMQ `receipt.getMessageId()`）。
- header（含 traceId）随 XADD 字段一起写入；消费侧还原。
- 复用 `MessagePayloadCodec` 编码 payload；复用 `TracePropagator`（inject 到 headers）。
- per-topic `SendCircuitBreaker`（既有，传输无关）保留——失败计数/success 计数不变。

### R2 — subscribe()（XREADGROUP 消费循环）
- `XGROUP CREATE ... MKSTREAM`（首次，`BUSYGROUP` 忽略）。
- 返回 `RedisStreamSubscription`（implements `Subscription`），`close()` 幂等关停消费线程池。
- **统一为"应用层重试"模式**（Redis 无 broker 自动重投）：消费循环读消息 → 调 handler →
  成功 XACK；失败留 PEL（不 XACK），由 RetrySweeper 退避重投。
- 复用 `MessageHandler` + `IdempotentHandler`（SETNX 包装，对 bus 透明）。
- consumer name = `app:{instanceId}`（便于 PEL 归属追踪；instanceId 来自 hostname/pod）。

### R3 — 退避重试（RetrySweeper，ZSET 延迟队列）
- 失败时：**XACK 原消息** + `HSET retry:{stream} {msgId} payload attempt nextRetryAt` +
  `ZADD retry-zset:{stream} {now+backoff(attempt)} {msgId}`。
- `RetrySweeper`（SmartLifecycle）：定时 `ZRANGEBYSCORE retry-zset:{stream} 0 now LIMIT batch` →
  Lua 原子抢占（ZREM 成功者）→ `XADD stream:{stream} * <原字段>` 回灌主 stream + `HDEL retry:{stream} {msgId}`。
- 退避表对齐 RocketMQ 18 级（取 16 级）：`[1s,5s,10s,30s,1m,...,30m]`。
- 多实例并发：ZREM 原子保证每条只被一个实例回灌。
- `attempt >= maxDeliveryAttempts` → 不回灌，`XADD dlq:{stream}` + `HDEL` + counter。

### R4 — PEL 崩溃恢复（PelRecoverySweeper）
- 实例崩溃时持消息（已 XREADGROUP 未 XACK）→ 留 PEL。
- `PelRecoverySweeper`（SmartLifecycle）：`XAUTOCLAIM {stream} {group} {consumer}
  {minIdleMs} 0 COUNT {batch}`，`minIdleMs` > 最大处理时长（ETL 30min → 配 35min）。
- 多实例并发安全（XAUTOCLAIM 原子转移归属）。
- 取回的消息走正常 handler → 成功 XACK / 失败进 RetrySweeper。

### R5 — DLQ + DeadLetterOperations（首次落地）
- 死信独立 stream：`dlq:{prefix}{topic}`。
- `XADD dlq:{stream} * originalTopic payload reason failedAt`。
- 实现 `DeadLetterOperations`（当前 RocketMQ 是 `UNSUPPORTED` 桩，本任务真正落地）：
  - `scanDeadLetters(topic, count)` → `XREVRANGE dlq:{stream} + - COUNT {n}` → decode。
  - `replayDeadLetter(topic, messageId)` → `XRANGE` 取原 payload → `XADD` 回主 stream。
  - `deadLetterCount(topic)` → `XLEN dlq:{stream}`。

### R6 — FIFO（rag_index_document）
- **bus 不做分区**——RedisStreamMessageBus 把同 topic 消息投给 group 内任一 consumer。
- per-documentId 串行由既有 `EtlDispatchServiceImpl:82-94` 的 `RLock(ETL_LOCK_PREFIX+documentId)` 保证。
- 文档此契约：bus 透传 `hashKey`（写入 stream 字段，供业务层参考），但**不据此分区**。

### R7 — MessageBusManagement 扩展
- 实现 `isCircuitBreakerOpen(String topic)`（暴露 per-topic `SendCircuitBreaker.state()`）。
- 供 child 2 的 `SharedCircuitBreakerGate` 读（跨契约冻结点）。

### R8 — 配置（直接替换，无 backend 开关）
- 新增 `app.messaging.redis.*`：
  - `stream-prefix`（默认 `stream:`）、`dlq-prefix`、`retry-prefix`、`retry-zset-prefix`。
  - `trim-threshold`（默认 100000）、`read-block-ms`（默认 2000）、`read-batch`（默认 32）。
  - `pel-min-idle-ms`（默认 35min）、`retry-poll-interval`（默认 5s）。
- **BackoffSchedule（共享组件，评审"通用性"P1）**：新增 `app.messaging.backoff-ms`（`long[]`，
  16 级默认值）+ `infrastructure/messaging/BackoffSchedule.java`（`@Component`，`next(attempt)`）。
  `RetrySweeper` 注入复用，**child 2 OutboxRelay 同 bean 复用**，退避策略单点配置（消除多份硬编码）。
- **命令超时**：依赖既有全局 `spring.data.redis.timeout: 3000ms`（`application-dev/stable.yml`）；
  `send()`/`XADD` 单次 ≤3s，child 2 relay 顺序 send 单 batch 有界（32×3s）。
- **SendCircuitBreaker 跨 child 冻结点（评审 P1-6.2）**：本任务保持 `SendCircuitBreaker` 不变；
  child 2 扩展其构造器加 `@Nullable SharedCircuitBreakerGate`+`topic`，在 `tripOpen()`/状态迁移点
  广播（gate=null no-op）。`circuitBreakerFor(topic)` 实例化点须可被 child 2 装配注入。
- **不引入** `app.messaging.backend` 枚举——`RedisStreamMessageBus` 是唯一实现，无选择路径。

### R9 — 删除 RocketMQ 实现（直接替换，非 parent 收尾）
本任务删除以下全部 RocketMQ 代码/配置（grep = 0）：
- 类：`RocketMQMessageBus`、`PushConsumerListener`、`SimpleConsumerReceiveLoop`、
  `RocketMQSubscription`、`PushSubscription`、`SimpleSubscription`、`OpenTelemetryTracePropagator`（若仅 RocketMQ 用）。
- `pom.xml`：移除 `org.apache.rocketmq:rocketmq-client-java` 依赖。
- `application.yml`：移除 `app.messaging.rocketmq.*` 段、`ROCKETMQ_ENDPOINTS` 等 env 覆盖。
- `scripts/init-rocketmq-topics.sh`：删除。
- `MessagingAutoConfiguration`：移除 `rocketmqClientServiceProvider` bean。

## Constraints

- **不使用 `@Transactional`**；sweeper 的 ZSET 抢占用 Lua 脚本保证原子。
- Redisson 仅用于 child 1 范围外的 RLock（业务层）；bus 本身用 `StringRedisTemplate` /
  `RedisTemplate` 的 Stream 操作（`org.springframework.data.redis.connection.stream.*`）。
- 错误码：新增 bus 内部异常用 `MessagingErrorCode`（400012 起）；DLQ/retry 不抛业务异常（自治）。
- Redis 持久化要求（运维）：`maxmemory-policy noeviction` 或 stream key 豁免；AOF everysec。
  （compose 配置调整归 parent 收尾；本任务代码不依赖，仅文档声明。）

## Acceptance Criteria

- [ ] `RedisStreamMessageBus implements MessageBus`，`send()` 返回 stream entry ID。
- [ ] `subscribe()` 返回 `RedisStreamSubscription`，`close()` 幂等关停线程池。
- [ ] send → XREADGROUP → handler → XACK 正常路径跑通（单测 + Testcontainers Redis）。
- [ ] RetrySweeper：失败消息按 16 级退避回灌主 stream；maxAttempts 耗尽进 DLQ（单测）。
- [ ] PelRecoverySweeper：模拟 consumer 崩溃（未 XACK），35min idle 后 XAUTOCLAIM 回收（Testcontainers）。
- [ ] `DeadLetterOperations` 三方法（scan/replay/count）真正实现，单测覆盖。
- [ ] rag_index_document FIFO：同 documentId 两消息并发，`EtlDispatchServiceImpl` RLock 串行（集成测试）。
- [ ] `MessageBusManagement.isCircuitBreakerOpen(topic)` 实现，供 child 2 用。
- [ ] 既有 `IdempotentHandler` SETNX 包装对 RedisStreamMessageBus 透明复用（测试）。
- [ ] `app.messaging.redis.*` 配置生效；`application.yml` 不再含 `rocketmq.*`。
- [ ] **RocketMQ 全部移除**：`grep -rn 'rocketmq\|RocketMQ' src/main pom.xml scripts/` = 0
      （`RocketMQMessageBus`/`PushConsumerListener`/`SimpleConsumerReceiveLoop`/`*Subscription` 类删除，
      `rocketmq-client-java` 依赖移除，`init-rocketmq-topics.sh` 删除，`rocketmqClientServiceProvider` bean 移除）。
- [ ] `RocketMQMessageBusTest`/`PushConsumerListenerTest`/`SimpleConsumerReceiveLoopTest` 删除（随实现移除）。
- [ ] 单测全绿：`RedisStreamMessageBusTest`、`RedisStreamConsumerRunnerTest`、`RetrySweeperTest`、
      `PelRecoverySweeperTest`、`RedisStreamDeadLetterOperationsTest`。

## Out of Scope

- Outbox 装饰器（child 2）。
- `docker-compose` 移除 RocketMQ 容器 + Redis 持久化策略调整（归 parent 收尾：删 rmqnamesrv/rmqbroker/rmqdashboard，Redis `noeviction`）。
