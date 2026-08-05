# Design — RedisStreamMessageBus

> 前置阅读：`prd.md`。本文件聚焦数据流、Stream 模型、重试/恢复机制、与既有 RocketMQ 实现的映射。

## 1. Stream 模型与命名

| 实体 | Redis key | 说明 |
|------|-----------|------|
| 主 stream（per topic） | `stream:{topicPrefix}{topic}` | `XADD` 写入；`XREADGROUP` 消费 |
| 消费组 | `{group}`（如 `save-group`/`index-group`） | `XGROUP CREATE` |
| consumer 名 | `app:{instanceId}` | PEL 归属；instanceId = hostname 或 `${random.uuid}` 短码 |
| 延迟重试 zset | `retry-zset:{prefix}{topic}` | score = 到期 ms；value = msgId |
| 重试 payload hash | `retry:{prefix}{topic}` | field=msgId；存原字段 JSON（避免 zset value 体积） |
| 死信 stream | `dlq:{prefix}{topic}` | `XADD`；`XRANGE` 扫描 |

例：`stream:SMART_RAG_chat_message_save`、`dlq:SMART_RAG_rag_index_document`。

## 2. send() 时序

```
send(envelope):
  cb = circuitBreakerFor(topic)
  if !cb.isCallAllowed() → throw MessagePublishException("circuit OPEN")
  payloadJson = codec.encode(envelope.payload)
  // headers：envelope.headers() 优先（relay 投递时含存储的 publisher traceparent），
  // propagator.inject() 仅在 traceparent 不存在时补充（非 relay 的直接调用场景）
  headersMap = new HashMap<>(envelope.headers())
  if (!headersMap.containsKey("traceparent")) headersMap.putAll(propagator.inject())
  record = StreamRecords.newRecord()
      .ofStrings(map(
        "topic", envelope.topic(),
        "tag", envelope.tag(),
        "dedupKey", envelope.dedupKey(),
        "hashKey", envelope.hashKey(),
        "headers", json(headersMap),
        "payload", payloadJson,
        "bornTs", envelope.timestamp(),
        "contentType", "application/json"))
  try:
    recordId = redisStreamOps.add(
        StreamOffset.create(streamKey).withPrefix(MAXLEN~trim),
        record)                     // XADD stream MAXLEN ~ trim * <fields>
    cb.recordSuccess()
    metrics.recordSendSuccess(topic, ...)
    return recordId.toString()      // 传输级 ID（替代 RocketMQ messageId）
  catch Exception:
    cb.recordFailure()
    metrics.recordSendFailure(topic)
**与 RocketMQ 的差异**：RocketMQ 用 `setMessageGroup(hashKey)` 触发 broker FIFO 队列；
Redis 不分区——`hashKey` 仅作为字段写入（供业务层参考，bus 不据此路由）。FIFO 由
`EtlDispatchServiceImpl` 的 `RLock(documentId)` 保证（已存在）。

**traceparent 跨层契约**（child 2 OutboxRelay 依赖）：send() 对 `traceparent` header
采"已存在不覆盖"策略。relay 重建 envelope 时 headers 含 publisher 存储的 traceparent，
send() 不用 relay 线程的 trace context 覆盖它。这是 child 2 的 design §5 明确要求的冻结点。

**Redis 命令超时（跨 child 冻结点，评审"性能"P2）**：`XADD`/`XREADGROUP` 等命令由既有全局
Lettuce 超时 `spring.data.redis.timeout: 3000ms`（`application-dev/stable.yml`）兜底——非新增配置。
这让 `send()` 单次调用 ≤3s，child 2 的 relay 单 batch（32 行）顺序 send 最坏 ~96s（有界），
而非无界阻塞。`max-attempts`/消费端重试与 publisher outbox 重试窗口**相互独立**（send 失败≠消费
失败，消息从未进 stream），非"对齐 maxDeliveryAttempts"——本任务消费端 16 级退避是独立设计。

**SendCircuitBreaker 跨 child 冻结点（评审 P1-6.2）**：本任务 `SendCircuitBreaker` **保持不变**
（`new SendCircuitBreaker(properties.circuitBreaker())`，无 gate）。child 2 将扩展 `SendCircuitBreaker`
构造器加 `@Nullable SharedCircuitBreakerGate` + `topic`，并在 `tripOpen()`/`recordSuccess()` 的状态
迁移点调 `gate.broadcastOpen/broadcastClosed`（nullable，gate=null 时 no-op）。本任务只需保证
`circuitBreakerFor(topic)` 的实例化点可被 child 2 的装配改造注入 gate（不内联到不可达处）。

## 3. subscribe() — RedisStreamConsumerRunner

`subscribe()` 返回 `RedisStreamSubscription`，内部持有一个 `RedisStreamConsumerRunner`。
为复用既有 `PushConsumerListener` / `SimpleConsumerReceiveLoop` 的重试计数/DLQ 逻辑骨架，
**统一为应用层重试模式**（Redis 无 broker 自动重投），按 `ConsumerMode` 仅在线程模型上区分：

```
RedisStreamConsumerRunner(topic, group, consumerName, config, handler, codec, ...):
  start():
    ensureGroup()                     // XGROUP CREATE stream group $ MKSTREAM (BUSYGROUP 忽略)
    if config.mode == PUSH:
      executor = 固定线程池(concurrency)
      for i in concurrency: submit(pollLoop())
    else:  // SIMPLE
      receiveThread + processingPool(Semaphore)  // 镜像 SimpleConsumerReceiveLoop 结构
  pollLoop():
    while running:
      messages = xreadGroup(group, consumer, COUNT batch, BLOCK readBlockMs, ">")
      for msg in messages:
        if PUSH: executor.process(() -> handle(msg))  // 并发
        else:    semaphore.acquire(); processingPool.process(() -> handle(msg))

  handle(msg):
    envelope = decode(msg)            // 还原 payload + headers + propagator.restore
    try:
      idempotentWrappedHandler.onMessage(envelope)
      xack(stream, group, msg.id)     // 成功 → XACK
      metrics.recordConsumeSuccess(...)
    catch PermanentConsumeException:
      xack(stream, group, msg.id)     // 永久错误 → XACK + DLQ
      xadd(dlqKey, ...original..., reason)
      metrics.recordDeadLetter(...)
    catch Exception:
      // 可重试：不 XACK（留 PEL），交给 RetrySweeper / PelRecoverySweeper
      metrics.recordConsumeFailure(...)
      metrics.recordRetry(...)
