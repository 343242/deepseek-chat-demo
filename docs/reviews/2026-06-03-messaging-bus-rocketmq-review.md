# 消息总线 RocketMQ 5.x 设计审查报告

> 审查对象：[docs/design/messaging-bus.md](../design/messaging-bus.md)（RocketMQ 5.x 版本）
>
> 审查日期：2026-06-03
>
> 审查维度：可靠性、并发安全性、可用性、幂等性、可扩展性、数据一致性、恢复能力、可观测性、SPI 后端可切换性
>
> 前置参考：本审查基于前一轮 Redis Streams 版本的 [设计审查](2026-06-03-messaging-bus-design-review.md)，
> 逐项确认旧问题的修复状态，并识别 RocketMQ 5.x 新引入的风险点。

## 总体评价

相比 Redis Streams 版本，整体质量显著提升。SPI 抽象层设计合理，PushConsumer/SimpleConsumer 双模式覆盖了不同业务场景，RocketMQ 原生能力（重试状态机、死信路由、POP 负载均衡、FIFO Topic）大幅降低了自建复杂度。可切换性问题（前一轮 6 个耦合点）已全部修复。

仍有 **3 个高危问题**（幂等语义错误、重试计数器不可靠、缺少健康检查）和 **5 个中等问题** 需在实现前修复。

### 前一轮问题修复确认

| 前轮 # | 问题 | 状态 | 说明 |
|--------|------|------|------|
| #1 | ensureGroupExists 在 send() 中 | ✅ 已消除 | RocketMQ 无需显式创建消费组 |
| #2 | MAXLEN 裁剪丢消息 | ✅ 已消除 | RocketMQ Broker 消息保留由 fileReservedTime 控制 |
| #3 | ACK/DLQ 竞态 | ✅ 已消除 | Broker 端状态机管理，客户端无需处理 |
| #6 | 重试阈值与处理时间竞态 | ✅ 已消除 | PushConsumer: Broker 管理超时；SimpleConsumer: invisibleDuration |
| #9 | send() 失败策略 | ✅ 已覆盖 | §7.1 明确降级为同步保存 |
| #13 | 无幂等基础设施 | ⚠️ 部分修复 | §5.10 有 Redis SETNX 实现，但存在语义错误（见 C-1） |
| #14 | 消息 ID 不可控 | ✅ 已修复 | 新增 `deduplicationKey` 字段 |
| #17 | 缺少 Transactional Outbox | ✅ 已决策 | §7.1 明确说明同 JVM 内异步解耦不需要 |
| #21 | 指标规格缺失 | ✅ 已修复 | §3.1 完整的 Micrometer 指标表 + 追踪传播方案 |
| #27 | RetryPolicy 被静默忽略 | ✅ 已修复 | 明确文档 PushConsumer 重试由 Broker 控制，RetryPolicy 仅作参考 |
| #29 | Topic 前缀冒号非法 | ✅ 已修复 | 改为 `SMART_RAG_` |
| #31 | DLQ 操作未纳入 SPI | ✅ 已修复 | `DeadLetterOperations` 接口 + `default` 方法 |
| #32 | 异常泄漏 | ✅ 已修复 | `MessageBus` Javadoc 约束所有异常必须是 `MessagingException` 子类 |

### 评分明细

| 维度 | 评分 | 与前轮对比 |
|------|------|-----------|
| 可靠性 | ✅ 良好 | ↑ 从 ⚠️ 提升。Broker 原生重试+DLQ 替代自建调度器 |
| 并发安全 | ✅ 良好 | ↑ 从 ⚠️ 提升。POP 消息级均衡+FIFO Broker 保证 |
| 可用性 | ⚠️ 部分 | → 持平。send() 降级已覆盖，但健康检查缺失 |
| 幂等性 | ⚠️ 有缺陷 | → 持平。基础设施存在语义错误 |
| 可扩展性 | ✅ 良好 | ↑ 从 ⚠️ 提升。RocketMQ 天然水平扩展 |
| 数据一致性 | ✅ 良好 | ↑ 从 ⚠️ 提升。同 JVM 异步解耦决策合理 |
| 恢复能力 | ✅ 良好 | ↑ 从 ⚠️ 提升。Broker 原生 DLQ + replay 接口 |
| 可观测性 | ✅ 良好 | ↑ 从 ⚠️ 提升。完整指标规格 + 追踪传播 |
| SPI 可切换性 | ✅ 良好 | ↑ 从 ⚠️ 提升。前轮 6 个耦合点全部修复 |

