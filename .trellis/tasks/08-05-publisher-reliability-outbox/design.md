# Design — Outbox 装饰器 + Relay + Redisson 协调

> 前置阅读：`prd.md`。本文件聚焦技术边界、数据流、取舍。
>
> **Redisson 边界**：仅做运行时协调（leader election + 共享熔断信号）；消息体持久化在 PG，
> Redisson 不可用时优雅降级，不阻断功能。
>
> **跨 child 契约冻结点**（parent `08-05-redis-stream-mq-migration/prd.md` §跨 Child 契约）：
> - Child 1 在 `MessageBusManagement` SPI **新增 `isCircuitBreakerOpen(String topic)`**，供本任务
>   `SharedCircuitBreakerGate` 回退读取（见 §3.4）。
> - `MessageBusManagement.circuitBreakerState()` 既有（`Map<String,String>`，topic→state 名），
>   作为防御性二级回退。
> - `RedisStreamMessageBus.send()` 对 `traceparent` header 采"已存在不覆盖"策略（见 §5）。
> - `MessagingErrorCode`：Child 1 占用 `400012`（`STREAM_OPERATION_FAILED`），本任务从 **`400013`** 起（见 §8）。
> - `RedisStreamMessageBus.send()` 的 Redis 命令（`XADD` 等）由**既有全局 Lettuce 超时
>   `spring.data.redis.timeout: 3000ms`**（`application-dev/stable.yml`，非新增）兜底，单次 ≤3s。
>   relay 单 batch 顺序 send 32 行最坏 ~96s（有界，非无界阻塞）；顺序 send 不改并行——
>   `rag_index_document` 的 FIFO 由业务 `RLock(documentId)` 保证（child 1 已明确，Redis 不分区）。
>   （见 §3.2"性能"注。）
> - **`BackoffSchedule`（评审"通用性"P1）**：由 child 1 引入（`infrastructure/messaging/BackoffSchedule.java`，
>   `@Component` 读 `app.messaging.backoff-ms`），`RetrySweeper` 与本任务 `OutboxRelay` **共用同一 bean**——
>   退避策略单点配置。本任务 relay 仅注入复用，不再自带退避常量（见 §3.3）。
> - **`SendCircuitBreaker` 广播钩子（评审 P1-6.2）**：child 1 保持 `SendCircuitBreaker` 不变；本任务
>   扩展其构造器加 `@Nullable SharedCircuitBreakerGate`+`topic`，在 `tripOpen()`/状态迁移点调
>   `gate.broadcastOpen/broadcastClosed`（gate=null no-op），并在装配点注入（见 §3.4）。

## 0. 故障域与降级语义（修正：Redis 现在是 MQ 硬依赖）

**前提变化**：本任务（child 2）的 delegate 是 `RedisStreamMessageBus`（child 1）。Redis
不再仅是"旁路协调"，而是**消息投递链路的硬依赖**。

| Redis 状态 | Leader Election | 共享熔断 | MQ 投递 | Outbox 行 |
|-----------|----------------|---------|--------|-----------|
| 正常 | 仅 leader 扫描 | 跨实例同步 | RedisStream 投递成功 | 即时投递成功即 DELETE |
| Redis 挂 | 每实例都扫（降级） | 回退本地熔断 | **delegate.send() 全失败** | **行留 PG，不丢——恢复后 relay 补投** |

**组合方案的卖点**：Redis 挂 = 无法即时投递，但 outbox 行在 PG 不丢。Redis 恢复后 relay
自动补投所有积压行。这是"outbox + Redis MQ"组合相对于"裸 Redis MQ"的核心增益——
裸 Redis MQ 挂了消息就没了，outbox 给了第二层持久化保险。

> **关键修正（评审 P1-7）**：早期方案中"Redis 挂 → relay 每轮 bumpAttempts → 积压行耗尽
> attempts 转 dead → 7 天后清理 = 静默丢失"会令上述卖点在 MQ 连续故障 >~2h 时失效
> （16 级退避总和 ≈ 6,346s ≈ 106 分钟）。本设计在 §3.2 引入**熔断门控下的"冻结 attempts"**
> 语义：relay 在 `SharedCircuitBreakerGate.isOpen(topic)` 为真时**只顺延 `next_retry_at`，不递增
> `attempts`**，让 `attempts` 只统计"对看似健康的 MQ 的真实投递尝试"。这样连续 MQ 故障期间
> 积压行的 `attempts` 冻结、永不转 dead，卖点对任意时长的故障都成立。`dead` 只属于真正反复
> 投递失败的"毒消息"。

## 1. 架构定位

```
                          ┌─────────────────────────────────────────┐
   Publisher 层            │  ChatMessagePublisher / ChatUsageTracker │
   (业务逻辑零改动)        │  / EtlDispatchServiceImpl                │
                          │  调 messageBus.send(envelope)            │
                          │  catch 块: MessagingException → counter   │
                          └──────────────────┬──────────────────────┘
                                             │ MessageBus SPI
                          ┌──────────────────▼──────────────────────┐
   装饰器层 (新增)         │  OutboxMessageBus                        │
                          │  send(): INSERT outbox → 查共享熔断门控   │
                          │          → 即时投递(2次重试, 有界队列)     │
                          │  subscribe(): 委托 ↓                      │
                          └────────┬───────────────────┬────────────┘
                           INSERT   │                   │ delegate
                                    │                   ▼
                          ┌─────────▼────────┐  ┌─────────────────────┐
   存储层                  │  outbox 表 (PG)  │  │  RedisStreamMessageBus│  ← delegate（child 1）
                          │  消息体 + 状态    │  │  (Producer/Consumer)  │
                          └─────────┬────────┘  └─────────────────────┘
                                    │ scan (仅 leader)
                          ┌─────────▼────────────────────────────────┐
   Relay 层 (新增)         │  OutboxRelay (SmartLifecycle)             │
   Redisson 协调           │  + RedissonLeadership(RLock持续持有+看门狗)│
                          │  + 共享 OPEN 门控: RBucket(TTL=cooldown)   │
                          │  claim短事务 → 门控判断 → 事务外send →     │
                          │  DELETE短事务 → 退避 / dead               │
                          └───────────────────────────────────────────┘
```

