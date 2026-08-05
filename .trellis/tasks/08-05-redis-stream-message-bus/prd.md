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
| maxDeliveryAttempts（应用层计数） | `SimpleConsumerReceiveLoop:266` Caffeine `AtomicInteger` | **attempt 随消息字段流转**（send=0→失败+1→回灌携带→handle 还原）；Caffeine 仅 metric 去抖（P0-2，不以 entry ID 为 key），超限 → DLQ |
| %DLQ% + 运维（`DeadLetterOperations`） | `RocketMQMessageBus:342` XADD `%DLQ%`；SPI 现为 `UNSUPPORTED` 桩 | **独立 `dlq:{topic}:{group}` stream**（带 MAXLEN）；`DeadLetterOperations` 真正实现（XRANGE/XADD/XLEN）——首次落地 |

## Requirements

### R1 — send()（XADD）
- `XADD stream:{prefix}{topic} * topic tag dedupKey hashKey headers payload bornTs attempt contentType`。
  send 写 `attempt=0`（P0-2：attempt 随消息字段流转）。**trim 不用固定 MAXLEN**——改 `StreamTrimTask`
  按 `XINFO` 各 group 最小 last-delivered-id 做 `XTRIM MINID ~`（P1-5，避免积压超阈值丢未投递消息）；
  `trim-threshold` 降级为 lag 告警阈值。
- 返回 stream entry ID 作为传输级 ID（替代 RocketMQ `receipt.getMessageId()`）。
- header（含 traceId）随 XADD 字段一起写入；消费侧还原。
- 复用 `MessagePayloadCodec` 编码 payload；复用 `TracePropagator`（inject 到 headers）。
- per-topic `SendCircuitBreaker`（既有，传输无关）保留——失败计数/success 计数不变。

### R2 — subscribe()（XREADGROUP 消费循环）
- `XGROUP CREATE ... MKSTREAM`（首次，`BUSYGROUP` 忽略）。
- 返回 `RedisStreamSubscription`（implements `Subscription`），`close()` 幂等关停消费线程池。
- **统一为"应用层重试"模式**（Redis 无 broker 自动重投）：消费循环读消息 → 调 handler →
  成功 XACK；可重试失败 **XACK 移出 PEL + 转入 ZSET 延迟队列**（详见 R3，**不留 PEL**——
  RetrySweeper 只扫 ZSET，留 PEL 的消息仅 PelRecoverySweeper 在 pelMinIdleMs 后才回收，会使首档退避失效）。
- 未知异常（非业务可重试白名单）直接 DLQ + 告警，不进入重试循环（避免 bug 被放大）。
- 复用 `MessageHandler` + `IdempotentHandler`（SETNX 包装，对 bus 透明）。
- consumer name = `app:{instanceId}`（便于 PEL 归属追踪；instanceId 来自 hostname/pod）。
- **pollLoop 连接级失败退避重连**（§3 Redis 故障韧性）：XREADGROUP 抛异常（Redis 宕机/主从切换/网络分区）
  时 try/catch + 指数退避（`initial-ms`=1s，`multiplier`=2，`max-ms`=30s，±20% jitter 防多实例同步重连风暴），
  成功（含空拉取）即 reset。退避 sleep 只阻塞 poll 线程自身，不影响业务线程（P1-4 独立连接池）；
  退避期间消息不丢（PEL/retry-zset 兜底）。记 `messaging.consume.connection.failure` metric。
  **不设本地降级**——Redis 是 Stream 存储，无 Redis 即无 MQ，刻意 fallback 会丢消息/双写不一致。

### R3 — 退避重试（RetrySweeper，ZSET 延迟队列）
- 失败时：**XACK 原消息** + 单 Lua 原子 `HSET retry:{stream}:{group} {retryId} payload attempt nextRetryAt` +
  `ZADD retry-zset:{stream}:{group} {now+backoff(attempt)} {retryId}`（key 维度含 group，避免多组串扰）。
- **attempt 计数随消息字段流转**（P0-2）：send 写 `attempt=0`；失败 `attempt=field+1`；
  回灌把 `attempt` 作为字段 XADD 进新 entry；handle 从字段还原累加。**不以 entry ID 为计数 key**
  （回灌生成新 ID，以 ID 为 key 会导致 attempt 永远=1，永不进 DLQ）。
  Caffeine 内存计数仅作 metric 去抖，不作正确性依据。
- `RetrySweeper`（SmartLifecycle）：单 Lua 脚本原子完成 `ZRANGEBYSCORE → ZREM 抢占 → HGET →
  XADD 回灌 → HDEL`（P1-3，中间崩溃由脚本原子性兜底，杜绝 ZREM 成功 XADD 前崩溃丢消息）。
  回灌字段含 `attempt`，写主 stream 带 trim 策略（见 R1/§2，MINID 而非 MAXLEN）。
