# Implementation Plan — RedisStreamMessageBus

> 前置阅读：`prd.md`、`design.md`。自下而上：配置 → send → subscribe/消费循环 → sweeper → DLQ → 装配 → 测试。

## Step 1 — RedisStreamConfig Properties（直接替换，无 backend 开关）

**文件**：
- `MessagingProperties.java`：
  - **删除** `RocketMQConfig` record（含 endpoints/producerGroup/maxDeliveryAttempts/maxMessageSize/enableSsl/accessKey/secretKey）。
  - 新增 `RedisStreamConfig` record（design.md §10 全字段：stream/dlq/retry/retry-zset prefix、
    trim-threshold、read-block-ms、read-batch、pel-min-idle-ms、retry-poll-interval、max-attempts）。
    另增 `app.messaging.backoff-ms`（`long[]`，顶层，BackoffSchedule 共享，design §4/§10）。
  - **不引入** `Backend` 枚举——无选择路径。
- `application.yml`：删除 `app.messaging.rocketmq.*` 段，新增 `app.messaging.redis.*` 段。

## Step 2 — RedisStreamMessageBus.send()

**文件**：`infrastructure/messaging/redis/RedisStreamMessageBus.java`

构造注入：`MessagingProperties`、`RedisTemplate<String,String>`（Stream ops）、
`MessagePayloadCodec`、`MessageValidator`、`@Nullable TracePropagator`、`@Nullable MeterRegistry`。

字段：
- `StringRedisTemplate`/`RedisTemplate` 的 `StreamOperations<String,Object,Object> streamOps`。
- `Map<String,SendCircuitBreaker> cbs`（per-topic，`circuitBreakerFor(topic)`，复用既有类）。

`send(envelope)`（design.md §2）：
- cb.isCallAllowed() → OPEN 抛 `MessagePublishException`。
- **traceparent（跨 child 冻结点，design §2）**：`headersMap = new HashMap<>(envelope.headers())`；
  `if (!headersMap.containsKey("traceparent")) headersMap.putAll(propagator.inject())`——relay 投递时
  不覆盖存储的 publisher traceparent。非 relay 的直接调用场景才注入当前线程 trace。
- **命令超时**：依赖既有全局 `spring.data.redis.timeout: 3000ms`（非新增）；`send()` 单次 ≤3s。
- `streamOps.add(StreamOffset.create(streamKey), record)`（含 MAXLEN trim，用 `XAddOptions`）。
- 成功 cb.recordSuccess + 返回 `recordId.getValue()`；失败 cb.recordFailure + 抛异常。

**注意**：Spring Data Redis 的 `StreamOperations.add` 签名；MAXLEN 用 `XAddOptions.maxlen(trim).approximate(true)`。
先 grep 确认 Spring Data Redis 版本支持（`org.springframework.data.redis.core.stream.StreamOperations`）。

## Step 3 — RedisStreamSubscription + ConsumerRunner

**文件**：
- `infrastructure/messaging/redis/RedisStreamSubscription.java`（implements `Subscription`）：
  持 `RedisStreamConsumerRunner`、`AtomicBoolean closed`；`close()` 幂等 → runner.stop()。
- `infrastructure/messaging/redis/RedisStreamConsumerRunner.java`：
  - `start()`：`ensureGroup()`（`streamOps.createGroup`，`BUSYGROUP` 吞）。
  - 按 `config.consumerMode()` 起线程（PUSH=concurrency 线程池；SIMPLE=receive+processingPool+Semaphore），
    结构镜像 `SimpleConsumerReceiveLoop`。
  - `pollLoop()`：`streamOps.read(...)`（XREADGROUP GROUP consumer COUNT batch BLOCK blockMs ">"）。
  - `handle(record)`：decode → idempotentWrapped handler → XACK / PermanentConsume→DLQ / 可重试→不XACK留PEL。
  - `stop()`：关线程池，`awaitTermination(shutdownTimeout)`。

**复用**：`IdempotentHandler.wrap(handler, topic, redisTemplate, ttl, metrics)`（既有，bus 透明）。
Caffeine `ConcurrentMap<msgId, AtomicInteger>` 重试计数（复用 `SimpleConsumerReceiveLoop` 的取法）。