**核心决策**：`OutboxMessageBus` 是 `MessageBus` 的**装饰器**（非替换）。publisher 注入的
`MessageBus` bean 换成 `OutboxMessageBus`（`@Primary`），调用链透明。
`subscribe()` / `shutdown()` / `deadLetterOperations()` 直接委托 delegate bus；`send()` 与 `sendAsync()`
经 outbox 托管（见 §2、§6.1）。

## 2. OutboxMessageBus.send() 时序

```
send(envelope):
  1. 校验 + 编码 payload → JSON（复用 MessagePayloadCodec）
  2. INSERT outbox(id, topic, payload, payload_type, tag, dedup_key, hash_key, headers,
                   status='pending', attempts=0, next_retry_at=now, created_at=now, updated_at=now)
       payload_type = envelope.payload().getClass().getName()   // relay 反序列化用（见 §4）
  3. best-effort 即时投递（不阻塞语义的关键）：
       if cbGate.isOpen(topic) → 跳过即时投递，行留 relay（快速返回）
       else:
         // spawn 前二次检查 gate，命中即不提交任务（省队列位）
         retryExecutor.execute(() -> sendWithRetry(envelope, outboxId, topic)):
           for i in 0..immediateRetryCount-1:
             try delegate.send(envelope) → 成功 → DELETE outbox 行 → return
             catch → sleep(immediateRetryIntervalMs) if i < last
           全失败 → 保留行（relay 回收），不抛异常
  4. return outboxId（作为"传输级 ID"占位）
```

**非阻塞保证**：步骤 3 在独立 `retryExecutor` 上异步执行，`send()` 在步骤 2 的 INSERT 完成后
即返回。即时投递失败不抛异常——行留 outbox 表，relay 兜底。

**即时投递 executor 必须有界（评审"错误处理"P1）**：MQ 故障期间 chat 热路径持续 `send()`，
每个请求会排队一个做 2 次 send + sleep 的任务。虚拟线程省线程，**不省任务堆积内存与对故障
Redis 的无效击打**。因此 `retryExecutor` 用**有界队列 + 拒绝策略**：
- 实现：`ThreadPoolExecutor(core=2, max=8, queue=ArrayBlockingQueue(64),
  RejectedExecutionHandler = 该任务的 AbortPolicy 捕获后仅 log.debug)`，**而非**
  `newVirtualThreadPerTaskExecutor()`（无界）。线程命名 `outbox-immediate-N`。
- **拒绝语义天然正确**：拒绝 = 行不投递，**继续留 PG**，relay 回收。不丢消息。
- **spawn 前二次检查 gate**（步骤 3 的 `if`）：OPEN 时连任务都不提交，避免把注定失败的 send
  塞进队列挤占配额。
- `@PreDestroy`：`shutdown()` + `awaitTermination(5s)`；**超时未完成的任务丢弃是安全的**
  （行留 PG，relay 兜底），文档化此点而非留给实现臆断。

> 虚拟线程用错场景的教训：虚拟线程适合"大量阻塞 IO 的并发"，不适合"故障期无限堆积的
> 重试任务"。有界 ThreadPoolExecutor 把背压还给调用方（拒绝 = 退回 relay），是这里的正确选择。

**hashKey 持久化**：步骤 2 的 INSERT 包含 `hash_key` 列（供 relay 重建 envelope 时恢复，
避免 `rag_index_document` 的 FIFO 语义退化——见 §4 注）。

**payload_type 持久化（评审 P1-4）**：步骤 2 写入 `payload_type`。relay 重建时用
`Class.forName(row.payloadType())` 反序列化（chat/usage/etl 三类 payload 不同，无此列则 relay
不知道按哪个类解码——`JacksonMessageCodec.decode(byte[], Class<T>)` 必须显式传目标类）。

**tag 持久化（评审 P1-8）**：步骤 2 写入 `tag`（当前三个 publisher 不用 tag，但
`MessageEnvelope.tag` 是传输元数据，列缺失会导致未来加 tag 即丢；加列成本极低）。

**与熔断器的关系**：即时投递前查 `SharedCircuitBreakerGate.isOpen(topic)`（见 §3.4）。
OPEN 时跳过即时投递，行留 relay。

## 3. OutboxRelay 设计

`implements SmartLifecycle`，`getPhase()` 设为 `DEFAULT_PHASE - 50`（在 destroyMethod 之前 stop）。

### 3.1 RedissonLeadership 组件（独立可单测 — 评审 P0-1/P0-2/"通用性"P1）

**问题**：早期伪代码把锁获取（`leaderThread`）与 drain 调度（`scheduler` 线程）拆到两个线程，
却用 `leaderLock.isHeldByCurrentThread()` 判定 leader——该 API 比对的是**当前线程的 threadId**，
scheduler 线程恒不是持锁线程，判定**恒为 false**：
- 若实现"忠实"于伪代码（`leaderLock == null || !isHeldByCurrentThread()` → 也 drain）：每实例
  都跑 drain，leader election 完全失效。
- 若实现时"修正"成只 `isHeldByCurrentThread()` 才 drain：relay **永不 drain**，积压永不投递——
  静默完全故障。

同时早期 `stop()` 的 `if (running) lock.unlock()` 条件写反（stop 先置 running=false → 永不 unlock），
正常停机不解锁，follower 要等看门狗 30s 才接管。

**修复**：抽出 `RedissonLeadership` 组件，收敛线程归属与 unlock 问题到一个可单测单元。

