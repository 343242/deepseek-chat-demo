# Design — RedisStreamMessageBus

> 前置阅读：`prd.md`。本文件聚焦数据流、Stream 模型、重试/恢复机制、与既有 RocketMQ 实现的映射。

## 0. 修订记录（评审修订，基线 commit `a85450b`）

| ID | 严重度 | 问题 | 修订位置 |
|----|--------|------|----------|
| P0-1 | 🔴 | §3 与 §4 对"失败后是否 XACK"自相矛盾 | §3 handle()、§3 "重试语义统一" 段 |
| P0-2 | 🔴 | attempt 计数器以 entry ID 为 key，XADD 回灌生成新 ID → 计数永不累加 → 永不进 DLQ | §4 "attempt 计数器" 段、§4 失败分支 |
| P1-3 | 🟠 | ZREM/XADD/HDEL 非原子 → ZREM 成功后崩溃丢消息 | §4 "原子回灌" 段（单 Lua 脚本） |
| P1-4 | 🟠 | XREADGROUP BLOCK 占用 Lettuce 共享连接 → 全站 Redis 阻塞 | §3 "消费连接隔离" 段 |
| P1-5 | 🟠 | MAXLEN~trim 与消费积压冲突 → 积压超阈值丢未投递消息 | §2 "主 stream trim 策略" 段 |
| P1-6 | 🟠 | PelRecoverySweeper 在调度线程同步 handle() → 阻塞 sweeper | §5 "异步派发" 段 |
| P2-7 | 🟡 | RetrySweeper.drain() 对 HGET null 无防护 | §4 drain 单条隔离 + 孤儿清理 |
| P2-8 | 🟡 | DLQ 写入无 MAXLEN → 无界增长 | §3/§6 所有 DLQ XADD 带 MAXLEN |
| P2-9 | 🟡 | 关闭顺序未规定 | §7 "SmartLifecycle phase 顺序" 段 |
| P2-10 | 🟡 | retry/dlq key 不含 group → 多组场景串扰 | §1 key 维度改为 topic:group |
| P2-11 | 🟡 | ZSET 延迟队列内联 → 抽 ZSetDelayQueue 通用组件 | §4 "ZSetDelayQueue 抽取" 段 |
| P2-12 | 🟡 | RetrySweeper 每条 4+ RTT | §4 批量 Lua |
| P2-13 | 🟡 | payload 三处驻留（stream+retry hash+dlq）内存放大 | §10 内存预估 + 持久化要求 |
| P2-14 | 🟡 | retry hash 残留泄漏 | §4 HSET+ZADD 单 Lua + hash TTL |
| 启动期断言 | 🟡 | maxAttempts/backoff/pel-min-idle 无校验 | §10 "启动期断言" 段 |
| consume 退避 | 🟡 | pollLoop 无 try/catch，XREADGROUP 失败线程静默退出；Redis 故障期狂打 | §3 pollLoop + "Redis 故障韧性" 段（指数退避+jitter）、§9 metric、§10 reconnect-backoff 配置 |

---

## 1. Stream 模型与命名

| 实体 | Redis key | 说明 |
|------|-----------|------|
| 主 stream（per topic） | `stream:{topicPrefix}{topic}` | `XADD` 写入；`XREADGROUP` 消费 |
| 消费组 | `{group}`（如 `save-group`/`index-group`） | `XGROUP CREATE` |
| consumer 名 | `app:{instanceId}` | PEL 归属；instanceId = hostname 或 `${random.uuid}` 短码（前缀可配） |
| 延迟重试 zset | `retry-zset:{prefix}{topic}:{group}` | score = 到期 ms；value = retryId |
| 重试 payload hash | `retry:{prefix}{topic}:{group}` | field=retryId；存原字段 JSON（避免 zset value 体积） |
| 死信 stream | `dlq:{prefix}{topic}:{group}` | `XADD`（带 MAXLEN）；`XRANGE` 扫描 |

例：`stream:SMART_RAG_chat_message_save`、`dlq:SMART_RAG_rag_index_document:index-group`。

**P2-10 修订（key 维度含 group）**：retry-zset / retry-hash / dlq key 一律含 `:{group}`。
原因：`XREADGROUP >` 对 group 内独立投递，失败是 per-group-consumer 事件；若 retry key 不含 group，
A 组失败消息会被 B 组 sweeper 抢到回灌，投递给错误 consumer。PEL 本身就是 per-group，故 retry/dlq 维度对齐。

> **当前拓扑为 1 topic : 1 group**（`chat_message_save`→`save-group`、`rag_index_document`→`index-group`）。
> 主 stream 与 XADD 回灌对**所有 group 广播**（XREADGROUP > 各组独立消费）。回灌/replay 的"广播语义"
> 在多组场景下会重复处理——当前 1:1 拓扑不受影响；若未来某 topic 出现多 group，需将
> `DeadLetterOperations.scanDeadLetters/replay` 的 API 扩展为带 group 参数（见 §6 "多组扩展点"）。