---

## 一、可靠性 (Reliability) ✅

### ✅ 做得好的部分

1. **Broker 原生重试状态机**：`Ready → Inflight → WaitingRetry → Commit/DLQ`，无需自建重试调度器，消除了前轮 #3、#6 的竞态问题。
2. **运维前置条件**：明确要求主从同步复制（`brokerRole=SYNC_MASTER`）或同步刷盘（`flushDiskType=SYNC_FLUSH`），避免 Broker 宕机丢消息。
3. **消息大小限制**：`maxMessageSize`（默认 4MB）在配置中声明。
4. **SimpleConsumer invisibleDuration**：显式控制消息不可见时长，避免 PushConsumer 的超时重复投递问题。

### 🟡 R-1 消息序列化失败处理

**位置**：§5.3 `send()` → `buildRocketMQMessage()`

**问题**：`codec.encode(message.payload())` 如果抛出序列化异常（如 payload 含不可序列化字段），异常直接透传到 `MessagePublishException`。但 `buildRocketMQMessage()` 中的 `builder.setTag()` / `builder.setKeys()` / `builder.setMessageGroup()` 也可能抛出非法参数异常（如 tag 含非法字符）。

**建议**：在 `buildRocketMQMessage()` 中明确校验：
- tag 不含空格、`||` 等 RocketMQ 保留字符
- topic 名称符合 RocketMQ 命名规则
- 序列化后 payload 大小不超过 `maxMessageSize`

### 🟡 R-2 5.x gRPC 客户端重连行为未说明

**问题**：`ClientConfiguration` 设置了 `requestTimeout`，但文档未说明 5.x gRPC 客户端在以下场景的行为：
- NameServer 暂时不可达（网络抖动）
- Broker 重启期间
- gRPC 连接断开后的自动重连策略

5.x 客户端内置自动重连，但 `Producer.send()` 在重连期间会抛出 `ClientException`。
文档应说明：5.x 客户端的重连是自动的，但 `send()` 在重连窗口期可能失败，需要业务层重试或降级。

**建议**：在 §5.3 添加客户端重连行为说明，或在风险评估表中增加此条目。

---

## 二、并发安全性 (Concurrency Safety) ✅

### ✅ 做得好的部分

1. **POP 消息级负载均衡**：消费者数不受 Queue 数限制，消除 4.x Rebalance 停顿。
2. **FIFO Topic Broker 保证**：同一 `messageGroup` 严格按序消费，客户端无需自建有序逻辑。
3. **PushConsumer 并发模型**：5.x SDK 内部管理消费线程，`concurrency` 参数控制并发度。

### 🟡 C-1 SimpleConsumer 顺序处理影响吞吐

**位置**：§5.4 `createSimpleSubscription()` → `receiveExecutor` 循环

**问题**：SimpleConsumer 使用单线程 `ScheduledExecutorService`，在 `for (MessageView messageView : messages)` 中顺序处理每条消息。如果 `batchSize=32` 且每条消息处理需 1s，一个批次耗时 32 秒。在此期间 `receive()` 不会被调用，Broker 端消息持续积压。

**当前设计适用场景**：RAG 索引（LLM 调用，本身就慢，batchSize=5），此问题影响不大。

**建议**：在 `ConsumerConfig` 的 Javadoc 中明确约束：
- PUSH 模式：并发由 SDK 管理，`concurrency` 参数生效
- SIMPLE 模式：当前为单线程顺序处理，适合低并发高延迟场景（如 LLM 调用）；如需高并发处理，应在订阅时提供可配置的线程池

### 🟡 C-2 SimpleConsumer 重试计数器不可靠

**位置**：§5.4 `createSimpleSubscription()` → `retryCounter`

**问题**：`ConcurrentHashMap<String, AtomicInteger> retryCounter` 存在以下问题：