```java
/** 持续持锁的 leader election 组件。看门狗续约，崩溃 ~30s 释放。 */
class RedissonLeadership {
    private final RedissonClient redisson;          // nullable：不可用时降级
    private final String lockKey;
    private final Clock clock;
    private final Consumer<Boolean> onLeadershipChange;  // leader 变化回调（true=获得）

    private volatile boolean leader = false;        // ← 唯一权威 leader 标志
    private final AtomicReference<RLock> heldLock = new AtomicReference<>();  // 仅持锁后赋值
    private volatile boolean running = true;
    private Thread leaderThread;                    // daemon + 命名

    void start() {
        if (redisson == null) { leader = true; return; }   // 无 Redisson → 降级"每实例都扫"
        leaderThread = Thread.ofPlatform()
            .daemon().unstarted(this::holdLeadership);
        leaderThread.setName("outbox-relay-leader");
        leaderThread.start();
    }

    boolean isLeader() { return leader; }           // tryDrainIfLeader 只查这个

    private void holdLeadership() {
        while (running) {
            RLock lock = redisson.getLock(lockKey);
            try {
                // tryLock 超时判定（评审 P2）：比 lock(-1) 在 Redis 抖动时行为更可预测、可测
                if (!lock.tryLock(5, -1, TimeUnit.SECONDS)) continue;  // 5s 拿不到 → 重试
                heldLock.set(lock);
                leader = true;
                onLeadershipChange.accept(true);
                log.info("Acquired relay leadership");
                try {
                    while (running) Thread.sleep(pollInterval.toMillis());
                } finally {
                    // stop()/异常退出统一走这里解锁（评审 P0-2 修正）
                    leader = false;
                    unlockQuietly(lock);             // 吞 IllegalMonitorStateException（锁已失）
                    heldLock.set(null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // 恢复中断标志
                break;
            } catch (Exception e) {                   // Redis 故障 / lock 抛异常
                leader = false;
                log.warn("Leadership lost, retry in 5s: {}", e.getMessage());
                sleepUninterruptibly(5, TimeUnit.SECONDS);
            }
        }
    }

    private void unlockQuietly(RLock lock) {
        try { if (lock.isHeldByCurrentThread()) lock.unlock(); }
        catch (IllegalMonitorStateException e) { log.warn("Lock already released: {}", e.getMessage()); }
    }

    void stop() {
        running = false;
        if (leaderThread != null) leaderThread.interrupt();   // 唤醒 sleep
    }
}
```

**OutboxRelay 用法**：
```
start():
  leadership.start()
  scheduler = scheduleAtFixedRate(this::tryDrainIfLeader, pollInterval, pollInterval)

tryDrainIfLeader():
  if leadership.isLeader(): drain()      // 只查 volatile isLeader，不碰 isHeldByCurrentThread
```

**锁语义**：锁在 `start()` 时获取并**持续持有**（看门狗每 10s 续约，默认 30s lease），不是每轮
drain 重新竞拍。
- **leader 崩溃（JVM 死）**：看门狗停止续约 → 锁 ~30s 自动释放 → 其它实例的 `holdLeadership()`
  的 `tryLock(5, ...)` 立即获取。**接管延迟 ≤ 看门狗超时(30s)**。
- **正常 stop()**：`running=false` → 内层 while 退出 → `finally` 中 `unlock()` → 其它实例立即获取。
  （修正早期"条件写反永不 unlock"的 bug。）
- **`FOR UPDATE SKIP LOCKED` 仍保留**：作为 leader 切换瞬间的最后防线（看门狗超时但旧 leader
  的 drain 未结束）——belt-and-suspenders，**尽力减少重复投递，最终由消费端幂等保证**。

**Redis 不可用降级**：`redisson == null` 时 `leader=true`（每实例都 drain，正确性不变，仅 DB
压力回升）；`tryLock`/`lock` 抛异常 → catch → 5s 后重试，期间 `leader=false`（不 drain，等
Redis 恢复后重新抢锁）。

**资源释放（评审"资源释放"P1）**：
- `leaderThread` 是 **daemon + 命名**（`outbox-relay-leader`）：非 daemon 线程在 stop() 未执行
  （Spring 上下文异常关闭）时阻止 JVM 退出；命名便于线程 dump 排障。
- `stop()` 顺序：`running=false → interrupt() → scheduler.shutdown()`；drain 可能正在
  `delegate.send()` 网络阻塞，`shutdown()`（非 shutdownNow）会等它完成，故
  `awaitTermination(5s)` 带超时。
- retryExecutor 关闭策略：`shutdown() + awaitTermination(5s)`，**超时丢弃队列任务安全**
  （行留 PG，relay 兜底）——见 §2。

> **与 EtlDispatchServiceImpl 锁模式的区别（评审"通用性"P2）**：PRD 原说"复用 EtlDispatchServiceImpl
> 锁模式"，但该类是短命 `tryLock(30s) + finally unlock`，与 relay 的**持续持锁**是两种语义——
> 不是复用，是重写。抽出 `RedissonLeadership` 把线程归属（P0-1）与 unlock（P0-2）收敛到可单测
> 组件，是这里的正确工程决策。

### 3.2 drain() 主体（事务拆分 + 熔断门控冻结 attempts + 异常隔离 + drain-until-empty）

**修正**：claim → send → delete **不在同一事务内**。send 是网络调用，放事务内会在 MQ 卡住时
长时间持有 PG 行锁。拆为多步。**drain 整体 try/catch** 防止单次异常杀死调度（评审 P0-3）。