**key 计算集中化**：新增 `RedisStreamKeys`（`@Component`，注入 prefix + topic + group），统一解析
主 stream / dlq / retry-hash / retry-zset 四类 key，避免改 key 方案时多处修改（评审"通用性"）。

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
        "attempt", "0",            // P0-2：随消息携带 attempt 基线（见 §4）
        "contentType", "application/json"))
  try:
    recordId = redisStreamOps.add(
        StreamOffset.create(streamKey).withPrefix(MINID~{trimCursor}),  // P1-5：见下
        record)
    cb.recordSuccess()
    metrics.recordSendSuccess(topic, ...)
    return recordId.toString()      // 传输级 ID（替代 RocketMQ messageId）
  catch Exception:
    cb.recordFailure()
    metrics.recordSendFailure(topic)
```

**P1-5 修订（主 stream trim 策略）**：固定 `MAXLEN ~ 100000` 会在消费积压时物理删除**尚未进入 PEL
的 entry**（XREADGROUP `>` 只投递从未投给 group 的消息；trim 删掉的从未投递消息无人补偿）。
改为 `MINID ~ {lastDeliveredIdByGroup}` 只裁剪"已投递/已 ACK 区间"：
- 每个 group 用 `XINFO GROUPS` 取 `last-delivered-id`，取**所有 group 的最小值**作为 trim cursor 下限，
  只删各组都已读过的 entry。
- send() 不在 XADD 内联 trim（多 group 时单次 XADD 无法知道全组 last-delivered）；
  改由独立 `StreamTrimTask`（SmartLifecycle，默认 1min）按 `XINFO` 计算最小 last-delivered-id 后
  `XTRIM stream MINID ~ {minId}`。trim 只落后 1 个周期，积压超阈值靠告警（§9 metric）。
- 兜底：`trim-threshold` 改为**告警阈值**（非物理裁剪上限），`XLEN - ΣXPENDING` 超阈值触发告警。
- 若 Redis < 6.2 不支持 MINID，降级为 `MAXLEN ~ {大值}` + 显式风险声明（仅单 group 安全）。

**P2-13 修订（payload 内存）**：同一 payload 可能并存于 主 stream（未 trim）+ retry hash（重试期）
+ dlq（死信）。三处驻留的最大量 = trim 窗口（MINID 后保留）+ retry 窗口积压 + dlq 窗口。
§10 给出内存预估公式与 `maxmemory-policy noeviction` 配套要求；retry hash 设 TTL 覆盖最大退避窗口
（见 §4 P2-14），dlq 带 MAXLEN（见 §3/§6）。

**与 RocketMQ 的差异**：RocketMQ 用 `setMessageGroup(hashKey)` 触发 broker FIFO 队列；
Redis 不分区——`hashKey` 仅作为字段写入（供业务层参考，bus 不据此路由）。FIFO 由
`EtlDispatchServiceImpl` 的 `RLock(documentId)` 保证（已存在）。

**幂等层与重试的承重不变式（已核实 `IdempotentHandler.java#A868:62-69`）**：`IdempotentHandler.wrap`
在 SETNX 成功后调 handler；**handler 抛异常即 `redis.delete(redisKey)`**，幂等键被清除，重试会重新处理。
因此 `handle()` 在可重试失败时**必须向上抛异常**（走 §3/§4 失败分支），不得 swallow；
否则幂等键残留会导致后续重试被误判重复而静默跳过。新代码须保持"失败即抛、即删幂等键"的契约。

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
    reconnectBackoff = ReconnectBackoff(initial=1s, factor=2, cap=30s, jitter=±20%)
    while running:
      try:
        messages = xreadGroup(group, consumer, COUNT batch, BLOCK readBlockMs, ">")
        reconnectBackoff.reset()            // 成功即重置（空拉取也算成功）
        for msg in messages:
          if PUSH: executor.process(() -> handle(msg))  // 并发
          else:    semaphore.acquire(); processingPool.process(() -> handle(msg))
      catch Exception e:                    // XREADGROUP/XADD 连接级失败（Redis 宕机/主从切换/网络分区）
        metrics.recordConsumeConnectionFailure(topic, group)
        if !running: break                  // 关停中不退避
        sleep = reconnectBackoff.nextSleep()    // 1s→2s→4s→…→30s 封顶，带 ±20% jitter（防同步重连风暴）
        log.warn("XREADGROUP failed, backing off {topic={}, group={}, sleepMs={}}", topic, group, sleep, e)
        Thread.sleep(sleep)                 // 阻塞 poll 线程自身，不占用业务线程；全 BLOCK 线程各自独立退避
  handle(msg):
    envelope = decode(msg)            // 还原 payload + headers + propagator.restore
    try:
      idempotentWrappedHandler.onMessage(envelope)
      xack(stream, group, msg.id)     // 成功 → XACK
      metrics.recordConsumeSuccess(...)
    catch PermanentConsumeException:
      xack(stream, group, msg.id)     // 永久错误 → XACK + DLQ（带 MAXLEN）
      xadd(dlqKey, MAXLEN~dlqTrim, ...original..., reason, originGroup=group)
      metrics.recordDeadLetter(...)
    catch RetryableConsumeException | 业务可重试白名单:
      routeToRetry(msg, ...)          // P0-1：见 §4 失败分支（XACK + ZSET，非留 PEL）
    catch Exception:                  // 未知异常 → 直接 DLQ + 告警（避免 bug 被重试循环放大）
      xack(stream, group, msg.id)
      xadd(dlqKey, MAXLEN~dlqTrim, ...original..., reason="UNKNOWN_UNRETRYABLE")
      metrics.recordUnknownFailure(topic)