```

**关键**：可重试失败**不立即重投**——消息留 PEL，由 RetrySweeper 按退避回灌。
这统一了 PUSH/SIMPLE 两条路径的重试语义（既有 RocketMQ 下 PUSH 靠 broker 重投、SIMPLE 靠
invisibleDuration 重现，Redis 下收敛为"PEL + sweeper"）。

## 4. RetrySweeper — 退避重试（ZSET 延迟队列）

**问题**：消息失败留 PEL 后，如何按 16 级退避（1s…30m）重新投递，而非立即重投？

**方案**：失败即 XACK（移出 PEL）+ 转入 ZSET 延迟队列，sweeper 到期回灌主 stream。

```
handle() 失败分支（可重试）:
  attempt = attemptCounter.computeIfAbsent(msgId, AtomicInteger::new).incrementAndGet()
  if attempt >= maxAttempts:
    xack(...) + xadd(dlqKey, ...)        // 耗尽 → DLQ
    attemptCounter.remove(msgId)
  else:
    xack(stream, group, msg.id)          // 移出 PEL（由 sweeper 接管）
    hset(retryHashKey, msgId, {payload, attempt, originalFields})
    zadd(retryZsetKey, nowMs + backoffSchedule.next(attempt), msgId)   // 到期分数

RetrySweeper.drain()（每 retryPollInterval 跑）:
  expired = zrangeByScoreWithScores(retryZsetKey, 0, now, LIMIT batch)
  // Lua 原子：对每个 msgId，ZREM 成功（抢到）才继续
  for entry in expired:
    if luaZremIfMember(retryZsetKey, entry.msgId) == 1:   // 抢到
      data = hget(retryHashKey, entry.msgId)
      xadd(streamKey, MAXLEN~trim, *, data.originalFields)   // 回灌主 stream
      hdel(retryHashKey, entry.msgId)
      metrics.recordRetryRedelivered(...)