```
tryDrainIfLeader():                       // scheduler 调用入口
  try { drainUntilEmpty() }
  catch Throwable e: log.warn("drain failed, will retry next poll", e)
  // ↑ 关键（评审 P0-3）：scheduleAtFixedRate 任务抛未捕获异常 → 后续执行被永久抑制。
  //   一次 DB 抖动不能杀死 relay 直到进程重启。整层吞掉、记日志、下轮继续。

drainUntilEmpty():                         // 评审"性能"P1：单次 poll 内循环 claim，防积压恢复过慢
  for batchNo in 0..maxBatchesPerPoll-1:   // 默认 10，防饿死其它工作
    rows = claimBatch()
    if rows.isEmpty(): break               // 本轮清空，退出
    processBatch(rows)

claimBatch():
  // Step A: claim（短事务，持锁 ~1ms）
  tx1 = TransactionTemplate
  rows = tx1.execute(status -> mapper.claimPending(batchSize, now, claimingTimeoutSeconds))
       // FOR UPDATE SKIP LOCKED + UPDATE status='claiming', updated_at=now
  tx1.commit()                             // 行锁释放

processBatch(rows):
  // Step B: 事务外 send（MQ 网络 IO，可能卡数秒）
  deliveredIds = []
  for row in rows:
    // ★ 熔断门控冻结 attempts（评审 P1-7）★
    if cbGate.isOpen(row.topic):
      // MQ 看似不可用 → 不 send、不递增 attempts，仅顺延 next_retry_at，释放回 pending
      continue                            // 留到 Step C 的 gate-defer 分支处理
    try:
      envelope = decode(row)              // 恢复 tag + hashKey + headers（含存储的 traceparent）
      delegate.send(envelope)
      deliveredIds.add(row.id)
      metrics.delivered.increment()
    catch Throwable:
      bumpOrMarkDead(row)                 // 真实投递失败 → 递增 attempts
  // Step C: 批量 DELETE（评审"性能"P4：合并为 DELETE WHERE id IN (...)）
  if !deliveredIds.isEmpty():
    tx2.execute(status -> mapper.deleteByIds(deliveredIds))
  // gate-defer：OPEN 期间顺延的行
  deferIds = [row.id for row in rows if cbGate.isOpen(row.topic)]
  if !deferIds.isEmpty():
    tx2.execute(status -> mapper.deferForRetry(deferIds, now + gateDeferInterval))
       // UPDATE SET status='pending', next_retry_at=now+gateDeferInterval WHERE id IN (...)
       // 注意：attempts 不变（冻结）

bumpOrMarkDead(row):                      // 仅真实投递失败调用
  nextAttempt = row.attempts + 1
  if nextAttempt >= maxAttempts:
    mapper.markDead(row.id, reason)       // 重试耗尽 → 死信（真正的毒消息）
    metrics.dead.increment()
  else:
    mapper.bumpAttempts(row.id, nextAttempt,
                        next_retry_at = now + backoffSchedule.next(nextAttempt),
                        status='pending')  // 释放回 pending
    metrics.failed.increment()
```

**at-least-once 语义说明**（修正重复投递表述）：
- send 成功 + DELETE 失败/崩溃 → 行残留 → 下轮 relay 重复投递 → **消费端 SETNX 幂等兜底**。
- 即时投递（异步线程）与 relay 并发处理同一行 → 双投递 → **同上，消费端幂等兜底**。
- **`FOR UPDATE SKIP LOCKED` 只防并发 claim 同一行，不消除重复投递**。文档表述应明确：
  尽力减少重复投递，最终由消费端 `IdempotentHandler`（SETNX）+ DB 唯一约束保证。
  这是 at-least-once 的固有权衡，不是 bug。

**drain-until-empty（评审"性能"P1）**：单次 poll 只 drain 一个 batch（32 行）、poll 间隔 5s →
峰值恢复 6.4 msg/s；故障 1h 积压 6k 行要 ~15 分钟恢复。改为**同一 poll 内循环 claim 直到清空**
（上限 `maxBatchesPerPoll` 默认 10，防单实例长时间持锁饿死其它工作 + 给 leader 心跳/stop 机会），
峰值恢复提到 ~64 msg/s。`maxBatchesPerPoll` 可配（积压严重时调大）。
**顺序 send 不并行（评审"性能"P2）**：单 batch 32 行 × 每次 `XADD` RTT；Redis 半死不活时线性放大
阻塞时间。但**不改并行**——`rag_index_document` 同 hashKey 的顺序对其有意义（FIFO 由业务
`RLock(documentId)` 保证，Redis 不分区，child 1 已明确）。前提是 `delegate.send()` 有界超时
（child 1 依赖既有全局 Lettuce 超时 `spring.data.redis.timeout: 3000ms`，见顶部跨 child 契约），否则一次故障轮询会阻塞 32×3s ≈ 96s。
本任务在 relay 侧不额外加 send 超时（信任 delegate 契约），但 `stop()` 的 `awaitTermination(5s)`
是最后兜底（§3.1）。

**熔断门控冻结 attempts 的语义边界（评审 P1-7）**：
- `gate.isOpen(topic)` 为真 = "本实例认为 MQ 不可达"。此时 relay **不 send**（避免对故障 Redis
  无效击打）、**不递增 attempts**（MQ 停机不应消耗消息寿命），只把 `next_retry_at` 顺延一个短
  间隔（`gateDeferInterval`，默认 = `pollInterval`）。
- `gate` 在 Redis 不可用时回退到**本实例本地 `SendCircuitBreaker`** 状态（§3.4）。MQ 真停时，
  即时投递路径的失败会 trip 本地熔断 OPEN，relay 随即看到 OPEN → 冻结 attempts。
  故 **Redis 全挂场景下 attempts 仍被正确冻结**（卖点对任意时长故障成立）。
- `attempts` 只在"gate 认为 MQ 可用、但 delegate.send() 真失败"时递增。`dead` 只属于反复真实
  投递失败的毒消息，不再因 MQ 停机被误杀。
- gate↔breaker 状态机共存（评审"错误处理"P2）：gate 的 2s 本地缓存是"读快照"，breaker 的
  cooldown 是"状态机迁移"。两者不冲突：gate 缓存过期后会重新读 breaker/Redis 最新态。relay
  冻结 attempts 期间不向 breaker 反馈（不 send），breaker 的 HALF_OPEN 探测靠即时投递路径
  （`OutboxMessageBus.send()` 的新消息）触发，relay 不参与探测。

**claim 的 status 流转**：pending → claiming（短事务 A 内 UPDATE + 刷新 updated_at）→ send →
delete / 回退 pending。claiming 状态防止同实例其它线程重复 claim（SKIP LOCKED 锁 + status 双
保险）。崩溃的 claiming 行由 `claimPending` 的超时回收。

