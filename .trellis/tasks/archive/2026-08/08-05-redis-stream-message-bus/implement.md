# Implementation Plan — RedisStreamMessageBus

> 前置阅读：`prd.md`、`design.md`（含 §0 修订记录的全部 P0/P1/P2 修订）。
> 自下而上：配置 → send → subscribe/消费循环 → sweeper → DLQ → 装配 → 测试。
>
> **与 design/prd 的一致性**：本计划已对齐评审修订后的 design（基线 `a85450b` → 修订 `1a9e9fb`）。
> 每步标注对应 design 章节与修订 ID（P0-x/P1-x/P2-x）。冻结点（traceparent / 命令超时 / SendCircuitBreaker / 错误码段）逐字遵循。

## Step 1 — MessagingProperties（直接替换，无 backend 开关）

**文件**：`infrastructure/messaging/MessagingProperties.java`

- **删除** `RocketMQConfig` record（endpoints/producerGroup/maxDeliveryAttempts/maxMessageSize/enableSsl/accessKey/secretKey）。
- **新增** `RedisStreamConfig` record（design §10 全字段）：
  - prefix：`stream-prefix`/`dlq-prefix`/`retry-prefix`/`retry-zset-prefix`（默认 `stream:`/`dlq:`/`retry:`/`retry-zset:`）。
  - `consumer-name-prefix`（默认 `app:`，design §1）。
  - `trim-threshold`（默认 100000，**P1-5：降级为 lag 告警阈值**，非物理裁剪上限）。
  - `dlq-trim-threshold`（默认 50000，P2-8 DLQ MAXLEN）。
  - `read-block-ms`（默认 2000）、`read-batch`（默认 32）。
  - `pel-min-idle-ms`（默认 **2400000 = 40min**，> ETL 30min + 10min margin，design §10 / prd R4）。
  - `retry-poll-interval`（默认 5s）、`trim-poll-interval`（默认 60s，P1-5 StreamTrimTask 周期）。
  - `max-attempts`（默认 16）。
  - `retry-hash-ttl`（默认 2h，P2-14）。
  - `reconnect-backoff`（P3 §3 Redis 故障韧性：`initial-ms`=1000、`multiplier`=2.0、`max-ms`=30000、`jitter-factor`=0.2）。
  - `consumer.connection`（P1-4：`share-native-connection`=false + `pool.max-active/max-idle`）。
- **顶层新增** `app.messaging.backoff-ms`（`long[]`，16 级默认值，BackoffSchedule 共享，design §4.4）。
- **不引入** `Backend` 枚举——无选择路径（prd R8）。
- **启动期断言（design §10）**：`MessagingProperties` 校验（失败即启动失败）：
  1. `maxAttempts <= backoff-ms.size()`（16<=16）。
  2. `pelMinIdleMs > max(各 consumer invisibleDuration) + 5min`（40min > 30min+5min ✓）。
  3. `dlq-trim-threshold > 0`、`read-batch >= 1`。
  （`retry-poll-interval` 与首档退避**非 fail-fast**：sweep 粒度 5s 下首档 1s 实际生效 ≤5s，可接受精度，见 design §10。）
- **文件**：`application.yml`：删除 `app.messaging.rocketmq.*` 段，新增 `app.messaging.redis.*` 段。

## Step 2 — RedisStreamMessageBus.send()

**文件**：`infrastructure/messaging/redis/RedisStreamMessageBus.java`

构造注入：`MessagingProperties`、`RedisTemplate<String,String>`、`MessagePayloadCodec`、`MessageValidator`、`@Nullable TracePropagator`、`@Nullable MeterRegistry`、`RedisStreamKeys`（design §1 通用组件）、`@Nullable SharedCircuitBreakerGate`（child 2 注入点，本任务 nullable no-op——见冻结点）。

字段：
- `StreamOperations<String,Object,Object> streamOps`（**P1-4：用独立 LettuceConnectionFactory 的 RedisTemplate**，见 Step 9）。
- `Map<String,SendCircuitBreaker> cbs`（per-topic，`circuitBreakerFor(topic)`，复用既有类；**实例化点须可被 child 2 注入 gate——design §2 冻结点**）。