## Step 4 — subscribe()

**文件**：`RedisStreamMessageBus.subscribe()`：
- 构造 `RedisStreamConsumerRunner` → `start()` → 包进 `RedisStreamSubscription` 返回。
- `MessageHandler` 经 `IdempotentHandler.wrap()` 包装（与 RocketMQ 一致）。

## Step 5 — RetrySweeper（ZSET 退避）

构造注入：`RedisTemplate`、`MessagingProperties`、`BackoffSchedule`（§4 共享退避）、`@Nullable MeterRegistry`。

字段：per-stream 的 `zsetKey`/`hashKey`。退避经 `backoffSchedule.next(attempt)`（非硬编码常量）。

- `start()`：`scheduler.scheduleAtFixedRate(this::drain, retryPollInterval, retryPollInterval)`。
- `drain()`：
  - 对每个活跃 stream（注册表，subscribe 时登记）：
    - `zrangeByScoreWithScores(zsetKey, 0, now, 0, batch)`。
    - 对每个 entry：Lua `if redis.call('ZREM', KEYS[1], ARGV[1])==1 then return 1 else return 0`；
      返回 1（抢到）→ `hget(hashKey, msgId)` → `xadd(streamKey, ..., originalFields)` → `hdel`。
- `registerStream(streamKey, retryZsetKey, retryHashKey, maxAttempts)`：subscribe 时调。
- `stop()`：关 scheduler。
- Lua 脚本常量（ZREM 原子抢占）。
- 指标：`retry.redelivered`/`retry.exhausted` counter。

**handle() 失败分支改造**：`RedisStreamConsumerRunner.handle()` 的"可重试"分支调
`retrySweeper.enqueue(streamKey, msgId, attempt, originalFields)`——该方法做 XACK + HSET + ZADD。
（把 XACK 从 handle 移到 enqueue 内，保证"转延迟队列"原子语义集中。）

## Step 6 — PelRecoverySweeper

**文件**：`infrastructure/messaging/redis/PelRecoverySweeper.java`

`implements SmartLifecycle`，与 RetrySweeper 同调度器（或独立，phase 同）。
构造注入同 RetrySweeper。

- `drain()`：对每个活跃 stream：
  - `xautoclaim(streamKey, group, selfConsumer, pelMinIdleMs, "0", batch)`
    （`streamOps.claim` 或底层 `XAUTOCLAIM`；确认 Spring Data Redis API，可能需 `RedisCallback` 调原生）。
  - 取回的 record 走 `RedisStreamConsumerRunner.handle()`（注入 runner 引用，或回调）。
- `selfConsumer` = `app:{instanceId}`。

**注意**：Spring Data Redis 对 XAUTOCLAIM 的封装支持度——若 `StreamOperations` 无直接方法，
用 `redisTemplate.execute((RedisCallback) conn -> conn.streamCommands().xClaim(...))` 或
原生 `XAUTOCLAIM` Lua。先验证 API。

## Step 7 — RedisStreamDeadLetterOperations

**文件**：`infrastructure/messaging/redis/RedisStreamDeadLetterOperations.java`
（implements `DeadLetterOperations`，design.md §6）

构造注入：`RedisTemplate`、`MessagePayloadCodec`、`MessagingProperties`。
- `scanDeadLetters(topic, count)`：`xrevrange(dlqKey, Range.<String>closed("+","-"), limit)` → decode。
- `replayDeadLetter(topic, messageId)`：`xrange` 取字段 → `xadd(streamKey, ...)`。
- `deadLetterCount(topic)`：`xlen(dlqKey)`。

`RedisStreamMessageBus.deadLetterOperations()` 返回此实例（非 UNSUPPORTED）。

## Step 8 — MessageBusManagement 扩展

**文件**：`MessageBusManagement.java` 加 `boolean isCircuitBreakerOpen(String topic)`。
`RedisStreamMessageBus` 实现：`circuitBreakerFor(topic).state() != CLOSED`。
（契约冻结点：供 child 2 的 `SharedCircuitBreakerGate` 读。）

## Step 9 — 装配（直接替换，删除 RocketMQ）