### 3.3 BackoffSchedule（共享退避 — 评审"通用性"P1）

**问题**：退避表有三份独立实现——`ChatMessagePublisher.DEFAULT_BACKOFF_MS`（200/1000/3000，
本任务将删除）、child 1 `BACKOFF_MS`（16 级）、本任务 relay 退避表（16 级，与 child 1 逐值
相同）。未来调退避策略要改三处。

**方案（所有权在 child 1）**：`BackoffSchedule`（`infrastructure/messaging/BackoffSchedule.java`，
`@Component` 读 `app.messaging.backoff-ms`，`next(attempt)` 封顶最后一档）**由 child 1 引入**
（其 `RetrySweeper` 注入，见 child 1 design §4）。本任务 `OutboxRelay` **注入同一 bean 复用**，
`bumpOrMarkDead` 调 `backoffSchedule.next(nextAttempt)`，不自带退避常量。两个 child 共用同一
配置段，退避策略单点配置。

```java
// child 1 引入；本任务仅注入复用
@Component
class BackoffSchedule {
    private final long[] delaysMs;                 // 来自配置 app.messaging.backoff-ms
    long next(int attempt) {
        return delaysMs[Math.min(attempt, delaysMs.length - 1)];   // 封顶最后一档
    }
}
```

默认值（16 级，与 child 1 一致；publisher outbox 重试与消费端重试窗口相互独立，非"对齐"）：
`[1s,5s,10s,30s,1m,2m,3m,4m,5m,6m,7m,8m,9m,10m,20m,30m]`。

> child 1 的 `RetrySweeper` 与本任务 relay 都注入同一 `BackoffSchedule` bean（child 1 引入），
> 退避策略单点配置。child 1 design §4 / implement Step 5 已采纳（`BACKOFF_MS` 常量 → `BackoffSchedule`）。

### 3.4 共享 OPEN 熔断信号（SharedCircuitBreakerGate）

**问题**：当前 `SendCircuitBreaker`（`SendCircuitBreaker.java:19-21`）是 per-instance 内存态。
多实例下 app-1 熔断 app-2 不知，继续对已挂 MQ 发起无效 send。

**方案**：只**共享 OPEN 信号**，不重写状态机。加本地缓存减少 RTT。

```
// isOpen(topic) 加 2s 本地缓存，避免每消息一次 Redis RTT
private final Map<String, CachedState> localCache = new ConcurrentHashMap<>();
record CachedState(boolean open, long expiresAt) {}

isOpen(topic):
  cached = localCache.get(topic)
  now = clock.millis()
  if cached != null && cached.expiresAt() > now:
    return cached.open                    // 本地缓存命中（2s TTL）
  // 缓存 miss → 读 Redis
  try:
    open = redisson.getBucket(cbSignalPrefix+topic).isExists()
  catch RedisException:
    open = busManagement.isCircuitBreakerOpen(topic)   // 回退本地态（parent 契约：child 1 新增此方法）
  localCache.put(topic, new CachedState(open, now + cbLocalCacheTtlMs))
  return open                              // ↑ 评审 P2：只调一次回退，put 后单次 return

broadcastOpen(topic):                      // 由 delegate 的 SendCircuitBreaker.tripOpen() 触发（见下）
  localCache.put(topic, new CachedState(true, clock.millis() + cbLocalCacheTtlMs))
  try:
    redisson.getBucket(cbSignalPrefix+topic).set("1", Duration.ofMillis(cooldownMillis))
  catch RedisException e:                  // 评审 P1-6.3：Redis 挂时降级，不破坏 send 链路
    log.warn("broadcastOpen fallback to local cache only: {}", e.getMessage())

broadcastClosed(topic):                    // HALF_OPEN 探测成功 → CLOSED 时触发
  localCache.put(topic, new CachedState(false, clock.millis() + cbLocalCacheTtlMs))
  try:
    redisson.getBucket(cbSignalPrefix+topic).delete()
  catch RedisException e:
    log.warn("broadcastClosed fallback to local cache only: {}", e.getMessage())
```

**广播触发点契约（评审 P1-6.2 — 跨 child 冻结点）**：现有 `SendCircuitBreaker` 嵌在 delegate
（`RedisStreamMessageBus.circuitBreakerFor()`）内部，trip 时不会自动广播。需定义契约：
- `SendCircuitBreaker` 构造器注入 `@Nullable SharedCircuitBreakerGate gate` + `String topic`
  （nullable 防循环依赖 / 测试隔离）。
- `tripOpen()`（`SendCircuitBreaker.java:80`）末尾：`if (gate != null) gate.broadcastOpen(topic)`。
- `recordSuccess()` 从 `HALF_OPEN → CLOSED`（`SendCircuitBreaker.java:51-61`）：末尾
  `if (gate != null && prev == HALF_OPEN) gate.broadcastClosed(topic)`。
- 这些调用在 `synchronized` 块内、`broadcastOpen/Closed` 内部已 try/catch 降级，**不破坏 send 链路**。
- 这是 child 1 的 `RedisStreamMessageBus` 装配点必须注入 gate 的冻结点（child 1 design 需注明）。

**本地缓存 2s TTL**：chat 热路径避免每消息 ~0.1-0.5ms Redis RTT。2s 内最多一次 RTT，
熔断状态变化延迟 ≤2s 可接受（outbox 行兜底）。topic 集合有界（3 个），`ConcurrentHashMap`
不清理无泄漏。

**降级**：Redis 异常 → 回退本实例 `busManagement.isCircuitBreakerOpen(topic)`（parent 跨 child
契约：child 1 在 `MessageBusManagement` 新增此方法）。防御性二级回退：若该方法不存在，
从 `circuitBreakerState().get(topic)` 推导（`"open".equals(state)`）。

## 4. 数据模型 — outbox 表（修正：加 payload_type/tag/hash_key，删无用索引/列）