`send(envelope)`（design §2）：
- `cb.isCallAllowed()` → OPEN 抛 `MessagePublishException`。
- **traceparent（冻结点，design §2）**：`headersMap = new HashMap<>(envelope.headers())`；
  `if (!headersMap.containsKey("traceparent")) headersMap.putAll(propagator.inject())`——relay 投递时不覆盖存储的 traceparent。
- **命令超时（冻结点）**：依赖既有全局 `spring.data.redis.timeout: 3000ms`（非新增）；`send()` 单次 ≤3s。
- **P0-2**：record 含 `attempt=0` 字段（随消息流转，供消费端 attempt 计数累加）。
- **P1-5（trim 策略变更）**：`streamOps.add(StreamOffset.create(streamKey), record)` **不带 MAXLEN**——
  主 stream 物理裁剪由独立 `StreamTrimTask` 按 `XINFO` 最小 last-delivered-id 做 `XTRIM MINID ~`（design §2 / Step 9）。
  `trim-threshold` 仅作 lag 告警阈值（Step 9 StreamTrimTask 内判断）。
- 成功 `cb.recordSuccess` + 返回 `recordId.getValue()`；失败 `cb.recordFailure` + 抛异常。

**注意**：Spring Data Redis `StreamOperations.add` 签名；先 grep 确认版本支持 `org.springframework.data.redis.core.stream.StreamOperations`。

## Step 3 — RedisStreamSubscription + RedisStreamConsumerRunner

**文件**：
- `infrastructure/messaging/redis/RedisStreamSubscription.java`（implements `Subscription`）：
  持 `RedisStreamConsumerRunner`、`AtomicBoolean closed`；`close()` 幂等 → `runner.stop()`。
- `infrastructure/messaging/redis/RedisStreamConsumerRunner.java`：
  - `start()`：`ensureGroup()`（`streamOps.createGroup`，`BUSYGROUP` 吞；非 BUSYGROUP 失败记 `messaging.stream.group.create.failed` metric，design §9）。
  - 按 `config.consumerMode()` 起线程（PUSH=concurrency 线程池；SIMPLE=receive+processingPool+Semaphore），结构镜像 `SimpleConsumerReceiveLoop`。
  - **P1-4（独立连接）**：XREADGROUP/XACK 用独立 LettuceConnectionFactory 的 streamOps（Step 9），不占业务共享连接。
  - `pollLoop()`（design §3，含 consume 退避重连）：
    ```
    reconnectBackoff = new ReconnectBackoff(initial, multiplier, max, jitterFactor)  // 来自 reconnect-backoff 配置
    while running:
      try:
        messages = streamOps.read(XREADGROUP GROUP consumer COUNT batch BLOCK blockMs ">")
        reconnectBackoff.reset()           // 成功即重置（空拉取也算）
        for msg: dispatch(msg)              // PUSH→executor / SIMPLE→semaphore+pool
      catch Exception e:                    // 连接级失败
        metrics.recordConsumeConnectionFailure(topic)   // design §9
        if !running: break
        sleep = reconnectBackoff.nextSleep()             // 1s→2s→…→30s ±20% jitter
        Thread.sleep(sleep)
    ```
    > 关键：原评审前伪代码无 try/catch，XREADGROUP 失败线程静默退出——已修（design §3 Redis 故障韧性）。
  - **handle(msg)（design §3，P0-1/P0-2 修订）**：
    ```
    envelope = decode(record)               // 还原 payload + headers + propagator.restore + attempt 字段
    try:
      idempotentWrappedHandler.onMessage(envelope)
      xack(stream, group, record.id)        // 成功 → XACK
      metrics.recordConsumeSuccess(...)
    catch PermanentConsumeException:
      xack + xadd(dlqKey, MAXLEN~dlqTrim, ...original..., reason, originGroup)  // P2-8 带 MAXLEN
      metrics.recordDeadLetter(...)
    catch RetryableConsumeException | 业务可重试白名单:
      retrySweeper.routeToRetry(streamKey, group, record, ...)   // P0-1：XACK+ZSET，非留 PEL（见 Step 5）
    catch Exception:                        // 未知异常 → 直接 DLQ + 告警（design §3）
      xack + xadd(dlqKey, MAXLEN~dlqTrim, ...original..., reason="UNKNOWN_UNRETRYABLE")
      metrics.recordUnknownFailure(topic)
    ```
  - `stop()`：关线程池，`awaitTermination(shutdownTimeout)`。

