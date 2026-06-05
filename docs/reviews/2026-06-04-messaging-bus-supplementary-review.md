# 消息总线设计补充审查报告

> 审查对象：[docs/design/messaging-bus.md](../design/messaging-bus.md)
>
> 审查日期：2026-06-04
>
> 审查维度：资源生命周期管理、死锁与活锁、可测试性、序列化安全与演进、降级与故障隔离、配置验证与 Fail-Fast、运维可观测性深度
>
> 前置审查：[2026-06-03 基础维度审查](2026-06-03-messaging-bus-rocketmq-review.md)（可靠性、并发安全、可用性、幂等性、可扩展性、数据一致性、恢复能力）
>
> API 准确性审查：2026-06-03 对照 [apache/rocketmq-clients](https://github.com/apache/rocketmq-clients) 源码验证了 ProducerImpl、PushConsumerBuilderImpl、SimpleConsumerBuilderImpl、MessageBuilderImpl、MessageViewImpl 的实际 API 签名。

## 总体评价

基础维度审查已确认文档的整体架构和 SPI 设计质量。本轮补充审查聚焦实现阶段最容易出 bug 的 7 个维度。共发现 **6 个 Critical** 和 **17 个 Medium** 问题。

最关键的发现：(1) `sendToDeadLetter()` 失败会导致消息永久丢失；(2) 幂等降级路径中 listener 可能被重复执行；(3) `ClientServiceProvider.loadService()` 硬编码阻碍测试。

### 评分明细

| 维度 | 评分 | 关键问题数 |
|------|------|-----------|
| 资源生命周期管理 | ⚠️ 有缺陷 | 🔴2 🟡2 |
| 死锁与活锁 | ⚠️ 部分 | 🔴1 🟡1 |
| 可测试性 | ⚠️ 有缺陷 | 🔴1 🟡2 |
| 序列化安全与演进 | ✅ 良好 | 🟡2 |
| 降级与故障隔离 | ⚠️ 有缺陷 | 🔴1 🟡2 |
| 配置验证与 Fail-Fast | ⚠️ 部分 | 🔴1 🟡3 |
| 运维可观测性深度 | ⚠️ 部分 | 🔴1 🟡4 |

---

## 一、资源生命周期管理 (Resource Lifecycle) ⚠️

### 🔴 LIFECYCLE-1 `createSimpleSubscription` 异常路径泄漏 `simpleConsumer`

**位置**：§5.4 `createSimpleSubscription()`

**问题**：`simpleConsumer` 在 `provider.newSimpleConsumerBuilder().build()` 后创建（内部持有 gRPC channel），但如果后续的 `Executors.newSingleThreadScheduledExecutor()` 或 `ThreadPoolExecutor` 构造抛出异常，`simpleConsumer` 永远不会被关闭。

```java
SimpleConsumer simpleConsumer = provider.newSimpleConsumerBuilder()
    .setClientConfiguration(clientConfig)
    .setConsumerGroup(group)
    .setAwaitDuration(Duration.ofSeconds(30))
    .setSubscriptionExpressions(subscriptionExpressions)
    .build();
// ⚠️ 如果下面任何一行抛异常，simpleConsumer 泄漏
ScheduledExecutorService receiveExecutor = Executors.newSingleThreadScheduledExecutor(...);
ExecutorService processingPool = new ThreadPoolExecutor(...);
```

**修复**：用 try-catch 包裹后续逻辑，失败时 `simpleConsumer.close()`：

```java
SimpleConsumer simpleConsumer = provider.newSimpleConsumerBuilder()...build();
try {
    ScheduledExecutorService receiveExecutor = ...;
    ExecutorService processingPool = ...;
    // ... 组装 subscription
} catch (Exception e) {
    simpleConsumer.close();
    throw new MessagingException("Failed to create subscription: " + topic, e);
}
```

---

### 🔴 LIFECYCLE-2 `processingPool.shutdown()` 不等待任务完成

**位置**：§5.4 `createSimpleSubscription()` 退出循环

**问题**：

```java
// 退出循环后关闭处理线程池
processingPool.shutdown();
```

仅 `shutdown()` 不等待任务完成。如果 Spring context close 导致进程立即退出，正在处理中的消息（已从 Broker receive 但未 ack）会丢失。Broker 会在 `invisibleDuration` 后重新投递，但这意味着消息处理时间被额外延长一个 `invisibleDuration` 周期。

**修复**：

```java
processingPool.shutdown();
try {
    if (!processingPool.awaitTermination(
            remainingTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        processingPool.shutdownNow();
    }
} catch (InterruptedException e) {
    processingPool.shutdownNow();
    Thread.currentThread().interrupt();
}
```

---

### 🟡 LIFECYCLE-3 `Caffeine Cache` 建议显式清理

**位置**：§5.4 `retryCounter`

`Cache<String, AtomicInteger>` 使用同步 Caffeine Cache，GC 时自动回收，不泄漏。但建议在 `RocketMQSubscription.close()` 中增加 `retryCounter.invalidateAll()` 加速内存回收，尤其在高频消息场景下。

---

### 🟡 LIFECYCLE-4 `shutdown()` 后仍可调用 `subscribe()`

**位置**：§5.4 `subscribe()` 入口

`shutdown = true` 是 volatile 的，但 `subscribe()` 没有检查此标志。如果另一个线程在 `shutdown()` 执行期间调用 `subscribe()`，新创建的消费者不会被关闭——`shutdown()` 的遍历循环已经完成。

**修复**：在 `subscribe()` 入口增加：

```java
if (shutdown) {
    throw new IllegalStateException("MessageBus is shutting down");
}
```

---

## 二、死锁与活锁风险 (Deadlock / Livelock) ⚠️

### 🔴 DEADLOCK-1 `CallerRunsPolicy` 阻塞 receive 线程

**位置**：§5.4 `createSimpleSubscription()` → `processingPool`

**问题**：`processingPool` 使用 `CallerRunsPolicy`。当有界队列满时，`processingPool.submit(task)` 会在 **receive 线程**上同步执行任务。

虽然不会死锁（task 的 finally 块会释放 `inflightSemaphore`），但会导致**吞吐坍塌**：

1. receive 线程被阻塞执行处理任务（可能包含 DB 写入、LLM 调用）
2. 阻塞期间停止拉取新消息
3. 整个消费管道停顿直到当前任务完成
4. 效果等价于 `concurrency = 1`

在 RAG 索引场景（LLM 调用可能耗时数分钟）下，CallerRuns 会导致消费完全停顿。

**修复建议**：替换为 `AbortPolicy` + 失败时释放 semaphore 并重试：

```java
try {
    processingPool.submit(task);
} catch (RejectedExecutionException e) {
    inflightSemaphore.release();
    // receive 循环下一轮会重新 acquire semaphore 并 receive
}
```

---

### 🟡 DEADLOCK-2 熔断器 `synchronized` 在高失败率下的锁竞争

**位置**：§5.3.1 熔断器

`send()` 流程：`checkCircuitBreaker()` (synchronized) → `producer.send()` (可阻塞 30s) → `recordSuccess()` (synchronized)。

`synchronized` 在 `producer.send()` 期间不持有（方法返回即释放），所以不会死锁。但 `recordFailure()` 路径中，多个发送失败线程会串行化在 `synchronized` 上。在 Broker 全面不可用的场景下，锁竞争加剧。

**建议**：替换为原子变量消除锁竞争：

```java
private final AtomicInteger failureCount = new AtomicInteger(0);
private final AtomicLong lastFailureTime = new AtomicLong(0);
```

---

## 三、可测试性 (Testability) ⚠️

### 🔴 TEST-1 `ClientServiceProvider.loadService()` 硬编码

**位置**：§5.3 构造函数

```java
this.provider = ClientServiceProvider.loadService();
```

`loadService()` 使用 Java ServiceLoader 在类路径上查找实现，无法在单元测试中注入 mock provider。这导致：
- 无法在没有 RocketMQ JAR 的环境中运行 `RocketMQMessageBus` 单元测试
- 无法 mock `Producer`、`PushConsumer` 行为
- 集成测试必须依赖 Testcontainers（增加 CI 时间）

**修复**：通过构造函数注入：

```java
public RocketMQMessageBus(MessagingProperties properties,
                           MessagePayloadCodec codec,
                           ClientServiceProvider provider,  // ← 注入
                           @Nullable MeterRegistry meterRegistry)
```

Auto-configuration 中将 `ClientServiceProvider.loadService()` 作为 `@Bean` 提供：

```java
@Bean
ClientServiceProvider rocketmqClientProvider() {
    return ClientServiceProvider.loadService();
}
```

---

### 🟡 TEST-2 `NoOpMessageBus` 行为契约不完整

**位置**：§6.2

`NoOpMessageBus` 定义了 `send()` 和 `subscribe()` 的行为，但：
- `sendAsync()` 行为未说明（应返回 `CompletableFuture.completedFuture("")`）
- `NoOpSubscription.isActive()` 始终返回 `false`——调用方如果依赖此方法做健康判断，会误判为"不健康"
- `deadLetterOperations()` 默认返回 `null`——调用方如果未做 null check 会 NPE

**建议**：补充 `NoOpMessageBus` 的完整行为表，对齐 `MessageBus` 接口的所有方法。

---

### 🟡 TEST-3 `buildClientConfiguration()` 每次调用创建新实例

**位置**：§5.4 `subscribe()` → `buildClientConfiguration()`

`ClientConfiguration` 是不可变对象，应缓存为实例字段。每次 `subscribe()` 创建新实例不仅浪费，还使测试时无法注入自定义配置（如 mock endpoints）。

---

## 四、序列化安全与演进 (Serialization Safety) ✅

### ✅ 做得好的部分

1. **`FAIL_ON_UNKNOWN_PROPERTIES=false`**（§6.3）：新版添加字段后旧消费者不会反序列化失败。
2. **Schema 演进规则**（§6.3）：仅允许加法变更、禁止破坏性变更、Content-Type 协商。
3. **破坏性变更策略**（§6.3.1）：版本化 Topic + 双版本兼容 + 迁移清理。
4. **`Message.headers` 中的 Content-Type**：预留未来格式协商。

### 🟡 SERDE-1 `validateMessage()` 对 payload 双重序列化

**位置**：§5.3 `validateMessage()` + `buildRocketMQMessage()`

```java
// validateMessage() 中：
byte[] encoded = codec.encode(message.payload());  // 第一次 encode
if (encoded.length > maxMessageSize) { throw ... }

// buildRocketMQMessage() 中：
.setBody(codec.encode(message.payload()))           // 第二次 encode
```

两个问题：
1. **性能浪费**：payload 可能接近 4MB，JSON 序列化是 CPU 密集操作
2. **一致性风险**：如果 `ObjectMapper` 配置了自定义序列化器有状态，两次 encode 理论上可能不同

**修复**：`validateMessage()` 返回 `byte[]` 或缓存 encode 结果：

```java
private byte[] validateAndEncode(Message<?> message) {
    validateTopicAndTag(message);       // 校验 topic/tag 格式
    byte[] encoded = codec.encode(message.payload());
    if (encoded.length > properties.rocketmq().maxMessageSize()) {
        throw new IllegalArgumentException("Payload too large: " + encoded.length);
    }
    return encoded;
}
```

---

### 🟡 SERDE-2 同 topic 多 payload 类型无类型标识

如果同 topic 下存在多种 payload 类型（如灰度期间新旧版本共存），消费者用错误的 `Class<T>` 反序列化时，`FAIL_ON_UNKNOWN_PROPERTIES=false` 会导致字段静默丢失——不会报错，但数据不完整。

**建议**：在 `Message.headers` 中注入 `__payloadType` = 完全限定类名，反序列化失败时可用于诊断日志。

---

## 五、降级与故障隔离 (Degradation & Fault Isolation) ⚠️

### 🔴 DEGRADE-1 `sendToDeadLetter()` 失败 = 消息永久丢失

**位置**：§5.4 `createSimpleSubscription()` + `sendToDeadLetter()`

```java
} catch (PermanentConsumeException e) {
    sendToDeadLetter(messageView, topic, group);  // 可能失败
    simpleConsumer.ack(messageView);               // 无论 DLQ 是否成功都 ack
}
```

以及重试耗尽路径：

```java
if (attempts >= maxRetries) {
    sendToDeadLetter(messageView, topic, group);   // 可能失败
    simpleConsumer.ack(messageView);                // 消息被 ack
}
```

如果 `sendToDeadLetter()` 中 `producer.send()` 抛异常（Broker 不可用、网络中断、熔断器 open），消息被 ack 但没进 DLQ——**永久丢失**。

**修复**：`sendToDeadLetter()` 返回 `boolean`，失败时不 ack：

```java
private boolean sendToDeadLetter(MessageView messageView, String topic, String group) {
    try {
        // ... producer.send(dlqMsg);
        return true;
    } catch (Exception e) {
        log.error("Failed to forward message to DLQ: topic={}, msgId={}",
            topic, messageView.getMessageId(), e);
        return false;
    }
}

// 调用方：
if (!sendToDeadLetter(messageView, topic, group)) {
    // DLQ 转发失败：不 ack，消息将在 invisibleDuration 后重新可见
    inflightSemaphore.release();
    return;  // 不 ack，等待重试
}
simpleConsumer.ack(messageView);
```

---

### 🟡 DEGRADE-2 熔断器是全局的，非 per-topic

**位置**：§5.3.1

一个 topic 发送连续失败 5 次会导致所有 topic 的发送被熔断（包括 `sendToDeadLetter`）。在 Broker 部分不可用场景下（如某个 topic 的路由数据丢失），故障范围会扩大。

**建议**：在文档中明确声明这是有意的简化设计，或记录 per-topic 熔断作为 Phase 2 改进项。同时 `sendToDeadLetter` 应绕过熔断器（DLQ 转发是最后防线）。

---

### 🟡 DEGRADE-3 幂等降级路径中 listener 可能被重复执行

**位置**：§5.10 `wrapWithIdempotent()`

```java
try {
    Boolean alreadyProcessed = redis.hasKey(redisKey);
    if (Boolean.TRUE.equals(alreadyProcessed)) { return; }
    listener.onMessage(msg);                          // ← 正常路径执行
    redis.opsForValue().setIfAbsent(redisKey, "1", ttl); // ← 可能失败
} catch (Exception e) {
    // catch 路径：
    listener.onMessage(msg);                          // ← 再执行一次！
}
```

**场景**：`redis.hasKey()` 成功 → `listener.onMessage()` 成功 → `setIfAbsent()` 抛异常 → 进入 catch → `listener.onMessage()` **再次执行**。同一条消息被消费两次。

**修复**：跟踪 listener 是否已执行：

```java
boolean listenerExecuted = false;
try {
    Boolean alreadyProcessed = redis.hasKey(redisKey);
    if (Boolean.TRUE.equals(alreadyProcessed)) { return; }
    listener.onMessage(msg);
    listenerExecuted = true;
    redis.opsForValue().setIfAbsent(redisKey, "1", ttl);
} catch (Exception e) {
    if (!listenerExecuted) {
        log.warn("Idempotent check failed, delegating to business-layer: topic={}", topic, e);
        listener.onMessage(msg);
    }
    // listenerExecuted=true 时说明 listener 已成功，仅 Redis 标记失败——静默放行
}
```

---

## 六、配置验证与 Fail-Fast (Configuration Validation) ⚠️

### 🔴 CONFIG-1 `maxRetries=0` 导致 Caffeine Cache 边界行为

**位置**：§5.4 `createSimpleSubscription()`

```java
Cache<String, AtomicInteger> retryCounter = Caffeine.newBuilder()
    .expireAfterWrite(config.invisibleDuration().multipliedBy(maxRetries * 2))
    .build();
```

当 `maxRetries = 0` 时：
- `invisibleDuration.multipliedBy(0)` = `Duration.ZERO`
- `expireAfterWrite(Duration.ZERO)` 的行为取决于 Caffeine 版本——某些版本抛 `IllegalArgumentException`
- 即使不抛异常，逻辑上"无重试 = 第一次失败直接进 DLQ"应走快速路径，不必创建 Cache

**修复**：`maxRetries = 0` 时跳过 retryCounter，第一次失败直接走 DLQ 路径。或在 `RetryPolicy` 构造时校验 `maxRetries >= 1`。

---

### 🟡 CONFIG-2 `ConsumerConfig` 缺少边界值校验

**位置**：§4.5 `ConsumerConfig`

以下配置值可能导致运行时异常：

| 参数 | 危险值 | 后果 |
|------|--------|------|
| `concurrency = 0` | `Math.max(1, 0)` = 1 | 安全（已被保护），但语义不清晰 |
| `batchSize = 0` | `ArrayBlockingQueue(0)` | 无法存放任何元素，所有 submit 触发 CallerRuns |
| `invisibleDuration < 20s` | RocketMQ 最小值约束 | Broker 拒绝或行为异常 |
| `tagExpression = null` | `new FilterExpression(null, TAG)` | 可能 NPE |

**修复**：在 `ConsumerConfig.Builder.build()` 中添加校验：

```java
public ConsumerConfig build() {
    if (concurrency < 1) throw new IllegalArgumentException("concurrency must be >= 1");
    if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
    if (invisibleDuration.compareTo(Duration.ofSeconds(20)) < 0)
        throw new IllegalArgumentException("invisibleDuration must be >= 20s");
    // ...
}
```

---

### 🟡 CONFIG-3 `topicPrefix` 未校验

**位置**：§6.1 `MessagingProperties`

`topicPrefix` 拼接到所有 topic 前面：`properties.topicPrefix() + message.topic()`。如果 `topicPrefix` 包含空格、中文、或特殊字符，拼接后的 topic 可能违反 `TOPIC_PATTERN`。

**修复**：在 `MessagingAutoConfiguration` 启动时校验 `topicPrefix` 符合 `^[%a-zA-Z0-9_-]+$`。

---

### 🟡 CONFIG-4 `maxMessageSize` 与 gRPC 限制不一致

**位置**：§6.1 `MessagingProperties`

`maxMessageSize` 默认 4MB，但 gRPC 默认消息大小限制也是 4MB（`maxInboundMessageSize`）。如果接近 4MB 的 payload 加上 headers/properties 超过 gRPC 限制，发送会失败。RocketMQ 5.x gRPC 客户端的默认 `maxInboundMessageSize` 需要确认。

**建议**：将 `maxMessageSize` 默认值设为 `4194304 - HEADERS_OVERHEAD`（如 4MB - 1KB），或在文档中说明与 gRPC 限制的关系。

---

## 七、运维可观测性深度 (Operational Observability) ⚠️

### 🔴 OBS-1 `messaging.consumer.lag` 采集路径缺失

**位置**：§3.1 指标规格

文档定义了 `messaging.consumer.lag` Gauge 指标，但没有说明数据来源：
- **PushConsumer**：5.x gRPC 客户端没有直接暴露 lag 的 API，需要通过 Broker Admin API（`mqadmin consumerProgress`）查询
- **SimpleConsumer**：更难——Broker 不跟踪 SimpleConsumer 的消费进度，需要结合 topic max offset 和 last ack offset 计算

当前设计中没有任何代码或配置说明 lag 如何采集。这个指标**定义了但无法实现**。

**修复**：
- 方案 A（推荐）：在 `RocketMQMessageBus` 中注入 `MQAdminExt`（RocketMQ Admin 客户端），定时查询 lag
- 方案 B：将 lag 指标移到外部监控系统（如 RocketMQ Dashboard → Prometheus exporter），文档中移除此指标
- 方案 C：标记为 Phase D，需额外 Admin API 集成

---

### 🟡 OBS-2 缺少消费成功时的正向日志

**位置**：§5.4 PushConsumer listener + SimpleConsumer 处理

消费成功时无任何日志输出。排查"消息是否被消费"时无法从日志确认。

**修复**：增加 DEBUG 级别日志：

```java
// PushConsumer:
listener.onMessage(message);
log.debug("Message consumed: topic={}, group={}, msgId={}", topic, group, messageView.getMessageId());
return ConsumeResult.SUCCESS;

// SimpleConsumer:
listener.onMessage(message);
simpleConsumer.ack(messageView);
log.debug("Message consumed and acked: topic={}, group={}, msgId={}", topic, group, msgId);
```

---

### 🟡 OBS-3 `isProducerHealthy()` 语义未实现

**位置**：§6.4 健康检查

健康检查表格说 `isProducerHealthy()` "尝试发送心跳消息"，但没有定义具体实现。如果仅检查 `producer != null`，无法检测 Broker 不可达。

**修复**：明确实现方式：

```java
public boolean isProducerHealthy() {
    // 方案 A：检查 5.x Producer 内部状态（Guava Service state）
    // 方案 B：发送探测消息到 __HEALTH_CHECK__ topic（需运维创建）
    // 方案 C：通过 gRPC channel state 判断连接状态
    try {
        return producer != null;  // 最低保障
    } catch (Exception e) {
        return false;
    }
}
```

---

### 🟡 OBS-4 消费指标缺少 `consumerMode` 标签

**位置**：§3.1

所有 consume 指标只有 `topic` + `group` 标签。当同一 topic 有不同消费模式（不同 group）时，无法区分。建议增加 `mode` 标签（`push` / `simple`）。

---

### 🟡 OBS-5 `messaging.idempotent.degraded` 缺少告警策略

**位置**：§3.1

指标已定义但没有推荐告警阈值。建议增加：

```yaml
# 推荐告警规则
messaging.idempotent.degraded{topic=*} > 0 for 5m  → P2 告警
# 含义：Redis 不可用超过 5 分钟，幂等检查持续降级中
```

---

### 🟡 OBS-6 消费端缺少消息全生命周期 trace 日志

消息在以下节点有日志：消费失败（log.error）、DLQ 转发（log.warn）、幂等跳过（log.info）。但以下节点无日志：发送成功、消费成功、重试（第 N 次）。

排查问题时的时间线示例：

```
实际链路：send → consume(retry1) → consume(retry2) → consume(success)
日志输出：                      [无]  [无]            [无]
                               log.warn              [无]
```

**建议**：至少在以下节点增加日志：
- 发送成功：DEBUG
- 消费成功：DEBUG
- 消费失败（重试中）：WARN（已有）
- DLQ 转发：WARN（已有）

---

## 优先修复顺序

按生产影响排序：

| 优先级 | 编号 | 问题 | 影响 |
|--------|------|------|------|
| P0 | DEGRADE-1 | DLQ 转发失败丢消息 | 数据丢失不可逆 |
| P0 | LIFECYCLE-1 | simpleConsumer 异常路径泄漏 | gRPC channel 泄漏 → OOM |
| P0 | DEGRADE-3 | 幂等降级路径 listener 双重执行 | 数据重复 |
| P1 | DEADLOCK-1 | CallerRunsPolicy 阻塞消费管道 | 吞吐降级 |
| P1 | TEST-1 | 硬编码 SPI 加载 | 阻碍单元测试和 CI |
| P1 | CONFIG-1 | maxRetries=0 的 Caffeine 边界 | 运行时异常 |
| P2 | OBS-1 | consumer.lag 指标无法实现 | 可观测性缺口 |
| P2 | LIFECYCLE-2 | processingPool 不等待终止 | 关闭时消息处理丢失 |
| P2 | SERDE-1 | payload 双重序列化 | 性能浪费 |
| P3 | 其余 Medium | 见各维度详细说明 | 改善代码质量 |