- 单条隔离（P2-7）：HGET 返回 null（孤儿）→ ZREM 清理 + metric，单条异常不中止整批。
- `ZSetDelayQueue` 通用组件（P2-11，评审"通用性"）：抽出 `enqueue`/`drain`，RetrySweeper 注入复用，
  供 child 2 Outbox 重试等场景共用，消除 ZSET 操作内联。
- 退避表 16 级：`[1s,5s,10s,30s,1m,...,30m]`（共享 `BackoffSchedule`）。
- 多实例并发：单 Lua 内 ZREM 抢占，只一个实例回灌。
- `attempt > maxAttempts` → 不回灌，`XADD dlq:{stream}:{group} MAXLEN~trim` + counter。
- retry hash 字段设 TTL（默认 2h，覆盖最大退避窗口 ×2），防极端残留（P2-14）。

### R4 — PEL 崩溃恢复（PelRecoverySweeper）
- 实例崩溃时持消息（已 XREADGROUP 未 XACK）→ 留 PEL。
- `PelRecoverySweeper`（SmartLifecycle）：`XAUTOCLAIM {stream} {group} {consumer}
  {minIdleMs} 0 COUNT {batch}`，`minIdleMs` > 最大处理时长（ETL 30min → 配 40min）。
- 多实例并发安全（XAUTOCLAIM 原子转移归属）。
- claim 后**异步派发**到该 subscription 的 processingPool（P1-6，不在 sweeper 调度线程同步 handle，
  避免 ETL 长任务阻塞 sweeper；与 RetrySweeper 各用独立线程池）。
- 启动期断言 `pelMinIdleMs > max(各 consumer invisibleDuration) + 5min`（当前 40min vs ETL 30min，margin 10min ✓）。

### R5 — DLQ + DeadLetterOperations（首次落地）
- 死信独立 stream：`dlq:{prefix}{topic}:{group}`（key 含 group，多组不串扰）。
- `XADD dlq:{stream}:{group} MAXLEN ~ {dlq-trim-threshold} * originalTopic payload reason failedAt originGroup`
  （所有 DLQ 写入统一带 MAXLEN，P2-8，避免无界增长）。
- 实现 `DeadLetterOperations`（当前 RocketMQ 是 `UNSUPPORTED` 桩，本任务真正落地）：
  - `scanDeadLetters(topic, count)` → `XREVRANGE dlq:{stream}:{group} + - COUNT {n}` → decode。
  - `replayDeadLetter(topic, messageId)` → `XRANGE` 取原 payload → `XADD` 回主 stream（带 MINID trim 策略）。
  - `deadLetterCount(topic)` → `XLEN dlq:{stream}:{group}`。
- 多组扩展点：当前 1:1 拓扑用"该 topic 唯一 group"解析 key；未来多组需 API 扩展为带 group 参数。

### R6 — FIFO（rag_index_document）
- **bus 不做分区**——RedisStreamMessageBus 把同 topic 消息投给 group 内任一 consumer。
- per-documentId 串行由既有 `EtlDispatchServiceImpl:82-94` 的 `RLock(ETL_LOCK_PREFIX+documentId)` 保证。
- 文档此契约：bus 透传 `hashKey`（写入 stream 字段，供业务层参考），但**不据此分区**。

### R7 — MessageBusManagement 扩展
- 实现 `isCircuitBreakerOpen(String topic)`（暴露 per-topic `SendCircuitBreaker.state()`）。
- 供 child 2 的 `SharedCircuitBreakerGate` 读（跨契约冻结点）。

### R8 — 配置（直接替换，无 backend 开关）
- 新增 `app.messaging.redis.*`：
  - `stream-prefix`（默认 `stream:`）、`dlq-prefix`、`retry-prefix`、`retry-zset-prefix`、`consumer-name-prefix`（默认 `app:`）。
  - `trim-threshold`（默认 100000，**降级为 lag 告警阈值**；物理裁剪改 `StreamTrimTask` MINID，P1-5）。
  - `dlq-trim-threshold`（默认 50000，DLQ MAXLEN，P2-8）、`trim-poll-interval`（默认 60s）。
  - `read-block-ms`（默认 2000）、`read-batch`（默认 32）、`retry-poll-interval`（默认 5s）。
  - `pel-min-idle-ms`（默认 **40min**，> ETL 30min + 10min margin，P1-6/R4 启动断言）。
  - `retry-hash-ttl`（默认 2h，P2-14，覆盖最大退避窗口 ×2）。
  - `consumer.connection`（P1-4，**独立连接池**：`share-native-connection=false` + pool，避免 XREADGROUP BLOCK 占共享连接拖垮全站 Redis）。
  - `reconnect-backoff`（§3 Redis 故障韧性，pollLoop 退避重连：`initial-ms`=1s、`multiplier`=2、`max-ms`=30s、`jitter-factor`=0.2）。