**复用**：`IdempotentHandler.wrap(handler, topic, redisTemplate, ttl, metrics)`（既有，bus 透明）。
**P0-2 修订**：**不再以 entry ID 作 Caffeine 计数 key**——attempt 从消息字段还原（见 Step 5）。Caffeine 仅作 metric 去抖。

## Step 4 — subscribe()

**文件**：`RedisStreamMessageBus.subscribe()`：
- 构造 `RedisStreamConsumerRunner` → `start()` → 包进 `RedisStreamSubscription` 返回。
- `MessageHandler` 经 `IdempotentHandler.wrap()` 包装（与 RocketMQ 一致）。
- subscribe 时向 RetrySweeper/PelRecoverySweeper/StreamTrimTask `register(streamKey, group, ...)`（design §4.6）。

## Step 5 — RetrySweeper（ZSET 退避，P0-2/P1-3/P2-7/P2-11/P2-12/P2-14 修订）

**文件**：`infrastructure/messaging/redis/RetrySweeper.java`

`implements SmartLifecycle`（phase 早期，`DEFAULT_PHASE - 200`，design §7 P2-9）。
构造注入：`ZSetDelayQueue`（P2-11 通用组件）、`RedisTemplate`、`RedisStreamKeys`、`BackoffSchedule`、`MessagingProperties`、`@Nullable MeterRegistry`、活跃 `RedisStreamConsumerRunner` 引用（用于回灌后 dispatch）。

字段：per-(stream,group) 的 zsetKey/hashKey（**P2-10：key 含 group**，经 `RedisStreamKeys` 解析）。退避经 `backoffSchedule.next(attempt)`（非硬编码）。

- `routeToRetry(streamKey, group, record, ...)`（design §4.1 失败分支，handle 调用）：
  ```
  attempt = parseInt(record.field("attempt", "0")) + 1   // P0-2：从字段还原，非 entry ID
  if attempt > maxAttempts:
    xack(stream, group, record.id)
    xadd(dlqKey, MAXLEN~dlqTrim, ...original..., reason="RETRY_EXHAUSTED", originGroup)   // P2-8
    metrics.recordDeadLetter(...)
    return
  retryId = uuid()
  // P2-14：HSET+ZADD 单 Lua 原子（杜绝 hash 孤儿）
  zSetDelayQueue.enqueue(retryZsetKey, retryHashKey, retryId,
      {payload, attempt, originalFields, bornTs}, nowMs + backoffSchedule.next(attempt), retryHashTtl)
  xack(stream, group, record.id)          // P0-1：移出 PEL
  metrics.recordRetry(topic, group, attempt)
  ```
- `drain()`（design §4.2，P1-3 单 Lua 原子 + P2-12 批量）：
  ```
  scheduler.scheduleAtFixedRate(this::drain, retryPollInterval, retryPollInterval)
  // 对每个活跃 (stream,group)：
  zSetDelayQueue.drain(retryZsetKey, retryHashKey, batch, payload -> {
    // P1-3：单 Lua 内完成 ZRANGEBYSCORE → ZREM 抢占 → HGET → XADD 回灌（含 attempt 字段）→ HDEL
    // P0-2：回灌字段含 attempt（随回灌携带）
    // P2-7：HGET null（孤儿）→ ZREM 清理 + recordRetryOrphan metric，单条异常不中止整批
    runner.dispatchToProcessingPool(() -> handle(reinjectedRecord))   // 回灌后正常消费
  })
  ```
- `register(streamKey, group, retryZsetKey, retryHashKey, runner)`：subscribe 时调。
- `stop()`：关 scheduler。
- 指标：`retry.redelivered`/`retry.orphan.count`（design §9）。

> **与评审前的关键差异**：① attempt 从消息字段还原（P0-2），不再 Caffeine entry-ID 计数；② 回灌单 Lua 原子（P1-3），非逐条 ZREM/HGET/XADD/HDEL 四步；③ ZSetDelayQueue 抽出（P2-11）。

## Step 5b — ZSetDelayQueue（P2-11 通用组件，新增）