1. **进程重启丢失**：内存中的计数器不持久化。进程重启后，之前已重试 N 次的消息重新从 0 开始计数。
2. **多实例不共享**：同一消费组的多个实例各自维护独立计数器。实例 A 重试 3 次后 ack 放弃，但同一消息被实例 B 重新 receive 时，计数从 0 开始，可能再重试 5 次（总共 8 次）。
3. **invisibleDuration 窗口**：消息在 `invisibleDuration` 后重新可见时，如果 receive 到另一个实例或重启后的同一实例，计数器无法关联到之前的重试历史。

**影响评估**：实际影响有限——
- 多实例：POP 模式下同一消息通常不会在不同实例间频繁跳转
- 重启：invisibleDuration 后消息重新可见，但 `maxRetries` 只控制单实例内的放弃策略
- 最终兜底：超过 Broker 端 `maxDeliveryAttempts` 后消息进入 DLQ，不依赖应用层计数器

**建议**：
- 文档明确说明 `RetryPolicy.maxRetries` 是 best-effort 单实例近似值
- 可选改进：将重试次数写入 `Message.headers`（消费端在 ack 前更新），下次 receive 时从 headers 恢复

---

## 三、可用性 (Availability) ⚠️

### 🟢 A-1 send() 失败降级已覆盖

**位置**：§7.1 `ChatMessagePublisher`

`chat.message.save` 场景中 `send()` 失败降级为同步保存，设计合理。`chat.usage.record` 失败仅记日志（非关键路径），也合理。

### 🔴 A-2 缺少 Health Indicator

**问题**：无 Spring Boot Actuator health indicator，运维无法通过 `/actuator/health` 判断消息总线状态。

**建议**：实现 `MessageBusHealthIndicator`，报告：

| 检查项 | 来源 | 健康条件 |
|--------|------|----------|
| Producer 连接状态 | 5.x Producer 内部状态 | 连接正常 |
| 每个消费组活跃状态 | `Subscription.isActive()` | 所有订阅均活跃 |
| 消费积压 | 通过 `messaging.consumer.lag` 指标 | lag < 阈值（可配置） |
| DLQ 深度 | 通过 `messaging.dead.count` 指标 | DLQ 无异常增长 |

### 🟡 A-3 sendAsync 线程池无背压控制

**位置**：§5.3 `sendAsync()`

```java
this.sendExecutor = Executors.newFixedThreadPool(4,
    r -> new Thread(r, "mq-send-async"));
```

**问题**：`newFixedThreadPool` 使用无界 `LinkedBlockingQueue`。如果 Broker 不可用导致 `producer.send()` 阻塞，4 个线程全部阻塞，后续 `sendAsync()` 调用的 task 在队列中无限堆积，最终 OOM。

**建议**：
- 使用有界队列（如 `ArrayBlockingQueue(256)`）+ `CallerRunsPolicy` 或自定义 `RejectedExecutionHandler`
- 或使用 `Semaphore` 限制在途请求数
- 在 Javadoc 中说明 `sendAsync` 的背压行为

### 🟡 A-4 优雅关闭顺序未协调

**位置**：§5.9 `shutdown()`

**问题**：`shutdown()` 顺序为：subscriptions → producer → sendExecutor。但未说明：
1. 如果 subscription.close() 耗时很长（如 SimpleConsumer 正在等待 receive 返回），是否会挤占 `shutdownTimeout` 给后续 producer.close() 的时间？
2. `sendExecutor.awaitTermination()` 超时后是否调用 `sendExecutor.shutdownNow()` 强制中断？

**建议**：明确超时预算分配：

```java
void shutdown() {
    long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
    // 1. 停止消费（分配 60% 超时）
    Duration subTimeout = Duration.ofNanos((long)((deadline - System.nanoTime()) * 0.6));
    for (RocketMQSubscription sub : activeSubscriptions) {
        sub.close();  // 内部使用 subTimeout
    }
    // 2. 关闭 Producer（分配 30% 超时）
    producer.close();
    // 3. 关闭发送线程池（剩余时间）
    sendExecutor.shutdown();
    long remaining = deadline - System.nanoTime();
    if (!sendExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
        sendExecutor.shutdownNow();
    }
}
```

---

## 四、幂等性 (Idempotency) ⚠️