```sql
-- V23__outbox.sql（V10-V22 已占用）
CREATE TABLE IF NOT EXISTS outbox (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    topic         VARCHAR(128)  NOT NULL,           -- 裸 topic 名
    payload       JSONB         NOT NULL,           -- 编码后的 payload
    payload_type  VARCHAR(255)  NOT NULL,           -- 评审 P1-4：payload.getClass().getName()，relay 反序列化用
    tag           VARCHAR(255),                     -- 评审 P1-8：MessageEnvelope.tag（当前三 publisher 不用，可空，未来加即不丢）
    dedup_key     VARCHAR(255),                     -- 幂等键（可空）
    hash_key      VARCHAR(255),                     -- 有序分区键（rag_index_document 用 documentId，可空）
    headers       JSONB         NOT NULL DEFAULT '{}',  -- 含存储的 traceparent/Content-Type 等
    status        VARCHAR(16)   NOT NULL DEFAULT 'pending',  -- pending / claiming / dead
    attempts      INT           NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    failure_reason TEXT,                             -- dead 时的异常摘要
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()   -- claiming 超时回收用；claim 时刷新
);
-- relay 扫描热路径（pending/claiming）
CREATE INDEX IF NOT EXISTS idx_outbox_claim ON outbox (status, next_retry_at, created_at)
    WHERE status IN ('pending', 'claiming');
-- 评审"性能"P3：dead 清理任务的部分索引（避免 dead 行全表扫）
CREATE INDEX IF NOT EXISTS idx_outbox_dead_cleanup ON outbox (status, created_at)
    WHERE status = 'dead';
```

**修正项**：
- **`payload_type` 列新增（评审 P1-4）**：`JacksonMessageCodec.decode(byte[], Class<T>)` 必须显式
  传目标类（`MessagePayloadCodec` 无类型信息内嵌，`subscribe()` 也是显式传 `payloadType`，
  注释"needed due to type erasure"）。chat/usage/etl 三个 payload 类不同，relay 不知道按哪个类
  反序列化。INSERT 时写 `envelope.payload().getClass().getName()`，relay 重建时
  `Class.forName(row.payloadType())`。**扩展性收益（评审"扩展性"P1）**：新增 topic 只需其
  payload 类在 classpath 上，relay 零代码改动即可反序列化——无需维护 topic→Class 注册表
  （新增 topic 要改注册表代码，扩展性差）。
- **`tag` 列新增（评审 P1-8）**：`MessageEnvelope.tag`（broker filter tag）原 INSERT 列清单缺失，
  relay 重建传 null。当前三个 publisher 均不用 tag（已核实），但数据模型不完整。加列成本极低，
  未来加 tag 即不丢。
- **`hash_key` 列新增**：`rag_index_document` 的 hashKey（=documentId）必须持久化。relay 重建
  envelope 时恢复 hashKey，否则 child 1 的 `RedisStreamMessageBus` 无法写入 stream 的 hashKey
  字段（业务层 `RLock` 虽然保证了串行，但 hashKey 是 stream record 的元数据，需完整传递）。
- **删除 `sent_at` 列和 `idx_outbox_sent_cleanup`**：投递成功即 `DELETE`，无 `sent` 态，
  该索引无消费者。
- **`status` 增加 `claiming`**：relay 事务 A 内 claim 时标记，防同实例重复 claim；崩溃的
  claiming 行由 `claimPending` 查询超时回收（`status='claiming' AND now - updated_at > claiming-timeout`）。
- **`updated_at` 列新增**：claiming 超时回收的时间依据；**claim 的 UPDATE 必须刷新 `updated_at`**
  （否则超时回收依赖的 updated_at 停留在 INSERT 时间）。
- **`idx_outbox_dead_cleanup` 新增（评审"性能"P3）**：清理任务 `WHERE status='dead' AND created_at < ...`
  在 `idx_outbox_claim`（`WHERE status IN ('pending','claiming')`）之外——dead 行只能全表扫。
  补 `(status, created_at) WHERE status='dead'` 部分索引。

**claimPending 查询修正（评审 P2 — 绑定参数，不再硬编码 INTERVAL）**：
```sql
SELECT * FROM outbox
WHERE (status = 'pending' AND next_retry_at <= #{now})
   OR (status = 'claiming' AND #{now} - updated_at > (#{claimingTimeoutSeconds} || ' seconds')::interval)
ORDER BY created_at LIMIT #{limit} FOR UPDATE SKIP LOCKED
```
> `claimingTimeoutSeconds` 来自配置 `app.messaging.outbox.claiming-timeout-seconds`（默认 300），
> **单一来源**，不再 SQL 内硬编码 `INTERVAL '5 minutes'` 双源。claim 的 UPDATE 同事务内
> `SET status='claiming', updated_at=now`（刷新 updated_at）。

**relay 重建 envelope 时恢复 tag + hashKey + payload_type 反序列化 + 存储的 traceparent**：
```java
Class<?> payloadType = Class.forName(row.payloadType());
MessageEnvelope<?> envelope = new MessageEnvelope<>(
    null, row.topic(),
    row.tag(),              // 恢复 tag（修正：之前传 null 丢失）
    codec.decode(row.payload(), (Class) payloadType),   // payload_type 驱动反序列化
    row.hashKey(),          // 恢复 hashKey（修正：之前丢失）
    row.dedupKey(),
    row.headers(),          // 恢复存储的 headers（含 publisher 的 traceparent）
    row.createdAt().toEpochMilli()
);
```

## 5. traceId 传播修正

**问题**（`RocketMQMessageBus:502-503`）：headers.forEach(addProperty) 之后执行
propagator.inject()——relay 线程注入的是 relay 自己的 trace context，覆盖 publisher 存的 traceparent。

**修正方案**：
- **outbox INSERT 时**：headers JSONB 存储的是 publisher 线程的 `propagator.inject()` 结果
  （即 publisher 的 traceparent）。