**文件**：`MessagingAutoConfiguration.java`

```java
// 删除：rocketmqClientServiceProvider() bean、rocketMQMessageBus() bean
// 新增：
@Bean(destroyMethod = "shutdown")
MessageBus messageBus(MessagingProperties props, RedisTemplate redisTemplate,
                      MessagePayloadCodec codec, MessageValidator validator,
                      @Autowired(required=false) TracePropagator propagator,
                      @Autowired(required=false) MeterRegistry registry) {
    return new RedisStreamMessageBus(props, redisTemplate, codec, validator, propagator, registry);
}
@Bean
RetrySweeper retrySweeper(...) { ... }
@Bean
PelRecoverySweeper pelRecoverySweeper(...) { ... }
@Bean
BackoffSchedule backoffSchedule(MessagingProperties props) { return new BackoffSchedule(props.getBackoffMs()); }  // §4 共享退避；child 2 relay 复用
@Bean
RedisStreamDeadLetterOperations redisStreamDeadLetterOperations(...) { ... }
```

**删除 RocketMQ 全部实现类**（grep = 0）：
- `RocketMQMessageBus.java`、`PushConsumerListener.java`、`SimpleConsumerReceiveLoop.java`、
  `RocketMQSubscription.java`、`PushSubscription.java`、`SimpleSubscription.java`。
- 若 `OpenTelemetryTracePropagator` 仅被 `RocketMQMessageBus` 使用则一并删除（先 grep 确认）。
- 测试：`RocketMQMessageBusTest`/`PushConsumerListenerTest`/`SimpleConsumerReceiveLoopTest` 随实现删除。

**pom.xml**：移除 `org.apache.rocketmq:rocketmq-client-java`（及 testcontainers-rocketmq 若有）。

**scripts/init-rocketmq-topics.sh**：删除。

**注意**：三 consumer（`ChatMessageSaveConsumer`/`UsageRecordConsumer`/`EtlDocumentConsumer`）注入 `MessageBus`，零改动。

## Step 10 — MessagingErrorCode 扩展

`MessagingErrorCode.java` 新增 `STREAM_OPERATION_FAILED(400012, "消息流操作失败")`。

## Step 11 — 测试

按 design.md §11 编写：
1. `RedisStreamMessageBusTest`（单测，mock streamOps）。
2. `RedisStreamConsumerRunnerTest`（单测）。
3. `RetrySweeperTest`（Testcontainers Redis，退避 + Lua 抢占）。
4. `PelRecoverySweeperTest`（Testcontainers Redis，短 minIdle 加速）。
5. `RedisStreamDeadLetterOperationsTest`。
6. `RedisStreamFifoTest`（Testcontainers Redis + 验证 RLock 串行）。
7. `BackoffScheduleTest`（next(attempt) 封顶最后一档；配置驱动）。
8. 集成：三条链路 send→consume 端到端（Testcontainers Redis）。
9. **确认 RocketMQ 测试已删**：`RocketMQMessageBusTest`/`PushConsumerListenerTest`/`SimpleConsumerReceiveLoopTest` 不存在。

**验证**：`./mvnw test -Dtest='RedisStream*' -DfailIfNoTests=false` 全绿；`grep -rn 'rocketmq\|RocketMQ' src/test` = 0。

## Review Gates

- **Gate A**（Step 4 后）：单 topic send→subscribe→consume 跑通（Testcontainers Redis）。
- **Gate B**（Step 5/6 后）：注入失败 → RetrySweeper 退避回灌；杀消费线程 → PelRecoverySweeper 回收。
- **Gate C**（Step 9 后）：RocketMQ 全部移除（`grep -rn 'rocketmq\|RocketMQ' src/main pom.xml scripts/` = 0）；
  三 consumer 在 RedisStreamMessageBus 下启动，端到端冒烟（需 Redis 实例 + docker）。

## Rollback

- 无灰度回滚——直接替换是干净 cutover。若需回退，`git revert` 本任务提交恢复 RocketMQ 实现。
- parent 收尾改 `docker-compose` 删 RocketMQ 容器后，回滚还需恢复 compose（见 parent PRD）。