### ✅ 做得好的部分

1. **两层幂等设计**：总线级（Redis SETNX）拦截大部分重复 + 业务级（DB 唯一约束）兜底，互补关系清晰。
2. **Redis 不可用降级**：幂等检查失败时静默放行，不阻塞消费，降级到业务层幂等。
3. **deduplicationKey 设计**：`deduplicated()` 工厂方法避免了泛型擦除后的签名冲突，说明充分考虑了 API 设计细节。

### 🔴 C-1（升级为 I-1）幂等 Key 在 Listener 执行前设置，阻断合法重试

**位置**：§5.10 `wrapWithIdempotent()`

**问题**：幂等检查在 listener.onMessage() **之前**执行。当 listener 抛出异常（消费失败）时：

```
T0:   消息 M 到达（deduplicationKey = K）
T0:   幂等检查 → Redis SETNX(K) 返回 1（首次）→ 放行
T0:   listener.onMessage(M) → 抛出异常
T0:   PushConsumer 返回 ConsumeResult.FAILURE / SimpleConsumer 不 ack
T1:   Broker 重新投递 M
T1:   幂等检查 → Redis SETNX(K) 返回 0（已存在）→ 跳过！
      → M 被永久跳过，未被成功处理
```

这实际上将 `at-least-once` 降级为 `at-most-once`——**消费失败的消息被幂等 key 阻断重试**。

**影响评估**：

| 场景 | 影响 |
|------|------|
| PushConsumer 偶发失败（网络超时） | 低：broker 重试间隔退避，幂等 key TTL=25h，重试通常在数小时内完成 |
| SimpleConsumer 进程重启 | 中：进程重启后 listener 之前失败的消息重新 receive，但幂等 key 仍在 |
| 永久性错误（payload 无效） | 无影响：无论如何都会重试失败进 DLQ |

**关键风险**：如果 listener 中的业务逻辑因临时原因失败（如 DB 连接超时），幂等 key 已设置，消息被标记为"已处理"。虽然 Broker 重试会再次投递消息，但幂等层会拦截它——直到 TTL 过期。

**修复方案**：

```
方案 A（推荐）：将幂等 key 设置移到 listener 成功之后

  1. 幂等检查 → 存在？跳过
  2. listener.onMessage() → 成功
  3. SETNX(key) → 设置幂等 key

  竞态窗口：两个消费者同时通过检查、同时处理 → 可能重复处理一次
  但：仅在极小时间窗口内发生，且业务层 DB 唯一约束兜底
  语义：at-least-once（正确）

方案 B：使用 Redis SET NX GET（Redis 6.0+），检查是否有其他消费者正在处理

  1. SET(key, "processing", NX, EX=60) → 成功？
  2. listener.onMessage() → 成功
  3. SET(key, "done", EX=ttl) → 幂等 key 生效
  4. listener 失败？DEL(key) → 释放锁，允许重试

  语义：分布式锁 + 幂等，最安全但最复杂
```

**推荐方案 A**：将 `listener.onMessage(msg)` 移到 SETNX 之前，SETNX 移到 listener 成功之后。
竞态窗口极小（纳秒级），业务层 DB 唯一约束兜底。

### 🟡 I-2 幂等 TTL 与 Broker 重试窗口的关系应显式约束

**位置**：§5.10 + §6.1 `IdempotentConfig.ttlSeconds`

**问题**：幂等 key TTL 默认 25h（90000 秒）。PushConsumer 的 16 次重试（退避间隔 10s → 2h，累计约 4.6h），TTL 覆盖充足。但文档未显式说明这个约束关系。

**建议**：在 §5.10 添加：

> **TTL 约束**：幂等 key 的 TTL 必须大于 Broker 端最大重试窗口。PushConsumer 16 次重试累计约 4.6 小时，默认 TTL 25h 覆盖充足。如果调整 `maxDeliveryAttempts` 或重试间隔，需同步评估 TTL。

---

## 五、可扩展性 (Scalability) ✅

### ✅ 做得好的部分