- **relay 重建 envelope**：用存储的 headers 直接构造 envelope，**不重新调 propagator.inject()**。
- **delegate.send() 内部**（`RedisStreamMessageBus.send()`，child 1 design §2 已采纳）：
  inject 时**跳过已存在的 header**——
  `if (!headersMap.containsKey("traceparent")) headersMap.putAll(propagator.inject())`。
  这样 relay 线程的 trace context 不会覆盖存储的 traceparent。

**跨 child 契约（已冻结）**：`RedisStreamMessageBus.send()` 在 buildRecord 时对 traceparent header
采用"已存在不覆盖"策略。child 1 的 design §2 已注明此约束（"traceparent 跨层契约"段）。

## 6. 装饰器边界

| `MessageBus` 方法 | OutboxMessageBus 行为 | 理由 |
|---------------------|----------------------|------|
| `send(envelope)` | **托管**：outbox INSERT + 共享熔断门控 + 异步即时投递 | 可靠性核心 |
| `sendAsync(envelope)` | **托管**：outbox INSERT + 异步即时投递（future 由投递任务完成） | CompletableFuture 语义 + 可靠性（见 §6.1） |
| `subscribe(...)` | **委托** delegate | outbox 只管 send 侧 |
| `sendAfterCommit(envelope)` | 默认实现 → `send()` → 经 outbox | 透明继承 |
| `shutdown()` | **委托** delegate + 关闭 relay/executor | 双向关闭 |
| `deadLetterOperations()` | **委托** delegate | 消费端 DLQ |

### 6.1 sendAsync 的 outbox 托管（评审"扩展性" sendAsync 绕过 outbox）

**问题**：若 `sendAsync` 直接委托 delegate，则**静默绕过 outbox 持久化**——未来调用方会以为有
outbox 保护，实则是裸 `RedisStreamMessageBus.sendAsync()`，MQ 故障即丢。当前无生产调用方（已核实，
仅测试用），但这是潜伏的可靠性空洞。

**决策（fix-at-source）**：`sendAsync` 同样走 outbox INSERT + 异步即时投递，`CompletableFuture`
由投递任务完成。这不是"外加特性"，而是 `send()` 同款逻辑的 `CompletableFuture` 包装——复用同一
`retryExecutor` 与 `sendWithRetry`，几乎无增量代码。

```
sendAsync(envelope):
  outboxId = INSERT outbox(...)                    // 同步持久化（同 send() 步骤 2）
  if !enabled: return delegate.sendAsync(envelope)
  if cbGate.isOpen(topic):
    return CompletableFuture.completedFuture(outboxId)   // OPEN：行留 relay，future 立即完成
  future = new CompletableFuture<>()
  retryExecutor.execute(() -> {                    // 复用 send() 的有界 executor
    try {
      id = sendWithRetry(envelope)                 // 2 次重试（同 send()）
      deleteOutbox(outboxId)
      future.complete(id)                          // 成功 → delegate 传输 ID
    } catch (retriesExhausted e) {
      future.completeExceptionally(e)              // 即时投递未成功；行留 relay（不丢），future 诚实反馈
    }
  })
  return future
```

**契约差异（关键，需文档化）**：`sendAsync` 的 future 表示**即时投递的 best-effort 结果**，与 `send()`
的"同步返回 outboxId、失败不抛（留行）"是两种语义——这符合 `MessageBus` SPI：`send()` 同步
fire-and-persist（返回 outboxId，投递失败不抛，relay 兜底）；`sendAsync()` 异步投递，future 在即时
投递成功时 complete(delegateId)、即时重试耗尽时 completeExceptionally——**但消息不丢**（行留 relay 补投）。
调用方据此区分"已即时送达"与"待 relay"。

**executor 拒绝处理**：`retryExecutor.execute` 拒绝时（队列满），
`future.completeExceptionally(RejectedExecutionException)` + 行留 relay（不丢）。与 `send()` 的
拒绝语义一致（拒绝 = 退回 relay）。

### 6.2 @Primary 装配与 delegate 构造顺序（评审"扩展性" @Primary）

两个 `MessageBus` bean 并存：内部 `redisStreamMessageBus`（无 `@Primary`）+ `@Primary outboxMessageBus`。
**构造顺序约束**：`OutboxMessageBus` 构造器引用 delegate（`MessageBus delegate`），Spring 按 bean 依赖
图保证 delegate 先构造——`@Bean MessageBus outboxMessageBus(MessageBus delegateBus, ...)` 形参注入即
满足。**注意**：不要用字段注入 `@Autowired MessageBus`（会注入自己 → 循环），必须构造器形参显式注入
**内部 delegate bean 名**（`@Qualifier("redisStreamMessageBus")` 或单参 `MessageBus` 形参，由 Spring
解析到非 `@Primary` 的候选）。装配代码须注明此点（见 implement Step 7）。

**sendAfterCommit 语义注释（评审"错误处理"P2）**：默认实现直接 `send()`。若调用方在事务内调用，
outbox INSERT 会**加入调用方事务**（同连接）。本场景 publisher 侧无业务写（prd §Constraints 已
声明），故 INSERT 是独立短事务。但需在 `OutboxMessageBus.sendAfterCommit` 显式注释
"调用方不应在持有业务事务时调用；若需事务性，应确保 INSERT 不与长事务共用连接"，避免未来误用。

## 7. Publisher 改动范围（修正：非"零改动"）

**修正表述**：publisher **业务逻辑零改动**，但 catch 降级路径**必须改**（移除旧降级方法 → 改 counter）。

| Publisher | 业务逻辑 | catch 块改动 |
|-----------|---------|-------------|
| `ChatMessagePublisher` | 不改 | 删 `saveWithBoundedRetry()` / `DEFAULT_BACKOFF_MS` / `backoffMs` 字段 → 改 `counter("chat.save.publish_failed")` |
| `ChatUsageTracker` | 不改 | catch 内加 `counter("chat.usage.publish_failed")` |
| `EtlDispatchServiceImpl` | 不改 | 删 `dispatchViaThreadPool()` 调用 → 改 `counter("rag.etl.publish_failed")` |