```

**P0-1 修订（统一 XACK 语义）**：可重试失败**统一走"XACK 移出 PEL + 转延迟队列"**
（见 §4 失败分支），**不留 PEL**。原 §3 "留 PEL 由 RetrySweeper 回灌"的措辞已删除——
RetrySweeper 只扫 ZSET，留 PEL 的消息只有 PelRecoverySweeper 处理，且需等 pelMinIdleMs（默认 40min），
会使首档 1s 退避失效。语义收敛为："PUSH/SIMPLE 两条路径在 Redis 下统一为 XACK+ZSET 延迟重试"。

> 与 RocketMQ 的对照：RocketMQ PUSH 靠 broker 重投、SIMPLE 靠 invisibleDuration 重现；
> Redis 无 broker 自动重投，统一为"XACK + ZSET + sweeper"。PelRecoverySweeper 仅用于崩溃恢复
> （已 XREADGROUP 未 XACK），与正常重试路径正交（见 §5）。

**P2-8 修订（DLQ 写入带 MAXLEN）**：所有 DLQ XADD 统一带 `MAXLEN ~ {dlq-trim-threshold}`
（单独配置，审计保留期，默认 50000），避免 DLQ 无界增长。replay 回灌仍带主 stream 的 trim 策略（§2）。

### P1-4 消费连接隔离（新增）

`XREADGROUP ... BLOCK readBlockMs`（默认 2000ms）是长阻塞命令。Spring Data Redis + Lettuce 默认
`shareNativeConnection=true`，阻塞命令会**独占共享 native 连接**长达 block 时长，把**整个 app 的所有
RedisTemplate 操作**（缓存、`IdempotentHandler` SETNX、业务读写）拖到 2s 级延迟——RocketMQ 下不存在此问题
（broker 推送不占 Redis 连接）。

**方案（强制）**：为消费循环提供**独立 `LettuceConnectionFactory`**：
```yaml
app.messaging.redis.consumer:
  connection:
    share-native-connection: false      # 独立连接，不与业务 RedisTemplate 共享
    pool:
      max-active: <Σ 各 topic concurrency>  # 至少覆盖所有 BLOCK 线程
      max-idle: <同上>