1. **POP 消息级负载均衡**：消费者实例数不受 Queue 数限制，水平扩展能力远超 4.x。
2. **消费组天然隔离**：不同业务场景使用不同消费组，互不影响。
3. **FIFO Topic 高基数 hashKey**：选择 documentId 而非 teamId 作为 hashKey，充分利用并行度。
4. **Queue 热点分析**：§5.7 明确说明了 Queue 热点问题和缓解策略。

### 🟡 S-1 生产端无流控

**位置**：§5.3 `send()`

**问题**：如果业务突发大量消息（如批量文档上传），生产速率远超消费速率，Broker 端消息持续积压。虽然 Broker 有持久化能力（不会像 Redis 那样 OOM），但可能导致：
- Broker 磁盘空间耗尽
- 消费延迟持续增长

**建议**：
- 监控 `messaging.consumer.lag` 指标，超过阈值时告警
- 可选：`send()` 内部通过 Semaphore 限制在途请求数
- 在风险评估表中增加此项

---

## 六、数据一致性 (Data Consistency) ✅

### ✅ 做得好的部分

1. **同 JVM 异步解耦决策正确**：§7.1 明确说明消费者与生产者在同一服务，Transactional Outbox 引入的复杂度远超收益。
2. **send() 失败降级为同步**：关键路径有兜底，不会因消息总线故障导致数据丢失。
3. **deduplicationKey 语义清晰**：生产端设置，消费端从 `keys` 字段恢复，跨重试稳定。

### 🟡 DC-1 消息与数据库状态的一致性窗口

**位置**：§7.1 `ChatMessagePublisher`

**问题**：虽然设计说"目标是降低响应延迟而非跨服务解耦"，但仍然存在以下场景：

```
T1: chat 响应生成成功
T2: messageBus.send() 成功（消息入 Broker）
T3: 消费者处理消息，调用 conversationHelper.saveMessagesAndNotify() 失败
    （如 DB 连接超时）
T4: 消息重试 → 最终进入 DLQ
→ 消息存在于 Broker 但未写入 DB → 需要 DLQ 重放恢复
```

**评估**：这是 at-least-once 语义下的标准行为，DLQ + 重放机制已覆盖恢复路径。
§5.6 的 `replayDeadLetter()` 接口提供了恢复手段。

**建议**：在 §7.1 添加注释说明：消费失败（如 DB 不可用）时消息最终进入 DLQ，通过 DLQ 重放恢复数据一致性。

---

## 七、恢复能力 (Recovery) ✅

### ✅ 做得好的部分

1. **Broker 原生 DLQ 路由**：重试耗尽后自动进入 `%DLQ%ConsumerGroup`，无需自建 DLQ 管理器。
2. **DeadLetterOperations 接口**：scanDeadLetters / replayDeadLetter / deadLetterCount，运维重放能力完整。
3. **DLQ 自动过期**：Broker `fileReservedTime`（默认 72h）自动清理过期死信，不会无限堆积。
4. **优雅关闭重投递**：未 ACK 消息在超时后由 Broker 重新投递，消费者重启后自动恢复。

### 🟡 R-3 DLQ 重放实现细节缺失

**位置**：§5.6 `replayDeadLetter()`

**问题**：方法体为 `// 从 DLQ 拉取消息，重新发送到主 Topic`，未说明：
1. 如何定位指定 `messageId` 的死信消息？（DLQ 是普通 Topic，需拉取后过滤）
2. 重放时是否保留原始 `deduplicationKey`？（应保留，否则幂等检查可能失败）
3. 重放消息的 `timestamp` 是原始时间还是重放时间？

**建议**：在实现阶段补充 DLQ 重放的具体步骤，或在文档中增加伪代码说明。

### 🟡 R-4 缺少消费端恢复时间窗口的说明

**问题**：消费者长时间离线后重新上线，消息积压的恢复时间取决于：
- 积压消息数量
- 消费者并发度
- 单条消息处理时间

文档未说明如何预估恢复时间和如何配置消费者以加速恢复（如临时增大 `concurrency` 或 `batchSize`）。

**建议**：在运维指南中添加消费积压恢复的估算公式和建议策略。

---

## 八、可观测性 (Observability) ✅

### ✅ 做得好的部分