```

**退避表（共享组件 BackoffSchedule，评审"通用性"P1）**：抽出 `BackoffSchedule`
（`infrastructure/messaging/BackoffSchedule.java`，`@Component` 读 `app.messaging.backoff-ms`，
`long next(int attempt)` 封顶最后一档），`RetrySweeper` 与 child 2 `OutboxRelay` **共用同一配置段**，
消除退避表多份独立实现。默认值 16 级：`[1000,5000,10000,30000,60000,120000,180000,240000,300000,
360000,420000,480000,540000,600000,1200000,1800000]`（消费端 16 级重试窗口；与 publisher 侧
outbox 重试相互独立，非"对齐"——见 §10 max-attempts 注）。child 2 复用此 bean，零额外配置。

**attempt 计数器**：沿用 `SimpleConsumerReceiveLoop` 的 Caffeine `ConcurrentMap<msgId, AtomicInteger>`
（PUSH 模式也用同一套，因 Redis 下无 broker 计数）。Caffeine expireAfterWrite 覆盖重试窗口
（最后一档 30m × 2 缓冲 = 1h）。

**多实例并发**：Lua `ZREM` 原子——第一个 sweeper ZREM 成功才回灌，其它 ZREM 返回 0 跳过。
无重复回灌。**retryHashKey 作为 payload 暂存**，避免 zset value 携带大体量 payload。

## 5. PelRecoverySweeper — 崩溃恢复

**问题**：consumer 已 `XREADGROUP` 取走消息、未 XACK 即崩溃 → 消息永久留 PEL（无人处理）。

**方案**：`XAUTOCLAIM` 转移 idle 过久的 PEL 条目给当前 consumer。

```
PelRecoverySweeper.drain()（每 retryPollInterval 跑，与 RetrySweeper 同调度器）:
  // XAUTOCLAIM stream group consumer minIdleMs 0 COUNT batch
  claimed = xautoclaim(streamKey, group, selfConsumer, pelMinIdleMs, "0", batch)
  for msg in claimed:
    handle(msg)                          // 走正常 handle 路径（成功 XACK / 失败进 retry）
```

**minIdleMs** = `pelMinIdleMs`（默认 35min）> 最大处理时长（ETL `invisibleDuration=30min`），
避免抢走正在处理的消息。多实例并发：XAUTOCLAIM 原子转移归属，天然安全。

**与 RetrySweeper 的关系**：RetrySweeper 处理"已 XACK 转延迟队列"的消息；
PelRecoverySweeper 处理"未 XACK 留 PEL"的消息（崩溃场景）。两者互补，不重叠。

## 6. DeadLetterOperations — 首次落地

```
scanDeadLetters(topic, count):
  entries = xrevrange(dlqKey, "+", "-", count)   // 最新 N 条
  return entries.map(e -> decode(e.payload, topic))

replayDeadLetter(topic, messageId):
  data = xrange(dlqKey, messageId, messageId)    // 取原字段
  xadd(streamKey, MAXLEN~trim, *, data.fields)   // 回灌主 stream
  // 不从 DLQ 删除（审计保留，靠 MAXLEN 或定期 XADD trim 控制）

deadLetterCount(topic):
  return xlen(dlqKey)