- **启动期断言**（fail-fast）：`maxAttempts <= backoff-ms.size()`；`pelMinIdleMs > max(invisibleDuration)+5min`；
  `dlq-trim-threshold > 0`、`read-batch >= 1`。
  （`retry-poll-interval` 与首档退避的关系非 fail-fast：sweep 粒度 5s 下首档 1s 实际生效 ≤5s，见 design §10。）
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

- **不使用 `@Transactional`**；sweeper 的 ZSET 抢占/回灌用**单 Lua 脚本**保证原子（P1-3：ZREM+HGET+XADD+HDEL 同一 eval）。
- Redisson 仅用于 child 1 范围外的 RLock（业务层）；bus 本身用 `StringRedisTemplate` /
  `RedisTemplate` 的 Stream 操作（`org.springframework.data.redis.connection.stream.*`）。
- **消费连接隔离（P1-4，强制）**：XREADGROUP BLOCK 在独立 `LettuceConnectionFactory`（`share-native-connection=false` + pool），
  不得占用业务共享 Redis 连接。
- 错误码：新增 bus 内部异常用 `MessagingErrorCode`（400012-400014）；DLQ/retry 不抛业务异常（自治，仅记 metric）。
- Redis 持久化要求（运维）：`maxmemory-policy noeviction` 或 stream key 豁免；AOF everysec。
  （compose 配置调整归 parent 收尾；本任务代码不依赖，仅文档声明。）
- payload 三处驻留（主 stream + retry hash + dlq）内存预估见 design §10（P2-13）。

## Acceptance Criteria

- [ ] `RedisStreamMessageBus implements MessageBus`，`send()` 返回 stream entry ID。
- [ ] **attempt 计数正确性（P0-2）**：同一条消息连续失败 N 次，`attempt` 字段从 0 递增到 N，
      到 maxAttempts 进 DLQ（单测：模拟失败 N+1 次，断言 DLQ 而非无限重试）。
- [ ] **XACK 语义统一（P0-1）**：可重试失败后 PEL 无残留（XACK），消息转入 ZSET；单测断言 PEL 计数=0、ZSET 计数=1。
- [ ] **原子回灌（P1-3）**：单 Lua 脚本完成 ZREM+HGET+XADD+HDEL，多实例并发只一个回灌（Testcontainers 双 sweeper）。
- [ ] **消费连接隔离（P1-4）**：消费用独立 LettuceConnectionFactory，业务 RedisTemplate 不被 XREADGROUP BLOCK 阻塞（集成测试观测连接占用）。
- [ ] **StreamTrimTask（P1-5）**：MINID 基于最小 last-delivered-id，积压场景不丢未投递消息（Testcontainers：停 consumer 后 send N 条超阈值，重启 consumer 全部投递）。
- [ ] **PelRecoverySweeper 异步派发（P1-6）**：claim 后异步处理，sweeper 线程不被长任务阻塞。
- [ ] **DLQ MAXLEN（P2-8）**：写入超 `dlq-trim-threshold` 后旧条目被裁剪，XLEN ≤ 阈值。
- [ ] **retry key 含 group（P2-10）**：retry-zset/retry-hash/dlq key 形如 `*:{topic}:{group}`。
- [ ] **SmartLifecycle 关闭顺序（P2-9）**：sweeper 先于 consumer pool 关闭，`close()` 幂等，`awaitTermination` 带超时。
- [ ] **通用组件**：`RedisStreamKeys`/`BackoffSchedule`/`ZSetDelayQueue` 单测通过，RetrySweeper 注入复用。
- [ ] **启动期断言**：违反 `maxAttempts<=backoff.size`/`pelMinIdleMs>ETL+5min`/`retry-poll<min-backoff` 时启动失败。
- [ ] `subscribe()` 返回 `RedisStreamSubscription`，`close()` 幂等关停线程池。
- [ ] send → XREADGROUP → handler → XACK 正常路径跑通（单测 + Testcontainers Redis）。
- [ ] RetrySweeper：失败消息按 16 级退避回灌主 stream；maxAttempts 耗尽进 DLQ（单测）。
- [ ] PelRecoverySweeper：模拟 consumer 崩溃（未 XACK），40min idle 后 XAUTOCLAIM 回收（Testcontainers）。
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
      `PelRecoverySweeperTest`、`RedisStreamDeadLetterOperationsTest`、`BackoffScheduleTest`、
      `ZSetDelayQueueTest`、`StreamTrimTaskTest`、`RedisStreamKeysTest`。

## Out of Scope

- Outbox 装饰器（child 2）。
- `docker-compose` 移除 RocketMQ 容器 + Redis 持久化策略调整（归 parent 收尾：删 rmqnamesrv/rmqbroker/rmqdashboard，Redis `noeviction`）。