1. **完整指标规格**：§3.1 定义了 7 个 Micrometer 指标，覆盖发送、消费、重试、死信、积压全链路。
2. **追踪传播**：`TracePropagator` 封装 MDC/Span 的注入和提取，消费循环在调用 listener 前自动恢复。
3. **日志规范**：代码示例中 log.error / log.warn 使用了结构化日志（topic, msgId 参数）。

### 🟡 O-1 缺少消费端重试指标的 attempt 标签来源

**位置**：§3.1 `messaging.retry.count` 标签 `attempt`

**问题**：对于 PushConsumer，重试次数由 Broker 管理，消费端在消息投递时如何获取当前 attempt 序号？5.x `MessageView` 的 `deliveryAttempt` 属性（或 `properties` 中的 `RECONSUME_TIME`）是否可用需验证。

**建议**：确认 5.x `MessageView` 是否暴露 `deliveryAttempt` 属性。如不暴露，`messaging.retry.count` 的 `attempt` 标签只能记录消费端观察到的重试次数（对 SimpleConsumer 适用，对 PushConsumer 可能不准确）。

---

## 九、SPI 后端可切换性 ✅

### ✅ 前轮问题全部修复

| 前轮 # | 问题 | 修复方式 |
|--------|------|----------|
| #27 | RetryPolicy 被静默忽略 | 明确文档：PushConsumer 重试由 Broker 控制，RetryPolicy 仅作参考值 |
| #28 | pollTimeout 是 Redis 专属 | 改为 `consumeTimeout` + `invisibleDuration`，语义通用 |
| #29 | Topic 前缀冒号非法 | 改为 `SMART_RAG_` |
| #30 | 有序消息语义差异 | `Message.ordered()` Javadoc 明确 per-hashKey 有序 |
| #31 | DLQ 未纳入 SPI | `DeadLetterOperations` 接口 + `default` 方法 |
| #32 | 异常泄漏 | `MessageBus` Javadoc 强制异常包装规范 |

### ✅ SPI 契约评估

| 抽象 | 可切换性 | 说明 |
|------|---------|------|
| `Message<T>` | ✅ 通用 | 无后端依赖，headers 扩展点够用 |
| `MessageListener<T>` | ✅ 通用 | 异常=重试、返回=ACK 的约定通用 |
| `Subscription` | ✅ 通用 | pause/resume/close 语义通用 |
| `ConsumerConfig` | ✅ 通用 | `consumeTimeout`/`invisibleDuration` 语义可映射到大多数 MQ |
| `RetryPolicy` | ⚠️ 有约束 | 文档明确不同后端行为不同，业务需按最保守策略设计 |
| `DeadLetterOperations` | ✅ 通用 | scan/replay/count 语义通用 |
| `MessagePayloadCodec` | ✅ 通用 | 序列化与后端解耦 |

**结论**：切换到其他 MQ 实现（如 Kafka、Pulsar），只需实现 `RocketMQMessageBus` → `KafkaMessageBus`，业务代码零改动。`RetryPolicy` 行为差异已在文档中显式声明，不构成隐式行为漂移。

---

## 十、其他缺失项

### 🟡 M-1 Schema 演进策略未说明

**位置**：§6.3 `JacksonMessageCodec`

**问题**：消息 payload 使用 JSON 序列化，但未说明 schema 变更的兼容性策略。如果 `ChatMessagePayload` 新增字段，旧消费者反序列化时可能失败。

**建议**：
- `JacksonMessageCodec` 配置 `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`
- 文档明确 payload 演进遵循"只增不删"原则
- 可选：在 `Message.headers` 中增加 `schemaVersion` 字段

### 🟡 M-2 错误分类（永久性 vs 暂时性）未覆盖

**位置**：§5.5 + §5.10

**问题**：PushConsumer 的 listener 抛出任何异常都返回 `ConsumeResult.FAILURE`，触发 Broker 重试。但以下错误不应重试：
- `SerializationException`（payload 格式错误）→ 重试 N 次后进 DLQ，浪费资源
- `IllegalArgumentException`（业务参数无效）→ 同上

**建议**：在 `wrapWithIdempotent()` 或 listener 包装层中区分可重试异常和不可重试异常。不可重试异常直接返回成功（ack 放弃）或记录到专用死信表。

或更简单的方案：在 `MessageListener` 接口中约定——抛出 `NonRetryableException`（`MessagingException` 子类）表示永久失败，消费端直接 ack + 记录日志。