outbox 正常时 `send()` 不抛（失败留行），catch 几乎不触发——仅防御 outbox INSERT 失败（DB 硬故障）。

## 8. 错误码扩展

`MessagingErrorCode` 新增（评审 P1-5：避让 child 1 已占用的 `400012`）：

| 码 | 常量 | 场景 |
|----|------|------|
| **400013** | `OUTBOX_INSERT_FAILED` | outbox 行 INSERT 失败（DB 硬故障），抛给 publisher catch |

> child 1 `08-05-redis-stream-message-bus` design §9 已声明 `400012 = STREAM_OPERATION_FAILED`。
> 两任务都动 `MessagingErrorCode` 枚举，码段必须协调——本任务用 **400013**。

## 9. 清理任务 + 索引

`OutboxCleanupScheduler`（`@Scheduled(cron = "${app.messaging.outbox.cleanup-cron:0 0 4 * * *}")`，
cron 外部化，评审"扩展性"硬编码）：
`DELETE FROM outbox WHERE status='dead' AND created_at < now() - INTERVAL '7 days'`（`7` 来自 `dead-retention-days`）。
走 `idx_outbox_dead_cleanup`（§4 新增的部分索引，避免 dead 行全表扫）。
（claiming 超时行由 relay 的 claimPending 查询自动回收，不归此清理任务。）

> **dead 行语义（配合 §3.2 P1-7 修正）**：dead 只属于"反复真实投递失败的毒消息"
> （attempts 在 gate 认为可用时仍反复 send 失败耗尽 maxAttempts）。MQ 停机期间 attempts 冻结、
> 不转 dead，故 7 天清理不会误删因 MQ 故障积压的行。

## 10. 配置项

```yaml
app.messaging:
  backoff-ms: [1000,5000,10000,30000,60000,120000,180000,240000,300000,
               360000,420000,480000,540000,600000,1200000,1800000]   # BackoffSchedule 共享（§3.3）——【此配置段由 child 1 定义，此处仅展示完整 app.messaging 视图；本任务不重复声明默认值（见 prd R8）】
  outbox:
    enabled: true                  # false 时 OutboxMessageBus 透传 delegate（灰度回退）
    poll-interval: 5s              # relay 扫描间隔
    batch-size: 32
    max-attempts: 16               # 评审"扩展性"：与消费端重试窗口相互独立（非"对齐"）；仅统计真实投递尝试（§3.2）
    immediate-retry-count: 2       # 即时投递重试次数
    immediate-retry-interval-ms: 100
    immediate-executor-core: 2     # 评审"错误处理"P1：即时投递有界 executor
    immediate-executor-max: 8
    immediate-executor-queue: 64   # 拒绝=行留 relay（不丢）
    dead-retention-days: 7
    cleanup-cron: "0 0 4 * * *"    # 评审"扩展性"：清理任务 cron 外部化（@Scheduled 占位符引用）
    leader-lock-key: "outbox:relay:leader"
    cb-signal-prefix: "messaging:cb:"
    cb-local-cache-ttl-ms: 2000    # isOpen() 本地缓存 TTL
    claiming-timeout-seconds: 300  # claiming 行超时回收阈值（绑定到 claimPending 查询，单源）
    gate-defer-interval: 5s        # gate OPEN 时顺延 next_retry_at 的间隔（§3.2 冻结 attempts）
```

## 11. 测试策略

| 测试类 | 覆盖 |
|--------|------|
| `OutboxMessageBusTest` | send 写行（含 hash_key/payload_type/tag 列）；即时投递成功删行；失败留行；熔断 OPEN 跳过；**有界 executor 拒绝时行留 relay** |
| `OutboxMessageBusSendAsyncTest` | **新增（§6.1）**：sendAsync 经 outbox（INSERT + future 由投递任务完成）；成功 complete(delegateId)；即时重试耗尽 completeExceptionally 但行留 relay；OPEN 时 future 立即 complete(outboxId)；executor 拒绝 completeExceptionally 且行留 relay |
| `RedissonLeadershipTest` | **新增（§3.1）**：isLeader 标志由持锁线程设置；stop() 后 finally unlock（非等看门狗 30s）；崩溃（杀线程）→ 看门狗超时后 follower 接管 ≤30s；Redisson null → 降级 leader=true；daemon + 命名线程 |
| `OutboxRelayTest` | 回收 pending→delete；claiming 超时回收；退避 next_retry_at；maxAttempts→dead；**drain 抛异常不杀后续调度（P0-3）**；**drain-until-empty 循环 claim**；**gate OPEN 时冻结 attempts（P1-7）** |
| `OutboxRelayLeaderTest` | 持续持锁语义；leader 崩溃后 follower 接管（≤30s，Testcontainers Redis）；非 leader 不 drain |
| `OutboxRelayConcurrencyTest` | 双 drain 并发 claim，SKIP LOCKED 互斥；重复投递由消费端幂等兜底 |
| `OutboxHashKeyTest` | rag_index_document 的 hashKey 在 relay 重建后保留 |
| `OutboxPayloadTypeTest` | **新增（P1-4）**：chat/usage/etl 三类 payload 经 outbox 往返后按 payload_type 正确反序列化 |
| `OutboxTagTest` | **新增（P1-8）**：envelope.tag 经 INSERT + relay 重建后保留（null 与非 null） |
| `OutboxTracePropagationTest` | relay 投递的消息 traceparent = publisher 的（非 relay 的） |
| `SharedCircuitBreakerGateTest` | 广播 OPEN→读到（含 2s 缓存）；Redis 挂→回退本地（只调一次）；**broadcastOpen/Closed try/catch 降级不抛（P1-6.3）**；**SendCircuitBreaker.tripOpen→gate.broadcastOpen 联动（P1-6.2）** |
| `BackoffScheduleTest` | **新增（§3.3）**：next(attempt) 封顶最后一档；配置驱动 |
| `ChatUsageTrackerTest` | outbox INSERT 失败时 counter 递增 |