```
- 该 factory 仅用于 `StreamOperations` 的 XREADGROUP/XACK，与业务用的 `redisTemplate`（共享连接）分离。
- 或改用 Redisson `RStream`（自带独立连接池），二选一。design 阶段已确认为强制项，不可省略。
- `read-block-ms` 可进一步调小（如 500ms）以缩短单次占用，但根本解法仍是连接隔离。

### Redis 故障韧性（新增，补 consume 侧重连退避）

本任务不设消息总线本地降级路径——**Redis 是 Stream 存储本身，无 Redis 即无 MQ**，与可降级的
`FallbackRateLimiter`（Redis 挂 → 本地 token bucket）不同，刻意 fallback 会丢消息/双写不一致，是错误架构。
正确的姿态是"快速失败 + 不阻塞业务 + 健康探活"，send 侧已由 `SendCircuitBreaker` + 命令超时(3s) 覆盖，
consume 侧由 pollLoop 退避重连（见上）覆盖。三者职责对照：

| 故障面 | 机制 | 覆盖位置 |
|--------|------|---------|
| send 端 Redis 不可达 | `SendCircuitBreaker` 失败累计 → OPEN → `send()` 快速抛 `MessagePublishException`，不阻塞调用线程（命令超时 3s 兜底） | §2、prd R1 |
| consume 端 XREADGROUP 失败 | pollLoop try/catch + `ReconnectBackoff`（1s→30s 指数退避 + ±20% jitter），阻塞 poll 线程自身 | 本节 |
| Redis 主从切换/短暂网络抖动 | `BUSYGROUP`/连接异常自动重连；Lettuce 自带连接池重连 | pollLoop catch |

**`ReconnectBackoff` 设计要点**：
- **指数退避 + 封顶**：`initial=1s, factor=2, cap=30s`，避免 Redis 恢复后瞬时大量重连压垮。
- **jitter 抖动**：每次 sleep 加 ±20% 随机偏移，防止多 consumer/多实例在同一时刻同步重连形成"重连风暴"。
  （多实例同时启动或 Redis 同时恢复时，无 jitter 的固定退避会产生同步重连尖峰。）
- **成功即重置**：任何一次成功的 XREADGROUP（含空拉取）→ `reset()` 回 1s，确保稳定期不保留放大退避。
- **退避期间不丢 PEL/retry 消息**：消息要么已在 PEL（PelRecoverySweeper 兜底）、要么在 retry-zset（RetrySweeper 兜底），
  poll 线程退避不影响这两条独立恢复链路。
- **不阻塞业务线程**：退避 sleep 只阻塞该 poll 线程自身（消费线程池内的 worker）；业务的 `redisTemplate` 操作走独立连接池（P1-4），不受影响。

**`MessagingHealthIndicator` 探活**（复用既有类，改探测目标，§8 已列）：health 检查 = Redis `PING` + 活跃订阅数 > 0。
注意：pollLoop 退避期间活跃订阅数仍 > 0（线程未退出），故 health 不反映"是否在退避"——
`metrics.recordConsumeConnectionFailure` 作为补充观测点，运维可据此告警"持续退避未恢复"。

## 4. RetrySweeper — 退避重试（ZSET 延迟队列）

**问题**：消息失败后，如何按 16 级退避（1s…30m）重新投递，而非立即重投？

**方案**：失败即 XACK（移出 PEL）+ 原子转入 ZSET 延迟队列，sweeper 到期原子回灌主 stream。

### 4.1 失败分支（handle 内可重试路径，P0-1/P0-2 修订）

```
routeToRetry(msg, envelope):
  // attempt 从消息字段还原（P0-2：不再以 entry ID 为 key）
  attempt = parseInt(msg.field("attempt", "0")) + 1
  if attempt > maxAttempts:
    xack(stream, group, msg.id)
    xadd(dlqKey, MAXLEN~dlqTrim, ...original..., reason="RETRY_EXHAUSTED", originGroup=group)
    metrics.recordDeadLetter(...)
    return
  // 失败 → XACK 移出 PEL + HSET/ZADD（单 Lua 原子，P2-14）
  retryId = uuid()                                   // 稳定标识，跨回灌保持一致
  luaEnqueueRetry(retryHashKey, retryZsetKey,
      retryId, {payload, attempt, originalFields, bornTs},
      nowMs + backoffSchedule.next(attempt))         // 到期 score
  xack(stream, group, msg.id)                        // 移出 PEL（sweeper 接管）
  metrics.recordRetry(topic, group, attempt)
```

**P0-2 修订（attempt 计数器随消息走，不以 entry ID 为 key）**：
原方案沿用 `SimpleConsumerReceiveLoop` 的 Caffeine `ConcurrentMap<msgId, AtomicInteger>`，前提是
RocketMQ broker 重投同一条消息、msgId 稳定（`SimpleConsumerReceiveLoop.java#0C3F:266`）。Redis 下
sweeper 回灌用 XADD **生成全新 entry ID**，handle 里 `computeIfAbsent(newId, …)` 每次从 1 开始，
attempt 永远 < maxAttempts → **永不进 DLQ，无限重试**。

**修正**：attempt 作为**消息字段**随消息流转——
1. send() 写入 `attempt=0`（§2 已加该字段）。
2. 失败时 `attempt = field+1`；HSET retry hash 存 `attempt`。
3. 回灌时把 `attempt` 作为字段 XADD 进新 entry（见 §4.2 原子脚本）。
4. handle 下次从字段还原 `attempt`，正确累加。

计数随消息走，不依赖内存（实例重启、多实例都正确）。Caffeine 内存计数器**仅作 metric/观测缓存**，
不作正确性依据；保留 Caffeine expireAfterWrite（覆盖 max 退避窗口 ×2）用于 metric 去抖。

### 4.2 RetrySweeper.drain() — 原子批量回灌（P1-3 / P2-7 / P2-12 修订）

**P1-3 修订**：原方案 Lua 只做 ZREM，后续 XADD/HDEL 非原子——ZREM 成功后、XADD 前崩溃 → 消息
永久丢失（违反 at-least-once）。改为**单 Lua 脚本**原子完成 ZREM→HGET→XADD→HDEL：

```lua
-- KEYS[1]=retryZsetKey  KEYS[2]=retryHashKey  KEYS[3]=streamKey
-- ARGV[1]=now  ARGV[2]=batch  ARGV[3]=maxlenApprox
local now, batch = tonumber(ARGV[1]), tonumber(ARGV[2])
local entries = redis.call('ZRANGEBYSCORE', KEYS[1], 0, now, 'WITHSCORES', 'LIMIT', 0, batch)
local refed = {}
for i = 1, #entries, 2 do
  local retryId = entries[i]
  local score = entries[i+1]
  local data = redis.call('HGET', KEYS[2], retryId)
  if data then
    local fields = cjson.decode(data)
    fields['attempt'] = tostring(tonumber(fields['attempt']))   -- 随回灌携带（P0-2）
    redis.call('XADD', KEYS[3], 'MAXLEN', '~', ARGV[3], '*',
               'topic', fields.topic, 'tag', fields.tag, 'dedupKey', fields.dedupKey,
               'hashKey', fields.hashKey, 'headers', fields.headers, 'payload', fields.payload,
               'bornTs', fields.bornTs, 'attempt', fields.attempt, 'contentType', 'application/json')
    redis.call('HDEL', KEYS[2], retryId)
    table.insert(refed, retryId)
  else
    -- P2-7：hash 无 payload（HSET 曾失败/zset 孤儿）→ 清理 zset 条目，记 metric
    redis.call('ZREM', KEYS[1], retryId)
    redis.call('INCR', 'messaging:retry:orphan:count')
  end
end
-- 抢占式 ZREM：只对成功取到 data 的条目从 zset 移除（上面 HGET 成功后，回灌成功后移除）
for _, id in ipairs(refed) do redis.call('ZREM', KEYS[1], id) end
return #refed
```