**文件**：`infrastructure/messaging/ZSetDelayQueue.java`（`@Component`，design §4.5）

- `enqueue(zsetKey, hashKey, id, payload, dueAtMs, hashTtl)`：单 Lua `HSET + EXPIRE + ZADD`（P2-14 原子，hash TTL 防残留）。
- `drain(zsetKey, hashKey, batch, Consumer<Payload> onDue)`：单 Lua `ZRANGEBYSCORE → ZREM 抢占 → HGET → HDEL`（P1-3 原子），回灌/消费由回调 `onDue` 负责。
- 复用方：RetrySweeper（回灌 stream）、child 2 OutboxRelay（延迟重试）。

## Step 5c — StreamTrimTask（P1-5，新增）

**文件**：`infrastructure/messaging/redis/StreamTrimTask.java`（`implements SmartLifecycle`，design §2）

- `scheduler.scheduleAtFixedRate(this::trim, trimPollInterval, trimPollInterval)`（默认 60s）。
- `trim()`：对每个活跃 stream：
  - `XINFO GROUPS streamKey` 取各组 `last-delivered-id`，取**最小值**作为 trim cursor。
  - `XTRIM streamKey MINID ~ {minLastDeliveredId}`（只裁剪各组都已读过的 entry，不丢未投递消息）。
  - lag 告警：`XLEN − Σ各组 XPENDING` > `trim-threshold` → `messaging.stream.trim.threshold.exceeded` counter + log.warn（design §9）。
- 降级：Redis < 6.2 不支持 MINID → `MAXLEN ~ {大值}` + 显式风险声明（仅单 group 安全）。

## Step 6 — PelRecoverySweeper（P1-6 异步派发修订）

**文件**：`infrastructure/messaging/redis/PelRecoverySweeper.java`

`implements SmartLifecycle`（phase 早期，与 RetrySweeper 同 phase 但**独立线程池**——design §5 P1-6）。
构造注入：`RedisTemplate`、`RedisStreamKeys`、`MessagingProperties`、活跃 `RedisStreamConsumerRunner` 引用。

- `drain()`：对每个活跃 (stream,group)：
  - `xautoclaim(streamKey, group, selfConsumer, pelMinIdleMs, "0", batch)`
    （`streamOps.claim` 或 `RedisCallback` 原生 XAUTOCLAIM；先验证 Spring Data Redis API）。
  - **P1-6（异步派发）**：`for record in claimed: runner.dispatchToProcessingPool(() -> handle(record))`
    ——不在 sweeper 调度线程同步 handle（ETL 长任务会阻塞 sweeper）。
- `selfConsumer` = `{consumer-name-prefix}{instanceId}`。
- `stop()`：关独立 scheduler。

## Step 7 — RedisStreamDeadLetterOperations（P2-8/P2-10 修订）

**文件**：`infrastructure/messaging/redis/RedisStreamDeadLetterOperations.java`（implements `DeadLetterOperations`，design §6）

构造注入：`RedisTemplate`、`MessagePayloadCodec`、`RedisStreamKeys`、`MessagingProperties`。
- `scanDeadLetters(topic, count)`：dlqKey 经 `RedisStreamKeys.dlqKey(topic, group)`（**P2-10：含 group**）；
  当前 1:1 拓扑用"该 topic 唯一 group"解析。`xrevrange(dlqKey, +, -, count)` → decode。
- `replayDeadLetter(topic, messageId)`：`xrange` 取字段 → `xadd(streamKey, *, fields)`（回灌带 trim 策略，由 StreamTrimTask 管理，不在 XADD 内联）。
- `deadLetterCount(topic)`：`xlen(dlqKey)`。
- **所有 DLQ 写入统一带 `MAXLEN ~ dlq-trim-threshold`**（P2-8，handle/retry 耗尽/replay 三处）。
- **多组扩展点**（design §6）：私有重载 `scanDeadLetters(topic, group, count)`，public API 暂用单 group，加注释标记扩展点。

`RedisStreamMessageBus.deadLetterOperations()` 返回此实例（非 UNSUPPORTED）。

## Step 8 — MessageBusManagement 扩展（child 2 契约）