```

当前 `RocketMQMessageBus.deadLetterOperations()` 返回 `UNSUPPORTED` 桩——RedisStreamMessageBus
**真正实现**这层能力，是迁移的净增益。

## 7. 装配（直接替换 RocketMQ，无并存无开关）

`MessagingAutoConfiguration` 调整为**干净 cutover**——删除 RocketMQ bean，`RedisStreamMessageBus` 成为唯一 `MessageBus`：

```java
// 删除：rocketmqClientServiceProvider()、rocketMQMessageBus() bean
// 新增：
@Bean(destroyMethod = "shutdown")
MessageBus messageBus(MessagingProperties props, RedisTemplate redisTemplate,
                      MessagePayloadCodec codec, MessageValidator validator,
                      @Autowired(required=false) TracePropagator propagator,
                      @Autowired(required=false) MeterRegistry registry) {
    return new RedisStreamMessageBus(props, redisTemplate, codec, validator, propagator, registry);
}
```

**不引入 `backend` 开关**——直接替换。RocketMQ 实现类、依赖、配置段、运维脚本全部删除（见 prd R9）。
consumer（`ChatMessageSaveConsumer`/`UsageRecordConsumer`/`EtlDocumentConsumer`）注入 `MessageBus`，零改动。


## 8. 与既有 RocketMQ 类的映射（重构参照）

| RocketMQ 实现 | Redis 对应 | 复用程度 |
|--------------|-----------|---------|
| `RocketMQMessageBus` | `RedisStreamMessageBus` | 重写；复用 `MessagePayloadCodec`/`SendCircuitBreaker`/`MessagingMetrics` |
| `PushConsumerListener` | `RedisStreamConsumerRunner.handle()`（PUSH 分支） | 逻辑骨架复用（decode→handler→success/permanent/retryable） |
| `SimpleConsumerReceiveLoop` | `RedisStreamConsumerRunner.handle()`（SIMPLE 分支） | Caffeine 重试计数复用 |
| `RocketMQSubscription`/`PushSubscription`/`SimpleSubscription` | `RedisStreamSubscription` | 合并为一个（close 关线程池） |
| DLQ `sendToDeadLetter` (`:342`) | `XADD dlq:{topic}` | 等价 |
| `MessagingHealthIndicator` | 复用，health 检查改为 Redis `PING` + 活跃订阅 | 改探测目标 |

## 9. 错误码扩展

`MessagingErrorCode` 新增（400012 起）：

| 码 | 常量 | 场景 |
|----|------|------|
| 400012 | `STREAM_OPERATION_FAILED` | XADD/XREADGROUP 等操作失败（transport 错误，非业务） |

DLQ/retry sweeper 内部失败不抛业务异常（自治），不消耗码段。

## 10. 配置

```yaml
app.messaging:
  backoff-ms: [1000,5000,10000,30000,60000,120000,180000,240000,300000,
               360000,420000,480000,540000,600000,1200000,1800000]   # BackoffSchedule 共享（§4）；child 2 relay 复用
  # 删除 app.messaging.rocketmq.* 段；无 backend 开关
  redis:                       # 唯一 MQ 配置
    stream-prefix: "stream:"
    dlq-prefix: "dlq:"
    retry-prefix: "retry:"
    retry-zset-prefix: "retry-zset:"
    trim-threshold: 100000     # MAXLEN ~ 值
    read-block-ms: 2000        # XREADGROUP BLOCK
    read-batch: 32             # COUNT
    pel-min-idle-ms: 2100000   # 35min，> ETL invisibleDuration 30min
    retry-poll-interval: 5s    # RetrySweeper/PelRecoverySweeper 扫描间隔
    max-attempts: 16           # 消费端 RetrySweeper 重试窗口；与 publisher outbox 重试相互独立（非"对齐"）
```

## 11. 测试策略

| 测试类 | 覆盖 |
|--------|------|
| `RedisStreamMessageBusTest` | send 返回 entry ID；XADD 写入正确字段；熔断 OPEN 抛异常 |
| `RedisStreamConsumerRunnerTest` | XREADGROUP→handle→XACK；PermanentConsume→DLQ；可重试留 PEL |
| `RetrySweeperTest` | 失败转 ZSET；退避计算；maxAttempts→DLQ；Lua ZREM 原子抢占（Testcontainers） |
| `PelRecoverySweeperTest` | 模拟未 XACK，35min idle 后 XAUTOCLAIM 回收（Testcontainers，用短 minIdle 加速） |
| `RedisStreamDeadLetterOperationsTest` | scan/replay/count 三方法 |
| `BackoffScheduleTest` | next(attempt) 封顶最后一档；配置驱动（child 2 共用） |
| 集成测试 | Testcontainers Redis，三条链路 send→consume 端到端 |

> Testcontainers 已是本仓依赖；新增 Redis Testcontainer（`GenericContainer` + redis:8 镜像）。