> 注意：上述为示意逻辑顺序。实现时 ZRANGEBYSCORE 取候选 → 逐条 ZREM 抢占（返回 1 才继续 HGET/XADD/HDEL）
> → 保证多实例只一个回灌。完整脚本见实现；评审要点：**ZREM + XADD + HDEL 必须在单个 Lua eval 内**，
> 中间崩溃由 Redis 脚本原子性兜底（要么全做，要么全不做）。

**P2-7 修订（单条隔离）**：drain() 对每条候选 try/catch；HGET 返回 null（孤儿）→ ZREM 清理 +
`recordRetryOrphan` metric；单条异常不中止整批。

**P2-12 修订（批量降 RTT）**：整个 batch 在单 Lua eval 内完成（1 RTT），而非逐条 ZREM/HGET/XADD/HDEL
（原 ~4 RTT/条 ×32 ≈ 128 RTT）。

### 4.3 attempt 计数器（P0-2 修订，细化）

- 正确性来源：**消息字段 `attempt`**（随 send→失败→HSET→回灌→handle 流转）。
- Caffeine `ConcurrentMap<retryId, AtomicInteger>`：仅 metric 累加与本地观测去抖，不作正确性依据；
  expireAfterWrite = 最大退避窗口 ×2（1h）。retryId 取回灌前的稳定标识（见 §4.1）。
- maxAttempts 判定用字段 attempt（`attempt > maxAttempts` → DLQ）。

### 4.4 退避表（共享组件 BackoffSchedule，评审"通用性"P1）

抽出 `BackoffSchedule`（`infrastructure/messaging/BackoffSchedule.java`，`@Component` 读
`app.messaging.backoff-ms`，`long next(int attempt)` 封顶最后一档），`RetrySweeper` 与 child 2
`OutboxRelay` **共用同一配置段**，消除退避表多份独立实现。默认值 16 级：
`[1000,5000,10000,30000,60000,120000,180000,240000,300000,360000,420000,480000,540000,600000,
1200000,1800000]`（消费端 16 级重试窗口；与 publisher 侧 outbox 重试相互独立）。child 2 复用此 bean，
零额外配置。

### 4.5 ZSetDelayQueue 抽取（P2-11 修订，评审"通用性"）

ZSET 延迟队列模式（`ZADD(score=到期)` + `ZRANGEBYSCORE` + Lua 抢占 ZREM + 回灌回调）是通用结构，
未来 Outbox 重试、定时任务、延迟通知都会复用。抽 `ZSetDelayQueue`（`infrastructure/messaging/ZSetDelayQueue.java`）：

```java
@Component
class ZSetDelayQueue {
    // 入队（payload 存 hash，zset 只存 id）
    void enqueue(String zsetKey, String hashKey, String id, Object payload, long dueAtMs);
    // 原子批量出队（单 Lua：ZRANGEBYSCORE → ZREM 抢占 → HGET payload → HDEL），回调消费 payload
    int drain(String zsetKey, String hashKey, int batch, Consumer<Payload> onDue);
}
```
`RetrySweeper` 注入 `ZSetDelayQueue`，`onDue` 回调负责"XADD 回灌主 stream"。消除 retry 逻辑里
ZSET 操作的内联，且 child 2 Outbox 重试可直接复用。

### 4.6 多实例并发

单 Lua 脚本内 ZREM 抢占（返回 1 才继续）——第一个 sweeper 抢到才回灌，其它 ZREM 返回 0 跳过，
无重复回灌。retryHashKey 作为 payload 暂存，避免 zset value 携带大体量 payload。

**P2-14 修订（retry hash 不泄漏）**：HSET+ZADD 用单 Lua 原子（§4.1 的 `luaEnqueueRetry`），
杜绝"HSET 成功 ZADD 失败 → hash 孤儿"或反之；额外给 retry hash 字段设 TTL（覆盖 max 退避窗口 ×2
+ 缓冲 = 2h），防极端场景残留。

## 5. PelRecoverySweeper — 崩溃恢复

**问题**：consumer 已 `XREADGROUP` 取走消息、未 XACK 即崩溃 → 消息永久留 PEL（无人处理）。