**文件**：`MessageBusManagement.java` 加 `boolean isCircuitBreakerOpen(String topic)`。
`RedisStreamMessageBus` 实现：`circuitBreakerFor(topic).state() == OPEN`（design §7）。
既有 `Map<String,String> circuitBreakerState()`（topic→state 名）保留，作为 child 2 防御性二级回退。
（契约冻结点：供 child 2 的 `SharedCircuitBreakerGate` 读——design §7。）

## Step 9 — 装配（直接替换，删除 RocketMQ）

**文件**：`MessagingAutoConfiguration.java`

```java
// 删除：rocketmqClientServiceProvider() bean、rocketMQMessageBus() bean

// P1-4：独立消费连接工厂（XREADGROUP/XACK 专用，不占业务共享连接）
@Bean
LettuceConnectionFactory redisStreamConsumerConnectionFactory(RedisProperties redisProps) {
    LettuceConnectionFactory f = new LettuceConnectionFactory(/* standalone config */);
    f.setShareNativeConnection(false);              // design §3 P1-4
    LettucePoolingClientConfiguration pool = LettucePoolingClientConfiguration.builder()
        .pool(new GenericObjectPoolConfig<>())      // max-active ≥ Σ 各 topic concurrency
        .build();
    f.setClientConfiguration(pool);
    return f;
}
@Bean
StringRedisTemplate redisStreamConsumerTemplate(LettuceConnectionFactory f) {
    return new StringRedisTemplate(f);              // 仅 StreamOperations 用
}

@Bean
RedisStreamKeys redisStreamKeys(MessagingProperties props) { return new RedisStreamKeys(props); }   // §1 通用组件
@Bean
BackoffSchedule backoffSchedule(MessagingProperties props) { return new BackoffSchedule(props.getBackoffMs()); }  // §4.4；child 2 relay 复用
@Bean
ZSetDelayQueue zSetDelayQueue(RedisTemplate rt) { return new ZSetDelayQueue(rt); }   // §4.5 P2-11；child 2 复用

@Bean(destroyMethod = "shutdown")
RedisStreamMessageBus redisStreamMessageBus(MessagingProperties props,
        @Qualifier("redisStreamConsumerTemplate") StringRedisTemplate consumerTemplate,  // P1-4
        RedisTemplate businessRedis, MessagePayloadCodec codec, MessageValidator validator,
        @Autowired(required=false) TracePropagator propagator,
        @Autowired(required=false) MeterRegistry registry,
        RedisStreamKeys keys) {
    return new RedisStreamMessageBus(props, consumerTemplate, businessRedis, codec, validator, propagator, registry, keys);
}
@Bean RetrySweeper retrySweeper(...) { ... }         // SmartLifecycle phase=DEFAULT-200
@Bean PelRecoverySweeper pelRecoverySweeper(...) { ... }  // SmartLifecycle phase=DEFAULT-200，独立线程池
@Bean StreamTrimTask streamTrimTask(...) { ... }    // SmartLifecycle，P1-5
@Bean RedisStreamDeadLetterOperations redisStreamDeadLetterOperations(...) { ... }
```

**P2-9 SmartLifecycle phase 顺序**（design §7）：
| 组件 | phase | stop 动作 |
|------|-------|-----------|
| RetrySweeper / PelRecoverySweeper | `DEFAULT_PHASE - 200` | 停调度 |
| RedisStreamConsumerRunner（各 subscription） | `DEFAULT_PHASE - 100` | `running=false`→唤醒 BLOCK→`shutdownNow` pool→`awaitTermination`（30s） |
| RedisStreamMessageBus bean（destroyMethod=shutdown） | bean destroy | 关消费连接工厂、清理 subscription |

**删除 RocketMQ 全部实现类**（grep = 0，prd R9）：
- `RocketMQMessageBus.java`/`PushConsumerListener.java`/`SimpleConsumerReceiveLoop.java`/`RocketMQSubscription.java`/`PushSubscription.java`/`SimpleSubscription.java`。
- `OpenTelemetryTracePropagator` 若仅被 RocketMQ 用则一并删（先 grep 确认）。
- 测试：`RocketMQMessageBusTest`/`PushConsumerListenerTest`/`SimpleConsumerReceiveLoopTest` 随实现删除。