### 🟡 M-3 NoOpMessageBus 的 subscribe() 行为需更明确

**位置**：§6.2 `NoOpMessageBus`

**问题**：`NoOpMessageBus.subscribe()` 返回 `NoOpSubscription`（`isActive()=false`）。如果消费者在 `SmartLifecycle.start()` 中订阅并期望后续处理消息，`enabled=false` 时静默不处理可能导致难以排查的问题。

**建议**：
- `NoOpMessageBus.subscribe()` 除记录 WARN 日志外，应增加一条 INFO 级启动日志："MessageBus is disabled (app.messaging.enabled=false). All messaging operations are no-op."
- 在应用启动 banner 或 health indicator 中显示消息总线状态

### 🟢 M-4 消费组管理策略

**位置**：§4.2 `subscribe()` Javadoc

**问题**：Javadoc 说"自动创建消费组（如不存在）"。RocketMQ 5.x 中消费组的创建方式取决于部署模式：
- Proxy 模式（默认）：首次 subscribe 时自动创建
- NameServer 模式：需通过 mqadmin 预创建

文档应明确说明当前使用的模式和消费组是否需要预创建。

---

## 十一、风险评估更新

在原 §10 风险评估表基础上，新增以下条目：

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 幂等 key 阻断合法重试（I-1） | **高** | 将 SETNX 移到 listener 成功之后；业务层 DB 唯一约束兜底 |
| SimpleConsumer 重试计数器不可靠（C-2） | 中 | 文档明确 maxRetries 是 best-effort；Broker DLQ 兜底 |
| sendAsync 线程池 OOM（A-3） | 中 | 有界队列 + 拒绝策略 |
| 消费者离线后积压恢复时间（R-4） | 低 | 监控 lag 指标 + 运维指南 |
| 5.x 客户端重连窗口（R-2） | 低 | 客户端自动重连；send() 失败降级已覆盖 |
| Schema 演进不兼容（M-1） | 低 | FAIL_ON_UNKNOWN_PROPERTIES=false + 只增不删原则 |

---

## 优先修复项（按影响排序）

| 优先级 | 编号 | 问题 | 修复建议 |
|--------|------|------|----------|
| 🔴 P0 | I-1 | 幂等 key 在 listener 前设置，阻断合法重试 | 将 SETNX 移到 listener 成功之后 |
| 🔴 P0 | A-2 | 缺少 Health Indicator | 实现 `MessageBusHealthIndicator` |
| 🔴 P0 | C-2 | SimpleConsumer 重试计数器不可靠 | 文档明确 best-effort 语义 + 可选改进 |
| 🟡 P1 | A-3 | sendAsync 线程池无背压 | 有界队列 + 拒绝策略 |
| 🟡 P1 | A-4 | 优雅关闭超时分配 | 明确超时预算分配策略 |
| 🟡 P1 | M-2 | 永久性错误不区分 | `NonRetryableException` 约定 |
| 🟡 P1 | M-1 | Schema 演进 | `FAIL_ON_UNKNOWN_PROPERTIES=false` + 文档约定 |
| 🟡 P2 | R-1 | 消息序列化失败处理 | 校验 tag/topic 格式 + payload 大小 |
| 🟡 P2 | R-2 | 5.x 客户端重连行为 | 文档说明 |
| 🟡 P2 | I-2 | TTL 与重试窗口关系 | 显式约束文档 |
| 🟡 P2 | C-1 | SimpleConsumer 顺序处理 | 文档约束适用场景 |
| 🟡 P2 | O-1 | attempt 标签来源 | 验证 5.x MessageView 是否暴露 deliveryAttempt |
| 🟡 P2 | S-1 | 生产端无流监控 | 监控 lag + 告警 |
| 🟡 P2 | DC-1 | DLQ 恢复路径说明 | §7.1 添加注释 |
| 🟡 P2 | R-3 | DLQ 重放细节 | 实现阶段补充 |
| 🟢 P3 | M-3 | NoOpMessageBus 启动提示 | 添加 INFO 日志 |
| 🟢 P3 | M-4 | 消费组管理策略 | 文档明确创建方式 |