**方案**：`XAUTOCLAIM` 转移 idle 过久的 PEL 条目给当前 consumer。

**P1-6 修订（异步派发）**：原方案在 sweeper 调度线程同步 `handle(msg)`，claimed 消息（ETL 文档可达
数分钟）会阻塞 sweeper；且与 RetrySweeper 共调度器时互相拖延。改为：claim 后**派发到该 subscription
自己的 processingPool 异步执行**，sweeper 线程只负责 claim 与派发：

```
PelRecoverySweeper.drain()（独立调度器，见 §7）:
  // XAUTOCLAIM stream group consumer minIdleMs 0 COUNT batch
  claimed = xautoclaim(streamKey, group, selfConsumer, pelMinIdleMs, "0", batch)
  for msg in claimed:
    runner.dispatchToProcessingPool(() -> handle(msg))   // P1-6：异步，不阻塞 sweeper
```

> 若 PelRecoverySweeper 与 RetrySweeper 共调度器，需用**独立线程池**（各 sweeper 自带单线程
> scheduled executor），避免长任务互相阻塞。handle() 走正常路径（成功 XACK / 永久→DLQ / 可重试→§4）。

**minIdleMs** = `pelMinIdleMs`（默认 40min）> 最大处理时长（ETL `invisibleDuration`，默认 10min，
ETL 实际 30min），避免抢走正在处理的消息。多实例并发：XAUTOCLAIM 原子转移归属，天然安全。
**启动期断言**：`pelMinIdleMs > max(各 consumer invisibleDuration) + margin`，
margin ≥ 5min；40min − 30min = 10min margin，断言通过（§10）。

**与 RetrySweeper 的关系**：RetrySweeper 处理"已 XACK 转延迟队列"的消息（正常重试）；
PelRecoverySweeper 处理"未 XACK 留 PEL"的消息（崩溃场景）。两者互补，不重叠。

## 6. DeadLetterOperations — 首次落地

```
scanDeadLetters(topic, count):
  entries = xrevrange(dlqKey, "+", "-", count)   // 最新 N 条；dlqKey 含 group（§1）
  return entries.map(e -> decode(e.payload, topic))

replayDeadLetter(topic, messageId):
  data = xrange(dlqKey, messageId, messageId)    // 取原字段
  xadd(streamKey, MINID~{trimCursor} via StreamTrimTask, *, data.fields)   // 回灌主 stream（带 trim 策略）
  // 不从 DLQ 删除（审计保留，靠 MAXLEN 控制）

deadLetterCount(topic):
  return xlen(dlqKey)
```

所有 DLQ 写入（§3 permanent/unknown 分支、§4 retry 耗尽、本节 replay 回灌）统一经
`RedisStreamKeys.dlqKey(topic, group)` + `MAXLEN ~ {dlq-trim-threshold}`（P2-8）。

**多组扩展点**：当前 DLQ key 含 group（`dlq:{prefix}{topic}:{group}`），但 `DeadLetterOperations`
API 是 `scanDeadLetters(topic, count)`（无 group 参数）。当前 1:1 拓扑下需在 MessageBus 层用
"该 topic 唯一 group" 解析 key；未来多组场景需把 API 扩展为 `scanDeadLetters(topic, group, count)`。
本任务在 `RedisStreamDeadLetterOperations` 内留 `@Nullable String group` 入参的私有重载，
public API 暂用单 group 解析，并加注释标记扩展点。

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

### P2-9 关闭顺序（SmartLifecycle phase）

涉及三组资源：consumer 线程池、RetrySweeper/PelRecoverySweeper 调度器、bus bean。
**phase 顺序（数字越小越早 stop）**：

| 组件 | phase | stop 动作 |
|------|-------|-----------|
| RetrySweeper / PelRecoverySweeper | 早期（如 `SmartLifecycle.DEFAULT_PHASE - 200`） | 停止调度，drain 在途批次收尾 |
| RedisStreamConsumerRunner（PUSH/SIMPLE） | 中期（`DEFAULT_PHASE - 100`） | `running=false` → 唤醒 BLOCK 线程 → `shutdownNow` processingPool → `awaitTermination`（带超时，默认 30s） |
| `RedisStreamMessageBus` bean（destroyMethod=shutdown） | bean destroy | 关闭独立消费 `LettuceConnectionFactory`、清理所有 subscription |

`close()` 幂等（`AtomicBoolean`）。consumer pool `awaitTermination` 带超时，超时后 `shutdownNow` + log。
sweeper 在 consumer 关闭后停止，避免向已关 pool 派发（PelRecoverySweeper 的 processingPool 即 consumer
的 pool，故 sweeper 必须先于 consumer stop）。

### MessageBusManagement — child 2 契约（跨 child 冻结点）

`RedisStreamMessageBus` 实现（或装配为单独 bean）既有 `MessageBusManagement` SPI，新增：
- `boolean isCircuitBreakerOpen(String topic)`：返回 `circuitBreakerFor(topic).state() == OPEN`
  （暴露 per-topic `SendCircuitBreaker` 状态）。