**pom.xml**：移除 `org.apache.rocketmq:rocketmq-client-java`（及 testcontainers-rocketmq 若有）。
**scripts/init-rocketmq-topics.sh**：删除。
三 consumer（`ChatMessageSaveConsumer`/`UsageRecordConsumer`/`EtlDocumentConsumer`）注入 `MessageBus`，零改动。

## Step 10 — MessagingErrorCode 扩展（码段冻结）

`MessagingErrorCode.java` 新增 `STREAM_OPERATION_FAILED(400012, "消息流操作失败")`。
> **400013 预留给 child 2 `OUTBOX_INSERT_FAILED`**（跨任务冻结点，design §9）。GROUP_CREATE/STREAM_TRIM 失败改记 metric，不占码段。

## Step 11 — 测试（design §11）

1. `RedisStreamMessageBusTest`（单测，mock streamOps）：send 返回 entry ID；XADD 写入正确字段（含 `attempt=0`）；熔断 OPEN 抛异常。
2. `RedisStreamConsumerRunnerTest`（单测 + Testcontainers）：
   - XREADGROUP→handle→XACK；PermanentConsume→DLQ（带 MAXLEN）；
   - **可重试→XACK+ZSET（P0-1，非留 PEL）**；
   - 未知异常→DLQ；
   - **pollLoop 失败后指数退避重连（成功即 reset）；多 consumer 退避不同步（jitter）；退避期不丢 PEL/retry（Testcontainers redis pause/resume）**。
3. `RetrySweeperTest`（Testcontainers）：
   - 失败转 ZSET；退避计算；**attempt 字段跨回灌累加（P0-2）**；
   - maxAttempts→DLQ；**单 Lua 原子回灌（P1-3）**；**HGET null 孤儿清理（P2-7）**。
4. `PelRecoverySweeperTest`（Testcontainers，短 minIdle 加速）：**异步派发（P1-6）**。
5. `RedisStreamDeadLetterOperationsTest`：scan/replay/count；**DLQ MAXLEN 生效（P2-8）**；多组扩展点。
6. `BackoffScheduleTest`：next(attempt) 封顶最后一档；配置驱动；maxAttempts<=size 断言。
7. `ZSetDelayQueueTest`（P2-11）：enqueue/drain 原子抢占；多实例只一个 drain 成功。
8. `StreamTrimTaskTest`（P1-5）：MINID 基于 XINFO last-delivered-id；积压不丢未投递消息。
9. `RedisStreamKeysTest`（P2-10）：key 维度含 group；prefix/topic/group 组合。
10. `RedisStreamFifoTest`（Testcontainers + RLock 串行）。
11. 集成：三条链路 send→consume 端到端；**consumer 独立连接池（P1-4）**；**SmartLifecycle 关闭顺序（P2-9）**。
12. **确认 RocketMQ 测试已删**：`RocketMQMessageBusTest`/`PushConsumerListenerTest`/`SimpleConsumerReceiveLoopTest` 不存在。

**验证**：`./mvnw test -Dtest='RedisStream*' -DfailIfNoTests=false` 全绿；`grep -rn 'rocketmq\|RocketMQ' src/test` = 0。

## Review Gates

- **Gate A**（Step 4 后）：单 topic send→subscribe→consume 跑通（Testcontainers Redis）。
- **Gate B**（Step 5/5b/5c/6 后）：注入失败 → RetrySweeper 退避回灌（**attempt 累加验证**）；杀消费线程 → PelRecoverySweeper 回收（**异步派发**）；**StreamTrimTask 积压不丢消息**。
- **Gate C**（Step 9 后）：RocketMQ 全部移除（`grep -rn 'rocketmq\|RocketMQ' src/main pom.xml scripts/` = 0）；三 consumer 在 RedisStreamMessageBus 下启动，端到端冒烟（需 Redis 实例 + docker）；**独立连接池验证（业务 RedisTemplate 不被 XREADGROUP BLOCK 阻塞）**。

## Rollback

- 无灰度回滚——直接替换是干净 cutover。若需回退，`git revert` 本任务提交恢复 RocketMQ 实现。
- parent 收尾改 `docker-compose` 删 RocketMQ 容器后，回滚还需恢复 compose（见 parent PRD）。