- 既有 `Map<String,String> circuitBreakerState()`（topic→state 名）保留，作为 child 2
  `SharedCircuitBreakerGate` 的防御性二级回退。

**用途**：child 2 `SharedCircuitBreakerGate.isOpen(topic)` 在 Redis 不可用（共享信号 RBucket 读失败）时，
回退调 `busManagement.isCircuitBreakerOpen(topic)`（本实例本地熔断态）。这是 child 2 design §3.4 依赖的
冻结点，本任务必须落地（prd R7）。`circuitBreakerFor(topic)` 的 per-topic 实例缓存须可被本方法遍历查询。

## 8. 与既有 RocketMQ 类的映射（重构参照）

| RocketMQ 实现 | Redis 对应 | 复用程度 |
|--------------|-----------|---------|
| `RocketMQMessageBus` | `RedisStreamMessageBus` | 重写；复用 `MessagePayloadCodec`/`SendCircuitBreaker`/`MessagingMetrics` |
| `PushConsumerListener` | `RedisStreamConsumerRunner.handle()`（PUSH 分支） | 逻辑骨架复用（decode→handler→success/permanent/retryable） |
| `SimpleConsumerReceiveLoop` | `RedisStreamConsumerRunner.handle()`（SIMPLE 分支） | 重试计数**改为消息字段携带**（P0-2），Caffeine 仅作 metric |
| `RocketMQSubscription`/`PushSubscription`/`SimpleSubscription` | `RedisStreamSubscription` | 合并为一个（close 关线程池，幂等） |
| DLQ `sendToDeadLetter` (`:342`) | `XADD dlq:{topic}:{group} MAXLEN~trim` | 等价 + trim（P2-8） |
| `MessagingHealthIndicator` | 复用，health 检查改为 Redis `PING` + 活跃订阅 | 改探测目标 |
| —（新增） | `RedisStreamKeys` / `BackoffSchedule` / `ZSetDelayQueue` | 通用组件（评审"通用性"） |

## 9. 错误码与可观测扩展

`MessagingErrorCode` 新增（400012；**400013 预留给 child 2 `OUTBOX_INSERT_FAILED`**，码段协调见 child 2 design §8）：

| 码 | 常量 | 场景 |
|----|------|------|
| 400012 | `STREAM_OPERATION_FAILED` | XADD/XREADGROUP 等操作失败（transport 错误，非业务） |

> **码段协调修正**：GROUP_CREATE 失败（非 BUSYGROUP）属装配期/自治失败，`STREAM_TRIM_EXCEEDED`
> 属运维告警——两者均**不抛业务异常、不占 MessagingErrorCode 码段**（改记 metric，见下）。原评审
> 误将它们编入 400013/400014 会与 child 2 的 `OUTBOX_INSERT_FAILED`(400013) **码段冲突**，已收回
> （跨 child 一致性比对发现——两任务都动 `MessagingErrorCode`，码段必须单点协调）。

DLQ/retry sweeper 内部失败**不抛业务异常**（自治），但必须记 metric：
- `messaging.retry.orphan.count`（zset/hash 不一致孤儿清理）
- `messaging.retry.redelivered`（回灌成功）
- `messaging.deadletter.count`（进 DLQ）
- `messaging.stream.lag`（gauge：XLEN − Σ各组 XPENDING）
- `messaging.retry.unknown.failure`（handle 未知异常直接 DLQ）
- `messaging.consume.connection.failure`（pollLoop XREADGROUP 连接级失败，退避重连中，§3 Redis 故障韧性）
- `messaging.stream.group.create.failed`（XGROUP CREATE 失败非 BUSYGROUP；原拟占 400013，改记 metric，见上码段协调修正）
- `messaging.stream.trim.threshold.exceeded`（lag 超 trim-threshold 告警 counter；trim 告警改用 metric 而非错误码）

## 10. 配置

```yaml
app.messaging:
  backoff-ms: [1000,5000,10000,30000,60000,120000,180000,240000,300000,
               360000,420000,480000,540000,600000,1200000,1800000]   # BackoffSchedule 共享（§4.4）；child 2 relay 复用
  # 删除 app.messaging.rocketmq.* 段；无 backend 开关
  redis:                       # 唯一 MQ 配置
    stream-prefix: "stream:"
    dlq-prefix: "dlq:"
    retry-prefix: "retry:"
    retry-zset-prefix: "retry-zset:"
    consumer-name-prefix: "app:"        # consumer 名前缀可配（§1）
    trim-threshold: 100000              # P1-5：改为 lag 告警阈值（物理裁剪改 MINID，见 StreamTrimTask）
    dlq-trim-threshold: 50000           # P2-8：DLQ MAXLEN
    read-block-ms: 2000                 # XREADGROUP BLOCK（配合独立连接池，§3 P1-4）
    read-batch: 32                      # COUNT
    pel-min-idle-ms: 2400000            # 40min，> ETL invisibleDuration 30min + 10min margin（启动期断言；与 prd R8 一致）
    retry-poll-interval: 5s             # RetrySweeper/PelRecoverySweeper 扫描间隔
    trim-poll-interval: 60s             # P1-5：StreamTrimTask 周期
    max-attempts: 16                    # 消费端 RetrySweeper 重试窗口
    reconnect-backoff:                   # §3 Redis 故障韧性（pollLoop 退避重连）
      initial-ms: 1000                   # 首次失败后 sleep
      multiplier: 2.0                    # 指数因子
      max-ms: 30000                      # 封顶 30s
      jitter-factor: 0.2                 # ±20% 抖动，防多实例同步重连风暴
    retry-hash-ttl: 2h                  # P2-14：retry hash 字段 TTL（覆盖 max 退避窗口 ×2）
    consumer:                           # P1-4：独立连接池
      connection:
        share-native-connection: false
        pool:
          max-active: 32                # ≥ Σ 各 topic concurrency
          max-idle: 32
```

### 启动期断言（新增）

`MessagingProperties` 校验（失败即启动失败，fail-fast）：
1. `maxAttempts <= backoff-ms.size()`（或显式声明"超出档位=末档"，二选一写死）。当前 16=16，OK。
2. `pelMinIdleMs > max(各 consumer invisibleDuration) + 5min`。当前 ETL invisibleDuration 默认 10min
   （`ConsumerConfig.java#6B44:34`），ETL 实际配 30min；config 已配 40min（2400000ms），
   40min − 30min = 10min > 5min margin，断言通过。
3. `dlq-trim-threshold > 0`、`read-batch >= 1`。
   **sweep 粒度说明（非 fail-fast）**：`retry-poll-interval`（默认 5s）是 RetrySweeper drain 的调度粒度，
   首档退避 1s 在 5s sweep 下实际生效为"≤5s"（消息到期后等下一次 sweep）。这是可接受的精度（16 级退避
   总和 ~106min，5s 粒度仅影响首档）。若需首档精确 1s，把 `retry-poll-interval` 调到 ≤1s（代价：sweeper 更频繁）。
   不再强制 `retry-poll-interval < backoff[0]`（与默认 5s/1s 矛盾，会让启动 fail-fast）。

### 内存预估（P2-13）

单消息最大 payload ≈ P（含 headers）。稳态驻留上限（近似）：
- 主 stream（MINID 后保留）≈ trim 周期内已 ACK 区间，通常 < 1min 投递量。
- retry hash ≈ 失败积压 × P（TTL 2h 兜底）。
- dlq ≈ `dlq-trim-threshold × P`（MAXLEN 封顶）。
总驻留 ≈ 投递率 × 1min + 失败积压 × P + 50000 × P。运维需按峰值失败率反推 Redis 内存，
`maxmemory-policy noeviction` + AOF everysec（Constraints 已声明，本任务代码不依赖）。

## 11. 测试策略

| 测试类 | 覆盖 |
|--------|------|
| `RedisStreamMessageBusTest` | send 返回 entry ID；XADD 写入正确字段（含 `attempt=0`）；熔断 OPEN 抛异常 |
| `RedisStreamConsumerRunnerTest` | XREADGROUP→handle→XACK；PermanentConsume→DLQ（带 MAXLEN）；可重试→XACK+ZSET（P0-1）；未知异常→DLQ；pollLoop 失败后指数退避重连（成功即 reset）；多 consumer 退避不同步（jitter）；退避期不丢 PEL/retry（Testcontainers redis pause/resume） |
| `RetrySweeperTest` | 失败转 ZSET；退避计算；attempt 字段跨回灌累加（P0-2）；maxAttempts→DLQ；单 Lua 原子回灌（P1-3）；HGET null 孤儿清理（P2-7）；Testcontainers |
| `PelRecoverySweeperTest` | 模拟未 XACK，40min idle 后 XAUTOCLAIM 回收且**异步派发**（P1-6）（Testcontainers，短 minIdle 加速） |
| `RedisStreamDeadLetterOperationsTest` | scan/replay/count 三方法；DLQ MAXLEN 生效（P2-8）；多组扩展点（§6） |
| `BackoffScheduleTest` | next(attempt) 封顶最后一档；配置驱动（child 2 共用）；maxAttempts<=size 断言 |
| `ZSetDelayQueueTest` | enqueue/drain 原子抢占；多实例只一个 drain 成功（P2-11） |
| `StreamTrimTaskTest` | MINID 基于 XINFO last-delivered-id；积压不丢未投递消息（P1-5） |
| `RedisStreamKeysTest` | key 维度含 group（P2-10）；prefix/topic/group 组合 |
| 集成测试 | Testcontainers Redis，三条链路 send→consume 端到端；consumer 独立连接池（P1-4）；SmartLifecycle 关闭顺序（P2-9） |

> Testcontainers 已是本仓依赖；新增 Redis Testcontainer（`GenericContainer` + redis:8 镜像）。
