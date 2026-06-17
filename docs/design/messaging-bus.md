# 消息总线抽象层设计

> 目标：设计一个基于 SPI 的消息总线抽象层，使用 Apache RocketMQ 5.x 作为消息中间件实现，
> 后续可切换到其他实现而无需修改上游业务代码。
>
> 核心需求：可靠持久化（消息不丢）、消费组（多消费者负载均衡）、削峰填谷（消费端背压控制）、
> 有序消息、Tag 过滤、死信自动管理。
>
> 约束：引入 Apache RocketMQ 5.x gRPC 客户端（`rocketmq-client-java`）；遵循项目已有的
> auto-configuration 模式（`@ConditionalOnBean` + `@EnableConfigurationProperties` + `record` properties）。
> 客户端版本基于 RocketMQ 5.2.0（`rocketmq-client-java` 5.2.0），Broker 端兼容 5.x 系列。

## 1. 背景

### 1.1 当前异步能力

> **现状说明（2026-06 更新）**：chat 模块当前**没有**使用 `ApplicationEventPublisher`。
> 经全仓 grep 验证（`@EventListener` / `@Async` / `ApplicationEventPublisher` / `publishEvent` 在
> `com.smart.rag.chat` 包下 0 命中），chat 落库与用量记录当前全部走**同步方法调用**：
> `ChatConversationHelper.saveMessagesAndNotify` 与 `ChatUsageTracker.recordUsage` 在
> `ChatServiceImpl.processResult()`（同步路径）与 `MultiTurnModeStrategy.executeStream()`
> 的 `Flux.doFinally`（流式路径）里直接调用。Phase C 的迁移起点是"同步方法调用"，不是事件总线。

| 能力 | 实现 | 局限 |
|------|------|------|
| 同步落库 | `ChatConversationHelper.saveMessagesAndNotify`（`processResult` / `executeStream.doFinally`） | 占用请求线程；LLM 流式响应结束后才落库；进程崩溃窗口内未落库的消息需 legacy DLQ 兜底 |
| 同步用量记录 | `ChatUsageTracker.recordUsage`（内部 try/catch 吞咽异常） | 失败仅记日志无补偿；高并发下可能与落库争抢 DB 连接 |
| 简单队列 | Redisson `RQueue` | 无 ACK，无消费组，无重试 |
| 死信队列 | `MessageDeadLetterQueue`（`RQueue`，仅 chat save 使用） | 手工重试调度，无消费组语义，Phase C 后退役 |
| 线程池异步 | `EtlTaskExecutorBridge`（`CompletableFuture`） | 不持久化，进程崩溃即丢失 |
| 流式响应 | `MultiTurnModeStrategy.executeStream`（`Flux<String>` + `SseStreamBridge`） | 客户端 SSE 流关闭 → `doFinally` 内同步落库；落库时长计入响应尾延迟 |

### 1.2 为什么选择 RocketMQ 5.x

RocketMQ 是 Apache 基金会顶级项目，专为分布式消息场景设计。5.x 版本引入了全新的
gRPC 协议客户端和 POP 消费模式，与本项目需求高度匹配：

- **5.x gRPC 客户端**：全新客户端实现（`rocketmq-client-java`），基于 gRPC 协议通信，
  替代 4.x 的 Remoting 协议。提供 `PushConsumer`（自动投递）和 `SimpleConsumer`（手动拉取）两种消费模式。
- **POP 消费模式**：5.x 核心改进。消费者按消息粒度负载均衡，替代 4.x 的 Queue 粒度分配。
  消费者扩缩容时无需 Rebalance 整个 Queue，延迟更低，资源利用更均匀。
- **原生消费组**：Broker 自动管理消费组，多消费者实例间负载均衡。
- **内置延迟重试**：消费失败后 Broker 自动重试，PushConsumer 通过状态机
  `Ready → Inflight → WaitingRetry → Commit/DLQ` 管理消息生命周期。
- **自动死信路由**：重试耗尽后消息自动进入 `%DLQ%ConsumerGroup` Topic，无需自建 DLQ 管理器。
- **FIFO Topic**：5.x 通过 Topic 级别 `messageType=FIFO` 配置有序消息，
  生产端设置 `messageGroup` 即可，无需 4.x 的 `MessageQueueSelector`。
- **Broker 端 Tag 过滤**：减少网络传输，比应用层过滤更高效。
- **SimpleConsumer 模式**：显式 `receive()` + `ack()` 控制，适合处理时间不可预测的场景
  （如 RAG 索引中 LLM 调用），可精确控制消息可见性和并发度。
- **事务消息**：半消息 + 本地事务回查，为未来的分布式事务场景预留能力。

> **5.x 客户端架构**：5.x gRPC 客户端通过 Proxy 组件与 Broker 通信。
> Proxy 可嵌入 Broker（5.x 默认模式）或独立部署。客户端 `endpoints` 配置可指向
> Proxy 地址（默认端口 8081）或 NameServer 地址（端口 9876，推荐生产环境使用，
> 支持自动 Broker 发现）。本项目默认使用 NameServer 地址。

> **运维前置条件**：生产环境建议 Broker 配置主从同步复制（`brokerRole=SYNC_MASTER`）
> 或 `flushDiskType=SYNC_FLUSH`。默认 `ASYNC_FLUSH` 下 Broker 宕机可能丢失
> 未刷盘消息（窗口约数百毫秒）。本文档后续配置和评估均基于主从同步部署的前提。

**结论**：消息总线的核心需求（可靠投递、消费组、重试、死信）全部是 RocketMQ 5.x 原生能力，
实现仅需 2 个核心类（RocketMQMessageBus + RocketMQSubscription）。

### 1.3 已有基础设施

- 项目使用 `app.*` 前缀的 `@ConfigurationProperties`，多数用 Java `record`。
- Auto-configuration 通过 `@ConditionalOnBean` 条件创建。
- `MessageDeadLetterQueue` + `DeadLetterRetryScheduler` 已有 DLQ 重试模式可参考，
  新消息总线上线后需按迁移计划逐步替换（见 §9 Phase C → Phase D）。
- Redisson 3.52.0 继续用于分布式锁、限流、缓存，与消息总线职责分离。

> **LLM SPI 已统一（commit `a98fa9b` / `65c5fcf` / `e0533a6`）**：
> chat / rag / agent 三个模块已解耦 Spring AI `ChatClient.Builder` 自动装配，
> 通过 `infrastructure/llm/adapter/ChatModelAdapter` SPI 接入。
> 模型标识符统一为 **`candidateId`**（registry candidate ID 格式），
> 取代旧的 `modelId` 概念。本文档 §7.2 用量记录 payload 字段、deduplicationKey
> 均使用 `candidateId`，与 LLM SPI 链路对齐。

> **Mode Strategy 已落地（参见 `docs/design/chat-mode-strategy-step2-execute-sinking.md`）**：
> `AbstractModeStrategy` / `SimpleModeStrategy` / `MultiTurnModeStrategy` / `ModeRouter`
> 取代了原本扁平的 `ChatServiceImpl` 执行路径。
> - 同步执行：`execute(StrategyExecutionContext)` 返回 `StrategyExecuteResult`
>   （携带 Spring AI `ChatResponse` 含 usage metadata）
> - 流式执行：`executeStream(StrategyExecutionContext)` 返回 `Flux<String>`，
>   在 `doFinally` 里做收尾（落库 + usage 记录）
> - Phase C 的 publisher 必须同时覆盖这两个执行路径，详见 §7.1。

> **`ChatUsageTracker` 已成为用量记录的中心化入口**：
> Phase C §7.2 的 publisher 接入点直接位于 `ChatUsageTracker.recordUsage()` 方法内部，
> 替换 `usageService.recordUsage(...)` 一行即可。`ChatUsageTracker` 内部已 try/catch
> 吞咽异常（"非关键路径"语义），与 §7.2 设计的 bus 失败仅记日志行为天然吻合。

## 2. 设计目标

### 2.1 目标

- 提供与具体消息中间件解耦的 `MessageBus` SPI 接口。
- 使用 RocketMQ 5.x 原生能力实现消费组、ACK、重试、死信、有序消息、Tag 过滤。
- 支持 FIFO Topic 级别的有序消息（同一 `messageGroup` 路由到同一分区，严格按序消费）。
- 支持消费端背压（PushConsumer 自动调节 / SimpleConsumer 手动拉取）。
- 与 Spring 生态集成（`@ConfigurationProperties`、条件装配、`SmartLifecycle` 管理）。
- 支持优雅关闭，未处理消息由 Broker 在超时后重新投递。

### 2.2 非目标

- 不在 Phase 1 实现事务消息（RocketMQ 半消息模式）。当前业务场景使用 `send()` + 消费端幂等已满足需求。
- 不实现跨集群消息复制或跨数据中心同步。
- 不替代 Spring `ApplicationEventPublisher`——进程内事件仍使用 Spring 原生机制。
- 不实现消息路由（topic exchange / header exchange）。Topic 即路由，消费端按需订阅。
- 不在 Phase 1 实现 SQL92 过滤，仅支持 Tag 过滤。

## 3. 设计原则

1. **SPI 优先**：业务代码只依赖 `MessageBus` 接口，不依赖任何实现类。
2. **可靠优先**：消息至少被消费一次（at-least-once），消费端需自行保证幂等。
3. **配置驱动**：所有 Topic、消费组、重试策略均通过 `@ConfigurationProperties` 配置。
4. **可观测**：每条消息的发送/消费/重试/死亡均有指标和日志。
5. **原生优先**：优先使用 RocketMQ 原生能力，不自建调度器和管理器。

### 3.1 可观测性指标规格

所有指标使用 Micrometer（Spring Boot Actuator 已间接依赖），命名遵循 common tags 规范。

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `messaging.send.count` | Counter | topic, result(success/fail) | 发送计数 |
| `messaging.send.latency` | Timer | topic | 发送延迟 |
| `messaging.consume.count` | Counter | topic, group, mode, result | 消费计数 |
| `messaging.consume.latency` | Timer | topic, group, mode | 消费处理延迟 |
| `messaging.retry.count` | Counter | topic, group, mode, attempt | 重试计数 |
| `messaging.dead.count` | Counter | topic, group | 死信计数 |
| `messaging.consumer.lag` | Gauge | topic, group | 消费延迟（积压量） |
| `messaging.idempotent.degraded` | Counter | topic | 幂等检查降级计数（Redis 不可用时） |
| `messaging.consumer.receive.last.success` | Gauge | topic, group | O-03: 最近一次成功 receive 的时间戳（epoch ms），用于检测消费者卡死 |

> **O-01 Phase A 优先指标**：`messaging.send.latency`、`messaging.consume.latency`、
> `messaging.retry.count`、`messaging.dead.count` 必须在 Phase A 实现。
> 这些指标是消息总线健康度的基本信号，缺失将导致问题无法发现。

> **O-02 监控建议**：通过 `messaging.consume.count{result=fail}` 增长率监控 per-listener 错误率。
> 如果某个 listener 的 fail 计数持续增长，表明该 Topic 消费逻辑存在问题（如反序列化失败）。

> **O-04 RocketMQ Prometheus Exporter**：除了应用层 Micrometer 指标，
> 生产环境建议部署 `rocketmq-exporter` 采集 Broker 端指标（消息积压、发送 TPS、
> 消费 TPS、Broker 磁盘使用等），与 Micrometer 指标互补。

> **实现说明**：`messaging.consumer.lag` 需通过 Broker Admin API（`mqadmin consumerProgress`）采集，
> 5.x gRPC 客户端未直接暴露 lag 查询接口。该指标标记为 Phase D 实现（需集成 `MQAdminExt`）。
> Phase A-D 期间可通过 RocketMQ Dashboard + Prometheus exporter 替代监控。

> **告警策略建议**：
> - `messaging.idempotent.degraded{topic=*} > 0` 持续 5 分钟 → P2 告警（Redis 不可用，幂等检查持续降级）
> - `messaging.dead.count{topic=*}` > 100 条/小时 → P2 告警（死信积压异常）
> - `messaging.send.count{result=fail}` 增长率 > 10/min → P1 告警（Broker 可能不可达）

追踪传播：生产端从当前 MDC/Span 提取 traceId 写入 `MessageEnvelope.headers`，
消费端在调用 listener 前自动恢复到 MDC。通过 `TracePropagator` 封装注入和提取逻辑，
与 Spring Micrometer Tracing 集成。

## 4. 核心抽象

### 4.1 消息模型

```java
/**
 * 消息信封 — 与传输层解耦的通用消息包装。
 */
public record MessageEnvelope<T>(
    @Nullable String id,                // 传输层分配，发送前为 null
    String topic,                       // 目标 Topic
    @Nullable String tag,               // 消息标签（用于 Broker 端过滤，null 表示无 Tag）
    T payload,                          // 业务载荷
    @Nullable String hashKey,           // 有序消息分区键（null 表示无序）
    @Nullable String deduplicationKey,  // 消费端幂等键（生产端设置，消费端从 msg.getKeys() 恢复，跨重试稳定）
    Map<String, String> headers,        // 扩展头（traceId、contentType 等）
    long timestamp                      // 创建时间戳
) {
    // S-03: headers 安全约束 — 禁止写入敏感信息（密码、token、完整身份证号等）。
    // headers 会被持久化到 Broker 磁盘并可能在日志中打印。
    // 如需传递用户标识，使用脱敏后的 ID（如 userId），不传递凭证类信息。
    public static <T> MessageEnvelope<T> of(String topic, T payload) {
        return new MessageEnvelope<>(null, topic, null, payload, null, null, Map.of(),
            System.currentTimeMillis());
    }

    /** 创建带 Tag 的消息，支持 Broker 端过滤 */
    public static <T> MessageEnvelope<T> of(String topic, String tag, T payload) {
        return new MessageEnvelope<>(null, topic, tag, payload, null, null, Map.of(),
            System.currentTimeMillis());
    }

    /**
     * 创建有序消息。
     * 同一 hashKey 的消息路由到同一分区（5.x 通过 messageGroup 实现），保证消费顺序。
     * 不同 hashKey 之间无序。
     * <p>
     * M-03: 参数顺序为 (topic, payload, hashKey)。当 T=String 时，
     * 注意不要与 (topic, tag, payload) 混淆——如有歧义风险，
     * 使用 Builder 模式或显式类型声明消除歧义。
     */
    public static <T> MessageEnvelope<T> ordered(String topic, T payload, String hashKey) {
        return new MessageEnvelope<>(null, topic, null, payload, hashKey, null, Map.of(),
            System.currentTimeMillis());
    }

    /**
     * 创建带去重键的消息。
     * 生产端 send() 网络超时时无法确认消息是否已入队，
     * 重试会产生两条不同 ID 但相同 deduplicationKey 的消息。
     * 消费端可基于此 key 实现幂等（DB 唯一约束 / 业务自然键）。
     * <p>
     * 注意：使用独立方法名 deduplicated() 而非重载 of()，
     * 因为 of(String, String, String) 在 T=String 时与 of(topic, tag, payload) 签名冲突。
     */
    public static <T> MessageEnvelope<T> deduplicated(String topic, T payload, String deduplicationKey) {
        return new MessageEnvelope<>(null, topic, null, payload, null, deduplicationKey, Map.of(),
            System.currentTimeMillis());
    }
}
```

### 4.2 消息总线 SPI

```java
/**
 * 消息总线 SPI — 所有消息操作的统一入口。
 * <p>
 * 实现类：RocketMQMessageBus（Phase 1）、未来可扩展其他后端。
 * 通过 {@link MessagingAutoConfiguration} 条件装配。
 * <p>
 * 实现类约束：所有抛出的异常必须是 {@link MessagingException} 的子类。
 * 底层异常（ClientException、IOException 等）应作为 cause 链入。
 * 业务代码不应 catch 底层异常类型。
 */
public interface MessageBus {

    // ==================== 生产者 ====================

    /** 同步发送消息，返回传输层消息 ID */
    String send(MessageEnvelope<?> message);

    /** 异步发送消息 */
    CompletableFuture<String> sendAsync(MessageEnvelope<?> message);

    // ==================== 消费者 ====================

    /**
     * 订阅 Topic。
     * <p>
     * 自动创建消费组（如不存在），启动消费循环。
     * 返回 {@link Subscription} 用于管理订阅生命周期。
     *
     * @param topic       目标 Topic
     * @param group       消费组名称
     * @param config      消费者配置（消费模式、并发度等）
     * @param payloadType 消息载荷类型（泛型擦除后运行时需要显式传入，用于反序列化）
     * @param listener    消息监听器
     * @return 订阅句柄
     */
    <T> Subscription subscribe(String topic, String group,
                               ConsumerConfig config,
                               Class<T> payloadType,
                               MessageHandler<T> handler);

    // ==================== 生命周期 ====================

    /** 关闭总线：停止所有消费者、释放连接 */
    void shutdown();

    // ==================== 事务集成 ====================

    /**
     * DC-01: 在当前 Spring 事务提交后发送消息。
     * <p>
     * 使用场景：DB 写入 + 消息发送需要原子性时（如保存 chat record 后发消息通知）。
     * 底层通过 {@code TransactionSynchronizationManager.registerSynchronization()} 实现。
     * <p>
     * 注意：事务回滚时消息不会被发送。非事务上下文中调用时立即发送（fallback）。
     */
    default void sendAfterCommit(MessageEnvelope<?> message) {
        // 默认实现：立即发送（非事务上下文的 fallback）
        send(message);
    }

    // ==================== 死信操作 ====================

    /**
     * 死信操作接口（可选）。
     * <p>
     * RocketMQ 实现委托给 Broker 的 %DLQ% 管理接口。
     */
    default DeadLetterOperations deadLetterOperations() {
        return null;
    }
}

/**
 * 消息总线管理接口 — 运维与健康检查专用。
 * <p>
 * 与 {@link MessageBus} SPI 分离，避免业务代码依赖管理方法。
 * {@link RocketMQMessageBus} 同时实现 MessageBus 和 MessageBusManagement。
 * 健康检查组件通过此接口获取总线内部状态。
 */
public interface MessageBusManagement {
    /**
     * 检查 Producer 连通性。
     * 实现：优先检查 5.x Producer 内部 Service 状态（isRunning），
     * 若不可访问则降级为非空检查。不发送探测消息（避免健康检查影响业务流量）。
     */
    boolean isProducerHealthy();

    /** 获取活跃订阅数（健康检查用） */
    int activeSubscriptionCount();

    /** 获取熔断器状态（健康检查用） */
    String circuitBreakerState();
}

/**
 * 死信操作 — 运维接口，支持死信查看和重放。
 */
public interface DeadLetterOperations {
    /** 扫描指定 topic 的死信消息 */
    List<MessageEnvelope<?>> scanDeadLetters(String topic, int count);

    /** 将指定死信消息重新投递到主 topic */
    void replayDeadLetter(String topic, String messageId);

    /** 获取指定 topic 的死信数量 */
    int deadLetterCount(String topic);
}
```

### 4.3 消息处理器

```java
/**
 * 消息处理器 — 业务代码实现此接口处理消息。
 * <p>
 * 命名为 MessageHandler 而非 MessageListener，避免与
 * {@code org.apache.rocketmq.client.apis.consumer.MessageListener}（返回 ConsumeResult）命名冲突。
 * <p>
 * 错误传播约定：实现类抛出异常 = 消费失败，由 RocketMQMessageBus 内部包装器捕获后
 * 转换为 RocketMQ 的 ConsumeResult.FAILURE（PushConsumer）或不 ack（SimpleConsumer）。
 * 正常返回 = 消费成功。实现必须是幂等的（消息可能被重复投递）。
 */
@FunctionalInterface
public interface MessageHandler<T> {
    void onMessage(MessageEnvelope<T> message);
}
```

### 4.4 订阅生命周期

```java
/**
 * 订阅句柄 — 管理单个消费组的生命周期。
 * <p>
 * close() 必须幂等：第二次调用应是 no-op。
 * 实现类使用 {@code AtomicBoolean closed} 守卫，首次 close 时执行清理逻辑，
 * 后续调用直接返回。
 */
public interface Subscription extends AutoCloseable {
    String topic();
    String group();
    boolean isActive();
    void pause();
    void resume();
    @Override
    void close();  // 停止消费、释放资源（幂等）
}
```

### 4.5 消费者配置

```java
/**
 * 消费模式 — 决定底层使用 PushConsumer 还是 SimpleConsumer。
 */
public enum ConsumerMode {
    /**
     * PushConsumer — Broker 自动推送消息到消费者。
     * 适合处理时间可预测的场景（chat save、usage 记录）。
     * 优点：自动重试、自动负载均衡、API 简单。
     */
    PUSH,

    /**
     * SimpleConsumer — 消费者主动拉取消息，显式 ack/nack。
     * 适合处理时间不可预测的场景（RAG 索引中 LLM 调用）。
     * 优点：精确控制并发度、消息可见性、无消费超时风险。
     */
    SIMPLE
}

/**
 * 消费者配置 — 每个订阅的独立配置。
 */
public record ConsumerConfig(
    ConsumerMode consumerMode,     // 消费模式，默认 PUSH
    int concurrency,               // PUSH: 消费线程数（如客户端支持），默认 20
    int batchSize,                 // SIMPLE: 每次 receive() 最大消息数，默认 32
    Duration consumeTimeout,       // PUSH: 仅作文档参考。实际消费超时由 Broker 端消费组元数据控制，
                                   // 无法从客户端 API 设置。需通过 mqadmin updateSubGroup 配置。
    Duration invisibleDuration,    // SIMPLE: 消费失败后消息不可见时长，默认 10min
    String tagExpression,          // Tag 过滤表达式，默认 "*"（接收所有 Tag）
    RetryPolicy retryPolicy        // 重试策略
) {
    public static final ConsumerConfig DEFAULT = new ConsumerConfig(
        ConsumerMode.PUSH, 20, 32,
        Duration.ofMinutes(15), Duration.ofMinutes(10),
        "*", RetryPolicy.DEFAULT);

    public static Builder builder() { return new Builder(); }

    /**
     * Builder — 校验边界值，启动时 fail-fast。
     */
    public static class Builder {
        // ... fields ...

        public ConsumerConfig build() {
            // B-01: 下限 + 上限校验
            if (concurrency < 1) throw new IllegalArgumentException("concurrency must be >= 1");
            if (concurrency > 256) throw new IllegalArgumentException("concurrency must be <= 256");
            if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
            if (batchSize > 256) throw new IllegalArgumentException("batchSize must be <= 256");
            if (invisibleDuration != null && invisibleDuration.compareTo(Duration.ofSeconds(20)) < 0)
                throw new IllegalArgumentException("invisibleDuration must be >= 20s (RocketMQ minimum)");
            if (invisibleDuration != null && invisibleDuration.compareTo(Duration.ofHours(2)) > 0)
                throw new IllegalArgumentException("invisibleDuration must be <= 2h");
            if (retryPolicy != null && retryPolicy.maxRetries() > 100)
                throw new IllegalArgumentException("maxRetries must be <= 100");
            if (tagExpression == null || tagExpression.isBlank())
                tagExpression = "*";
            return new ConsumerConfig(consumerMode, concurrency, batchSize,
                consumeTimeout, invisibleDuration, tagExpression, retryPolicy);
        }
    }
}
```

### 4.6 重试策略

```java
/**
 * 重试策略。
 * <p>
 * PushConsumer：重试由 Broker 端自动调度（5.x 状态机管理）。
 * maxRetries 对应消费组的 maxDeliveryAttempts（Broker 端配置）。
 * 客户端消费失败返回 ConsumeResult.FAILURE 即触发 Broker 侧重试。
 * 超过 maxDeliveryAttempts 后消息自动进入 %DLQ% Topic。
 * <p>
 * 错误传播约定：SPI 层 MessageHandler.onMessage() 返回 void，抛出异常 = 消费失败。
 * RocketMQMessageBus 内部 PushConsumer 包装器捕获异常后返回 ConsumeResult.FAILURE，
 * SimpleConsumer 包装器捕获异常后不调用 ack()，消息在 invisibleDuration 后重新可见。
 * <p>
 * SimpleConsumer：无自动重试。消费失败时不调用 ack()，
 * 消息在 invisibleDuration 后重新可见，由消费者再次 receive() 拉取。
 * RocketMQMessageBus 内部维护 ConcurrentHashMap&lt;msgId, retryCount&gt;
 * 在 receive 循环中跟踪重试次数，超过 maxRetries 后 ack 放弃。
 */
public record RetryPolicy(
    int maxRetries             // PushConsumer: 最大投递次数（Broker 端），默认 16
                              // SimpleConsumer: 应用层最大重试次数，默认 5
) {
    /** PushConsumer 默认重试策略 */
    public static final RetryPolicy DEFAULT = new RetryPolicy(16);

    /** SimpleConsumer 默认重试策略 */
    public static final RetryPolicy SIMPLE_DEFAULT = new RetryPolicy(5);

    /** 无重试 */
    public static final RetryPolicy NO_RETRY = new RetryPolicy(0);
}
```

> **5.x 重试与 4.x 的区别**：4.x 通过 `consumer.setMaxReconsumeTimes()` 客户端配置重试次数。
> 5.x PushConsumer 的 `maxDeliveryAttempts` 是消费组的 Broker 端元数据，
> 在创建消费组时确定（通过 mqadmin 或 Proxy API）。客户端代码中的 `RetryPolicy.maxRetries`
> 作为参考值，实际生效值以 Broker 端配置为准。

### 4.7 追踪传播

> 生产端从当前上下文提取追踪信息写入 `MessageEnvelope.headers`，消费端在调用 listener 前自动恢复。
> 与 Spring Micrometer Tracing 集成，默认使用 W3C TraceContext 格式。

```java
/**
 * 追踪传播 — 在消息发送和消费之间传播 traceId / spanId。
 * <p>
 * 生产端：buildRocketMQMessage() 中调用 inject()，将当前追踪上下文写入 MessageEnvelope.headers。
 * 消费端：listener 调用前调用 restore()，从 MessageEnvelope.headers 恢复到当前线程 MDC。
 * <p>
 * 实现：MicrometerTracePropagator（默认），基于 Spring Micrometer Tracing + W3C TraceContext。
 */
public interface TracePropagator {
    /** 从当前上下文提取追踪信息，返回需注入 MessageEnvelope.headers 的键值对 */
    Map<String, String> inject();

    /** 从 MessageEnvelope.headers 恢复追踪信息到当前线程上下文（MDC + Span） */
    void restore(Map<String, String> headers);

    /** 清除当前线程的追踪上下文（消费端处理完成后调用） */
    void clear();
}
```

> **集成点**：
> - `buildRocketMQMessage()`：`propagator.inject().forEach(builder::addProperty)`
> - PushConsumer listener 包装：调用 `propagator.restore(properties)` → listener → `propagator.clear()`
> - SimpleConsumer 处理循环：同上，在 processingPool 的每个任务中 wrap

## 5. RocketMQ 5.x 实现

### 5.1 核心映射

| SPI 概念 | RocketMQ 5.x 概念 | 说明 |
|----------|------------------|------|
| `MessageBus` | `Producer` + `PushConsumer` / `SimpleConsumer` 管理 | 统一入口 |
| `MessageEnvelope.topic` | Topic | Topic 名称 |
| `MessageEnvelope.tag` | Tag | Broker 端过滤标签 |
| `MessageEnvelope.hashKey` | `messageGroup`（FIFO Topic） | 分区路由（替代 4.x 的 `MessageQueueSelector`） |
| `MessageEnvelope.deduplicationKey` | `keys` 字段 | Broker 端去重查询 |
| `MessageEnvelope.headers` | `properties` | 用户自定义属性 |
| `MessageHandler` | `MessageListener`（返回 `ConsumeResult`） | SPI 层回调（避免与 5.x MessageListener 命名冲突） |
| `Subscription` | `PushConsumer` 或 `SimpleConsumer` 实例 | 消费者生命周期 |
| `ConsumerMode.PUSH` | `PushConsumer` | 自动投递，自动重试 |
| `ConsumerMode.SIMPLE` | `SimpleConsumer` | 手动 receive/ack，精确流控 |
| `RetryPolicy.maxRetries` | `maxDeliveryAttempts`（Broker 端） | PushConsumer 最大投递次数 |
| `DeadLetterOperations` | `%DLQ%ConsumerGroup` Topic | 死信管理 |
| — | `ClientServiceProvider` | 5.x 客户端工厂（SPI 加载） |
| — | `ClientConfiguration` | 客户端配置（endpoints、timeout 等） |
| — | `FilterExpression` | Tag 过滤表达式（替代 4.x 字符串） |

### 5.2 包结构

```
infrastructure/messaging/
├── MessageBus.java                    (SPI 接口)
├── MessageEnvelope.java                       (消息信封)
├── MessageHandler.java                 (消息处理器接口)
├── Subscription.java                  (订阅句柄)
├── ConsumerConfig.java                (消费者配置)
├── ConsumerMode.java                  (消费模式枚举)
├── RetryPolicy.java                   (重试策略)
├── MessagingProperties.java           (@ConfigurationProperties)
├── MessagingAutoConfiguration.java    (无条件装配 — Phase 0)
├── MessagingHealthIndicator.java      (健康检查)
├── MessagePayloadCodec.java           (序列化抽象)
├── JacksonMessageCodec.java           (JSON 序列化实现)
├── idempotent/
│   └── IdempotentConfig.java          (幂等配置 record)
├── exception/
│   ├── MessagingException.java
│   ├── MessagePublishException.java
│   ├── MessageConsumeException.java
│   └── PermanentConsumeException.java
└── rocketmq/
    ├── RocketMQMessageBus.java        (核心实现: 5.x Producer + PushConsumer/SimpleConsumer 管理)
    └── RocketMQSubscription.java      (订阅管理: PushConsumer/SimpleConsumer 生命周期)
```

**Maven 依赖**：

```xml
<!-- RocketMQ 5.x gRPC 客户端 -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client-java</artifactId>
    <version>${rocketmq-client-java.version}</version>
</dependency>
<!-- Caffeine 缓存：SimpleConsumer 重试计数器自动过期，防止 OOM -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
<!-- Spring Boot Actuator：健康检查与 Micrometer 指标 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

> **实现复杂度**：核心实现仅需 `RocketMQMessageBus` + `RocketMQSubscription` 两个类。
> 发送端使用 5.x `Producer`，消费端根据 `ConsumerMode` 创建 `PushConsumer` 或 `SimpleConsumer`。
> 重试调度和死信路由由 Broker 原生处理，无需自建调度器。

### 5.2.1 异常层次

> 消息总线异常融入项目已有的 `AbstractException` + `IErrorCode` 体系。
> `MessagingErrorCode` 使用 D 类基础设施错误码段 **400001–400011**
> （与 `.trellis/spec/backend/error-handling.md` 段位定义一致）。
> 4xxxxx 段为基础设施层预留，非消息总线独占。

```java
/**
 * 消息总线错误码 (D类, 400001–400011)
 */
public enum MessagingErrorCode implements IErrorCode {
    PUBLISH_FAILED(400001, "消息发送失败"),
    CONSUME_FAILED(400002, "消息消费失败"),
    PERMANENT_CONSUME_ERROR(400003, "永久性消费错误"),
    SUBSCRIPTION_ERROR(400004, "订阅异常"),
    CIRCUIT_BREAKER_OPEN(400005, "熔断器开启，拒绝发送"),
    INVALID_TOPIC(400006, "非法Topic名称"),
    INVALID_TAG(400007, "非法标签名称"),
    INVALID_GROUP(400008, "非法消费者组名称"),
    MESSAGE_TOO_LARGE(400009, "消息体超限"),
    INVALID_CONFIG(400010, "消费配置无效"),
    UNSUPPORTED_OPERATION(400011, "不支持的操作"),
    ;

    private final int code;
    private final String message;

    MessagingErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public int getCode() { return code; }
    @Override public String getMessage() { return message; }
}

/**
 * 消息总线基础异常 — 融入项目现有异常体系。
 * 继承 {@link AbstractException}，携带 {@link MessagingErrorCode}。
 */
public class MessagingException extends AbstractException {
    public MessagingException(MessagingErrorCode errorCode) {
        super(errorCode);
    }

    public MessagingException(IErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public MessagingException(IErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}

/** 消息发送异常 — Producer 发送失败时抛出 */
public class MessagePublishException extends MessagingException {
    public MessagePublishException(String detail, Throwable cause) {
        super(MessagingErrorCode.PUBLISH_FAILED, detail, cause);
    }

    public MessagePublishException(String detail) {
        super(MessagingErrorCode.PUBLISH_FAILED, detail);
    }
}

/** 消息消费异常 — 消费处理失败时抛出 */
public class MessageConsumeException extends MessagingException {
    public MessageConsumeException(String detail, Throwable cause) {
        super(MessagingErrorCode.CONSUME_FAILED, detail, cause);
    }
}

/**
 * 永久性消费异常 — 表示消息本身不可处理，重试无意义。
 * <p>
 * 典型场景：反序列化失败、payload schema 不匹配、消息体格式错误。
 * <p>
 * 消费端 listener 抛出此异常时：
 * - PushConsumer：跳过重试计数，返回 ConsumeResult.FAILURE（Broker 在 maxDeliveryAttempts 后路由到 DLQ）
 * - SimpleConsumer：立即 ack + 转发 DLQ（跳过重试计数器）
 */
public class PermanentConsumeException extends MessagingException {
    public PermanentConsumeException(String message) {
        super(MessagingErrorCode.PERMANENT_CONSUME_ERROR, message);
    }

    public PermanentConsumeException(String message, Throwable cause) {
        super(MessagingErrorCode.PERMANENT_CONSUME_ERROR, message, cause);
    }
}
```

**错误分类规则**：

| 异常类型 | 示例 | 处理方式 |
|----------|------|----------|
| `PermanentConsumeException` | 反序列化失败、schema 不匹配、无效 payload | 跳过重试，直接 DLQ |
| 其他 `RuntimeException` | DB 临时不可用、外部服务超时 | 正常重试路径 |

### 5.3 发送消息

> **实现选择**：发送端直接使用 5.x `Producer`（通过 `ClientServiceProvider` 创建），
> 而非 `RocketMQTemplate`（4.x Spring 封装）。原因：
> 1. `RocketMQTemplate` 封装的是 4.x `DefaultMQProducer`，不兼容 5.x gRPC 客户端。
> 2. 5.x `Producer` API 更简洁：`producer.send(message)` 返回 `SendReceipt`。
> 3. 有序消息通过 `MessageBuilder.setMessageGroup()` 设置，无需 `MessageQueueSelector`。

```java
// RocketMQMessageBus
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;

public class RocketMQMessageBus implements MessageBus {
    private final Producer producer;
    private final MessagingProperties properties;
    private final MessagePayloadCodec codec;
    private final ClientServiceProvider provider;
    // CopyOnWriteArrayList：读多写少（shutdown 迭代遍历，subscribe 少量写入），无需显式同步
    private final CopyOnWriteArrayList<RocketMQSubscription> activeSubscriptions
        = new CopyOnWriteArrayList<>();
    private volatile boolean shutdown;
    private final ClientConfiguration clientConfiguration;
    @Nullable private final MeterRegistry meterRegistry;

    /**
     * ClientServiceProvider 通过构造函数注入，支持单元测试替换为 mock 实现。
     * 生产环境由 MessagingAutoConfiguration 通过 ClientServiceProvider.loadService() 创建 Bean。
     */
    public RocketMQMessageBus(MessagingProperties properties,
                               MessagePayloadCodec codec,
                               ClientServiceProvider provider,
                               @Nullable MeterRegistry meterRegistry) {
        this.properties = properties;
        this.codec = codec;
        this.provider = provider;
        this.meterRegistry = meterRegistry;

        ClientConfiguration clientConfig = ClientConfiguration.newBuilder()
            .setEndpoints(properties.rocketmq().endpoints())
            .setRequestTimeout(properties.rocketmq().requestTimeout())
            .build();

        validateTopicPrefix(properties.topicPrefix());

        this.clientConfiguration = clientConfig;

        try {
            this.producer = provider.newProducerBuilder()
                .setClientConfiguration(clientConfig)
                .build();
        } catch (ClientException e) {
            throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                "Failed to create RocketMQ Producer", e);
        }
    }

    /**
     * 校验 topicPrefix 格式，启动时 fail-fast。
     * topicPrefix 拼接到所有 topic 前面，格式错误会导致拼接后的 topic 违反 TOPIC_PATTERN。
     */
    private static void validateTopicPrefix(String prefix) {
        if (prefix != null && !prefix.isEmpty()
            && !java.util.regex.Pattern.matches("^[a-zA-Z0-9_-][%a-zA-Z0-9_-]*$", prefix)) {
            throw new IllegalArgumentException(
                "Invalid topicPrefix: '" + prefix
                + "'. Must start with alphanumeric/underscore/hyphen, "
                + "followed by alphanumeric/underscore/hyphen/percent characters only.");
        }
    }

    @Override
    public String send(MessageEnvelope<?> message) {
        checkCircuitBreaker();
        try {
            byte[] encoded = validateAndEncode(message);
            org.apache.rocketmq.client.apis.message.Message rmqMsg =
                buildRocketMQMessage(message, encoded);
            SendReceipt receipt = producer.send(rmqMsg);
            recordSuccess();
            // SendReceipt.getMessageId() 返回 MessageId 类型，toString() 得到 Broker 分配的唯一 ID
            log.debug("Message sent: topic={}, msgId={}", message.topic(), receipt.getMessageId());
            return receipt.getMessageId().toString();
        } catch (Exception e) {
            recordFailure();
            throw new MessagePublishException(
                "Failed to send message to topic: " + message.topic(), e);
        }
    }

    /**
     * 异步发送消息。
     * <p>
     * 5.x Producer 原生提供 sendAsync()（基于 gRPC async stub），
     * 返回 CompletableFuture<SendReceipt>，无需额外线程池。
     */
    @Override
    public CompletableFuture<String> sendAsync(MessageEnvelope<?> message) {
        // 同步验证失败直接抛 IllegalArgumentException（非 transient 错误，不应以 CF 返回）
        byte[] encoded = validateAndEncode(message);
        org.apache.rocketmq.client.apis.message.Message rmqMsg =
            buildRocketMQMessage(message, encoded);
        checkCircuitBreaker();
        try {
            return producer.sendAsync(rmqMsg)
                .thenApply(receipt -> {
                    recordSuccess();
                    return receipt.getMessageId().toString();
                })
                .exceptionally(e -> {
                    recordFailure();
                    // 提取 CompletionException 包装的原始异常，避免双层包装
                    Throwable cause = (e instanceof java.util.concurrent.CompletionException ce)
                        ? ce.getCause() : e;
                    throw new MessagePublishException(
                        "Async send failed: " + message.topic(), cause);
                });
        } catch (Exception e) {
            recordFailure();
            return CompletableFuture.failedFuture(
                new MessagePublishException(
                    "Failed to initiate async send: " + message.topic(), e));
        }
    }

    private org.apache.rocketmq.client.apis.message.Message buildRocketMQMessage(
            MessageEnvelope<?> message, byte[] encodedPayload) {
        var builder = provider.newMessageBuilder()
            .setTopic(properties.topicPrefix() + message.topic())
            .setBody(encodedPayload);

        if (message.tag() != null) {
            builder.setTag(message.tag());
        }
        if (message.deduplicationKey() != null) {
            builder.setKeys(message.deduplicationKey());
        }
        // 有序消息：仅 FIFO Topic 设置 messageGroup，避免非 FIFO Topic 设置无效属性
        if (message.hashKey() != null && isOrderedTopic(message.topic())) {
            builder.setMessageGroup(message.hashKey());
        }
        // X-01: 显式设置 Content-Type，明确序列化格式
        if (message.headers().getOrDefault("Content-Type", null) == null) {
            builder.addProperty("Content-Type", "application/json");
        }
        message.headers().forEach(builder::addProperty);

        return builder.build();
    }

    /**
     * 判断 Topic 是否为 FIFO 有序 Topic。
     * 通过配置属性 app.messaging.ordered-topics 判定。
     */
    private boolean isOrderedTopic(String topic) {
        return properties.orderedTopics() != null
            && properties.orderedTopics().contains(topic);
    }

    /** Topic 格式：字母数字 + 下划线 + 连字符 + 百分号（系统 Topic 前缀如 %DLQ%、%APP_DLQ%），1-128 字符 */
    private static final java.util.regex.Pattern TOPIC_PATTERN =
        java.util.regex.Pattern.compile("^[%a-zA-Z0-9_-]{1,128}$");

    /** Tag 格式：字母数字 + 下划线 + 连字符，最大 64 字符 */
    private static final java.util.regex.Pattern TAG_PATTERN =
        java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /**
     * 校验消息并编码 payload。
     * 合并校验与编码为单次操作，避免双重序列化（validateMessage + buildRocketMQMessage 各 encode 一次）。
     *
     * @return 编码后的 payload bytes
     */
    private byte[] validateAndEncode(MessageEnvelope<?> message) {
        String fullTopic = properties.topicPrefix() + message.topic();
        if (fullTopic.length() > 128) {
            throw new IllegalArgumentException(
                "Full topic name too long: '" + fullTopic
                + "' (prefix + topic = " + fullTopic.length() + " chars, max 128)");
        }
        if (!TOPIC_PATTERN.matcher(message.topic()).matches()) {
            throw new IllegalArgumentException(
                "Invalid topic name: '" + message.topic()
                + "'. Must be 1-128 chars, alphanumeric/underscore/hyphen/percent only.");
        }
        if (message.tag() != null && !TAG_PATTERN.matcher(message.tag()).matches()) {
            throw new IllegalArgumentException(
                "Invalid tag name: '" + message.tag()
                + "'. Must be 1-64 chars, alphanumeric/underscore/hyphen only.");
        }
        byte[] encoded = codec.encode(message.payload());
        if (encoded.length > properties.rocketmq().maxMessageSize()) {
            throw new IllegalArgumentException("Message payload too large: " + encoded.length + " bytes");
        }
        return encoded;
    }
}
```

#### 5.3.1 发送熔断

> 当 RocketMQ Broker 不可用或网络异常时，发送操作会持续超时等待，占用线程池资源。
> 引入轻量级熔断器避免级联故障。设计遵循项目已有的 `ModelCircuitBreakerRegistry` 模式：
> 三态（CLOSED/OPEN/HALF_OPEN）+ synchronized 方法守卫 + computeIfAbsent 懒加载。

```java
/**
 * M-02: 独立发送熔断器，遵循 ModelCircuitBreakerRegistry 模式。
 * 支持 per-topic 粒度熔断（不同 Topic 独立计数），避免单个 Topic 故障影响全局。
 */
public class SendCircuitBreaker {

    private final CircuitBreakerConfig config;
    private final Clock clock;

    // 三态：CLOSED → OPEN（连续失败达阈值） → HALF_OPEN（冷却后探针） → CLOSED
    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private int failureCount = 0;
    private long openedAtMs = 0;
    private int activeHalfOpenProbes = 0;

    SendCircuitBreaker(CircuitBreakerConfig config) {
        this(config, Clock.systemUTC());
    }

    SendCircuitBreaker(CircuitBreakerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * 发送前检查：CLOSED 放行，OPEN fast-fail，HALF_OPEN 限制 1 个探针。
     * 不经过 sendToDeadLetter() — 确保 DLQ 转发不受熔断影响（最后防线）。
     */
    synchronized boolean isCallAllowed() {
        refreshState();
        if (state == CircuitBreakerState.OPEN) return false;
        if (state == CircuitBreakerState.HALF_OPEN) {
            if (activeHalfOpenProbes >= 1) return false;
            activeHalfOpenProbes++;
        }
        return true;
    }

    synchronized void recordSuccess() {
        if (state == CircuitBreakerState.HALF_OPEN) activeHalfOpenProbes--;
        failureCount = 0;
        state = CircuitBreakerState.CLOSED;
    }

    synchronized void recordFailure() {
        if (state == CircuitBreakerState.HALF_OPEN) {
            activeHalfOpenProbes--;
            tripOpen();
            return;
        }
        failureCount++;
        if (failureCount >= config.failureThreshold()) {
            tripOpen();
        }
    }

    synchronized CircuitBreakerState state() {
        refreshState();
        return state;
    }

    private void tripOpen() {
        state = CircuitBreakerState.OPEN;
        openedAtMs = clock.millis();
        failureCount = config.failureThreshold();
    }

    private void refreshState() {
        if (state == CircuitBreakerState.OPEN
            && clock.millis() - openedAtMs >= config.cooldownMillis()) {
            state = CircuitBreakerState.HALF_OPEN;
        }
    }
}

// AR-03: 熔断器 cooldown 到期仅表示进入 HALF_OPEN 状态允许探针请求，
// 不代表下游系统已恢复。探针失败会立即重新 OPEN。
// 生产环境应结合健康检查指标（如 messaging.send.latency P99）确认恢复。

// ==================== RocketMQMessageBus 中使用 ====================

public class RocketMQMessageBus implements MessageBus {

    // M-02: 独立熔断器实例，per-topic 粒度（ConcurrentHashMap 懒加载）
    private final ConcurrentMap<String, SendCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    private SendCircuitBreaker circuitBreakerFor(String topic) {
        return circuitBreakers.computeIfAbsent(topic,
            k -> new SendCircuitBreaker(properties.circuitBreaker()));
    }

    @Override
    public String send(MessageEnvelope<?> message) {
        SendCircuitBreaker cb = circuitBreakerFor(message.topic());
        if (!cb.isCallAllowed()) {
            throw new MessagePublishException(
                "Circuit breaker OPEN for topic: " + message.topic());
        }
        try {
            byte[] encoded = validateAndEncode(message);
            org.apache.rocketmq.client.apis.message.Message rmqMsg =
                buildRocketMQMessage(message, encoded);
            SendReceipt receipt = producer.send(rmqMsg);
            cb.recordSuccess();
            log.debug("Message sent: topic={}, msgId={}", message.topic(), receipt.getMessageId());
            return receipt.getMessageId().toString();
        } catch (Exception e) {
            cb.recordFailure();
            throw new MessagePublishException(
                "Failed to send message to topic: " + message.topic(), e);
        }
    }
}
```

`MessagingProperties` 中新增熔断配置（完整定义见 §6.1）：

```java
/** 熔断配置（定义在 MessagingProperties 内部 record） */
public record CircuitBreakerConfig(
    int failureThreshold,          // 连续失败次数阈值，默认 5
    long cooldownMillis            // 熔断冷却时间（30s），默认 30000
) {
    public CircuitBreakerConfig {
        if (failureThreshold <= 0) failureThreshold = 5;
        if (cooldownMillis <= 0) cooldownMillis = 30000;
    }
}
```

### 5.4 消费消息

5.x 提供两种消费模式，根据 `ConsumerConfig.consumerMode` 选择：

**PushConsumer**（`ConsumerMode.PUSH`）：Broker 自动推送消息，适合处理时间可预测的场景。
消费失败返回 `ConsumeResult.FAILURE` 触发 Broker 侧自动重试。

**SimpleConsumer**（`ConsumerMode.SIMPLE`）：消费者主动 `receive()` + 显式 `ack()`，
适合处理时间不可预测的场景（如 RAG 索引中 LLM 调用）。
无消费超时风险，精确控制并发度和消息可见性。

```java
// RocketMQMessageBus.subscribe()
@Override
public <T> Subscription subscribe(String topic, String group,
                                   ConsumerConfig config,
                                   Class<T> payloadType,
                                   MessageHandler<T> handler) {
    // 消费组名称格式校验（B-05）
    if (group == null || !GROUP_PATTERN.matcher(group).matches()) {
        throw new IllegalArgumentException(
            "Invalid consumer group: '" + group
            + "'. Must be 1-128 chars, alphanumeric/underscore/hyphen only.");
    }
    // synchronized 保护 shutdown-check 和 activeSubscriptions.add() 的原子性（CS-01/CS-02）
    // 防止 TOCTOU 竞态：subscribe() 通过 shutdown 检查后、add() 前，shutdown() 可能已关闭所有 subscription
    synchronized (this) {
        if (shutdown) {
            throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                "MessageBus is shutting down, cannot create new subscriptions");
        }
    }
    // 注入幂等包装
    MessageHandler<T> wrappedHandler = wrapWithIdempotent(handler, topic);

    String fullTopic = properties.topicPrefix() + topic;
    ClientConfiguration clientConfig = this.clientConfiguration;
    FilterExpression filterExpression = new FilterExpression(
        config.tagExpression(), FilterExpressionType.TAG);

    Map<String, FilterExpression> subscriptionExpressions = Map.of(fullTopic, filterExpression);

    try {
        Subscription subscription;
        if (config.consumerMode() == ConsumerMode.SIMPLE) {
            subscription = createSimpleSubscription(topic, group, config,
                payloadType, wrappedHandler, clientConfig, subscriptionExpressions);
        } else {
            subscription = createPushSubscription(topic, group, config,
                payloadType, wrappedHandler, clientConfig, subscriptionExpressions);
        }
        // 二次 synchronized：确保 shutdown 未在 subscription 创建期间触发
        synchronized (this) {
            if (shutdown) {
                // shutdown 已触发，立即关闭刚创建的 subscription 防止泄漏
                try { subscription.close(); } catch (Exception e) { log.warn("Failed to close subscription during shutdown race", e); }
                throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                    "MessageBus is shutting down, cannot create new subscriptions");
            }
            activeSubscriptions.add(subscription);
        }
        return subscription;
    } catch (ClientException e) {
        throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
            "Failed to create subscription: " + topic, e);
    }
}

/** 消费组名称格式：字母数字 + 下划线 + 连字符，1-128 字符 */
private static final java.util.regex.Pattern GROUP_PATTERN =
    java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");

// ==================== PushConsumer ====================

private <T> Subscription createPushSubscription(
        String topic, String group, ConsumerConfig config,
        Class<T> payloadType, MessageHandler<T> handler,
        ClientConfiguration clientConfig,
        Map<String, FilterExpression> subscriptionExpressions) throws ClientException {

    MessageListener pushListener = messageView -> {
        try {
            T payload = codec.decode(
                toByteArray(messageView.getBody()), payloadType);
            MessageEnvelope<T> message = new MessageEnvelope<>(
                messageView.getMessageId().toString(),
                topic,
                messageView.getTag().orElse(null),
                payload,
                null,  // hashKey: 消费端无需
                messageView.getKeys().stream().findFirst().orElse(null),
                messageView.getProperties(),
                messageView.getBornTimestamp()
            );
            handler.onMessage(message);
            log.debug("Message consumed: topic={}, group={}, msgId={}", topic, group, messageView.getMessageId());
            return ConsumeResult.SUCCESS;
        } catch (PermanentConsumeException e) {
            // 永久性错误（反序列化失败、schema 不匹配等）：跳过重试，直接返回 FAILURE
            // Broker 在 maxDeliveryAttempts 后自动路由到 %DLQ%
            log.error("Permanent consume error, skipping retry: topic={}, msgId={}",
                topic, messageView.getMessageId(), e);
            return ConsumeResult.FAILURE;
        } catch (Exception e) {
            log.error("Push consume failed: topic={}, msgId={}",
                topic, messageView.getMessageId(), e);
            return ConsumeResult.FAILURE;
        }
    };

    PushConsumer pushConsumer = provider.newPushConsumerBuilder()
        .setClientConfiguration(clientConfig)
        .setConsumerGroup(group)
        .setSubscriptionExpressions(subscriptionExpressions)
        .setConsumptionThreadCount(config.concurrency())
        .setMessageListener(pushListener)
        .build();

    return new RocketMQSubscription(topic, group,
        pushConsumer, null, null);
}

// ⚠️ C-03 限制说明：5.x PushConsumer 无法跳过 Broker 重试。
// 即使 listener 返回 ConsumeResult.FAILURE，Broker 仍会按 maxDeliveryAttempts 重试，
// 直到耗尽后才路由到 %DLQ%。PermanentConsumeException 无法真正"跳过"重试。
// 对于错误率高的 Topic（如反序列化频繁失败），建议使用 SimpleConsumer，
// 可在 listener 中立即 ack + 转发 DLQ，真正跳过 Broker 重试周期。

// ==================== SimpleConsumer ====================

private <T> Subscription createSimpleSubscription(
        String topic, String group, ConsumerConfig config,
        Class<T> payloadType, MessageHandler<T> handler,
        ClientConfiguration clientConfig,
        Map<String, FilterExpression> subscriptionExpressions) throws ClientException {

    SimpleConsumer simpleConsumer = provider.newSimpleConsumerBuilder()
        .setClientConfiguration(clientConfiguration)
        .setConsumerGroup(group)
        .setAwaitDuration(Duration.ofSeconds(30))
        .setSubscriptionExpressions(subscriptionExpressions)
        .build();

    try {
        return buildSimpleSubscription(topic, group, config,
            payloadType, listener, simpleConsumer);
    } catch (Exception e) {
        try { simpleConsumer.close(); } catch (Exception closeEx) {
            log.warn("Failed to close simpleConsumer after setup failure", closeEx);
        }
        throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
            "Failed to create subscription: " + topic, e);
    }
}

private <T> Subscription buildSimpleSubscription(
        String topic, String group, ConsumerConfig config,
        Class<T> payloadType, MessageHandler<T> handler,
        SimpleConsumer simpleConsumer) {

    // 后台线程轮询 receive()（接收循环单线程）
    ExecutorService receiveExecutor = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "simple-consumer-" + topic));
    AtomicBoolean running = new AtomicBoolean(true);
    int maxRetries = config.retryPolicy().maxRetries();

    // maxRetries=0 时跳过重试计数器，第一次失败直接走 DLQ 路径
    boolean skipRetry = maxRetries <= 0;

    // 重试计数器：msgId → 已重试次数
    // 使用 Caffeine 替代 ConcurrentHashMap：自动过期防止 OOM，覆盖重启场景。
    //
    // 限制：SimpleConsumer 重试计数器是本地/内存的。进程重启后计数重置。
    // 对于关键路径，考虑使用 Redis 支持的计数器或接受重启后 maxRetries 可能不精确。
    //
    // 首选方案：RocketMQ 5.x MessageView 提供 getDeliveryAttempt() 时，
    // 应优先使用该 Broker 端计数（权威来源），替代本地计数器：
    //   int attempts = messageView.getDeliveryAttempt();
    //   if (attempts >= maxRetries) { /* skip to DLQ */ }
    // 详见 §5.5 SimpleConsumer 重试机制。
    //
    // 注意：RocketMQSubscription.close() 中应调用 retryCounter.invalidateAll() 加速内存回收。
    @Nullable Cache<String, AtomicInteger> retryCounter = skipRetry ? null :
        Caffeine.newBuilder()
            .expireAfterWrite(config.invisibleDuration().multipliedBy(maxRetries * 2))
            .build();

    // 处理线程池：有界队列 + AbortPolicy，防止消息积压导致 OOM
    int processingConcurrency = Math.max(1, config.concurrency());
    AtomicInteger threadCounter = new AtomicInteger(0);
    ExecutorService processingPool = new ThreadPoolExecutor(
        processingConcurrency, processingConcurrency, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(config.batchSize() * 2),  // 有界队列，防止 OOM
        r -> new Thread(r, "simple-process-" + topic + "-" + threadCounter.incrementAndGet()),
        new ThreadPoolExecutor.AbortPolicy()  // 队列满时拒绝，由 receive 循环捕获并释放 semaphore
    );

    // 滑动窗口：Semaphore 控制在途消息数，替代 batch-then-wait 模式
    Semaphore inflightSemaphore = new Semaphore(config.concurrency());

    receiveExecutor.submit(() -> {
        long backoffMs = 1000;  // 指数退避初始值（E-04/AR-01）
        while (running.get()) {
            try {
                // 获取一个许可后再拉取消息，保证在途消息数不超过并发度
                if (!inflightSemaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                    continue;  // 所有槽位被占用，等待释放
                }
                List<MessageView> messages = simpleConsumer.receive(
                    Math.min(config.batchSize(), config.concurrency()),
                    config.invisibleDuration());
                backoffMs = 1000;  // 成功拉取，重置退避
                if (messages.isEmpty()) {
                    inflightSemaphore.release();  // 未拉到消息，释放许可
                    continue;
                }
                // 批量拉取：为后续消息尝试获取额外许可（非阻塞）
                // 第 1 条消息已有许可，后续消息需要额外许可
                List<MessageView> processable = new ArrayList<>(messages.size());
                processable.add(messages.get(0));
                for (int i = 1; i < messages.size(); i++) {
                    if (inflightSemaphore.tryAcquire()) {
                        processable.add(messages.get(i));
                    } else {
                        break;  // 槽位不足，剩余消息下次 receive 时拉取
                    }
                }
                for (MessageView messageView : processable) {
                    try {
                        processingPool.submit(() -> processMessage(
                            messageView, topic, group, config, payloadType, listener,
                            simpleConsumer, retryCounter, skipRetry, maxRetries, inflightSemaphore));
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        inflightSemaphore.release();  // 队列满，释放许可
                        log.debug("Processing pool full, releasing semaphore: topic={}", topic);
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Simple receive error, retrying in {}ms: topic={}", backoffMs, topic, e);
                    try {
                        Thread.sleep(Math.min(backoffMs, 60_000));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoffMs = Math.min(backoffMs * 2, 60_000);  // 指数退避，上限 60s
                }
            }
        }
        // 退出循环后优雅关闭处理线程池，等待在途任务完成
        processingPool.shutdown();
        try {
            // 使用 closeTimeout 而非 invisibleDuration（R-01），避免 30min 等待阻塞 shutdown
            long closeTimeoutMs = closeTimeout != null
                ? closeTimeout.toMillis()
                : config.invisibleDuration().toMillis();
            if (!processingPool.awaitTermination(closeTimeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("Processing pool did not terminate within {}ms, forcing shutdown: topic={}",
                    closeTimeoutMs, topic);
                processingPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            processingPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    });

    var subscription = new RocketMQSubscription(topic, group,
        null, simpleConsumer, receiveExecutor);
    subscription.setRunningFlag(running);
    return subscription;
}

/**
 * 处理单条 SimpleConsumer 消息（M-01: 从 receive 循环中提取）。
 * 包含完整的成功/永久错误/可重试错误/DLQ 路径。
 */
private <T> void processMessage(
        MessageView messageView, String topic, String group,
        ConsumerConfig config, Class<T> payloadType,
        MessageHandler<T> handler, SimpleConsumer simpleConsumer,
        @Nullable Cache<String, AtomicInteger> retryCounter,
        boolean skipRetry, int maxRetries,
        Semaphore inflightSemaphore) {
    String msgId = messageView.getMessageId().toString();
    try {
        T payload = codec.decode(
            toByteArray(messageView.getBody()), payloadType);
        MessageEnvelope<T> message = new MessageEnvelope<>(
            msgId, topic,
            messageView.getTag().orElse(null),
            payload, null,
            messageView.getKeys().stream().findFirst().orElse(null),
            messageView.getProperties(),
            messageView.getBornTimestamp()
        );
        handler.onMessage(message);
        simpleConsumer.ack(messageView);
        log.debug("Message consumed and acked: topic={}, group={}, msgId={}", topic, group, msgId);
        if (retryCounter != null) retryCounter.invalidate(msgId);
    } catch (PermanentConsumeException e) {
        handlePermanentError(messageView, topic, group, msgId, simpleConsumer, retryCounter);
    } catch (Exception e) {
        handleRetryableError(messageView, topic, group, msgId, e,
            retryCounter, skipRetry, maxRetries, simpleConsumer, inflightSemaphore);
    } finally {
        inflightSemaphore.release();
    }
}

private void handlePermanentError(MessageView messageView, String topic, String group,
                                   String msgId, SimpleConsumer simpleConsumer,
                                   @Nullable Cache<String, AtomicInteger> retryCounter) {
    log.error("Permanent consume error, forwarding to DLQ: topic={}, msgId={}", topic, msgId);
    if (sendToDeadLetter(messageView, topic, group)) {
        simpleConsumer.ack(messageView);
    } else {
        log.warn("DLQ forward failed for permanent error, message will reappear: topic={}, msgId={}", topic, msgId);
    }
}

private void handleRetryableError(MessageView messageView, String topic, String group,
                                   String msgId, Exception e,
                                   @Nullable Cache<String, AtomicInteger> retryCounter,
                                   boolean skipRetry, int maxRetries,
                                   SimpleConsumer simpleConsumer,
                                   Semaphore inflightSemaphore) {
    if (skipRetry || retryCounter == null) {
        log.error("Simple consume failed (no retry): topic={}, msgId={}", topic, msgId, e);
        if (sendToDeadLetter(messageView, topic, group)) {
            simpleConsumer.ack(messageView);
        }
    } else {
        int attempts = retryCounter.get(msgId, k -> new AtomicInteger(0))
            .incrementAndGet();
        if (attempts >= maxRetries) {
            log.error("Simple consume exhausted retries ({}): topic={}, msgId={}", attempts, topic, msgId, e);
            if (sendToDeadLetter(messageView, topic, group)) {
                simpleConsumer.ack(messageView);
                retryCounter.invalidate(msgId);
            } else {
                // C-02: DLQ 转发失败时清除重试计数器，让消息重新从 0 开始计数
                // 避免无限循环尝试 DLQ 转发
                retryCounter.invalidate(msgId);
                log.warn("DLQ forward failed, retry counter reset for next attempt: topic={}, msgId={}", topic, msgId);
            }
        } else {
            log.warn("Simple consume failed ({}/{}): topic={}, msgId={}", attempts, maxRetries, topic, msgId, e);
            // 不 ack → 消息在 invisibleDuration 后重新可见
        }
    }
}

/**
 * 订阅管理 — PushConsumer/SimpleConsumer 生命周期。
 * <p>
 * close() 幂等保证：使用 {@code AtomicBoolean closed} 守卫，
 * 首次 close 执行清理逻辑（停止 receive 线程、关闭消费者），
 * 后续调用直接返回（no-op）。
 */
// RocketMQSubscription 关键实现：
public class RocketMQSubscription implements Subscription {
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * R-01: close(Duration) 接受超时参数，传递到内部 processingPool.awaitTermination()
     * 和 SimpleConsumer.close()。避免使用不可控的 invisibleDuration 作为关闭超时。
     */
    public void close(Duration timeout) {
        if (!closed.compareAndSet(false, true)) {
            return;  // 幂等：已关闭，直接返回
        }
        // 执行清理逻辑：停止 receive 线程、关闭消费者、释放资源
        // timeout 用于 processingPool.awaitTermination() 和 consumer.close()
        // ...
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(30));  // 默认 30s 超时
    }

    @Override
    public boolean isActive() {
        return !closed.get();  // close 后立即返回 false
    }
}

/**
 * 将消息转发到死信 Topic。
 * <p>
 * 注意：SimpleConsumer 的 DLQ 是应用层实现的。Broker 仅对 PushConsumer 自动路由到
 * %DLQ%{consumerGroup}（基于 maxDeliveryAttempts）。SimpleConsumer 无消费组级别的
 * maxDeliveryAttempts 配置，因此需要应用层在重试耗尽后手动转发。
 * 应用层 DLQ 使用 %APP_DLQ%{consumerGroup} 前缀，避免与 Broker 管理的 PushConsumer DLQ 冲突。
 * <p>
 * 注意：此方法绕过熔断器（直接调用 producer.send()），确保 DLQ 转发不受全局熔断影响。
 *
 * @return true=转发成功, false=转发失败（调用方不应 ack）
 */
private boolean sendToDeadLetter(MessageView messageView, String topic, String group) {
    String msgId = messageView.getMessageId().toString();

    // DC-03 / I-03: Redis SETNX 去重 — 防止 ack 失败导致重复 DLQ 转发
    // 如果消息已进入 DLQ（key 存在），跳过转发并返回 true（视为成功，可安全 ack）
    String dedupKey = "messaging:dlq:sent:" + msgId;
    Boolean isNew = stringRedisTemplate.opsForValue().setIfAbsent(
        dedupKey, "1", properties.invisibleDuration().multipliedBy(2));
    if (Boolean.FALSE.equals(isNew)) {
        log.info("DLQ forward skipped (already sent): topic={}, msgId={}", topic, msgId);
        return true;
    }

    // AR-05: DLQ 转发次数限制 — 防止 DLQ 转发本身无限重试
    // 使用 Redis INCR 计数，超过 3 次则放弃消息（ack + 记录 + 告警）
    String attemptsKey = "messaging:dlq:attempts:" + msgId;
    long attempts = stringRedisTemplate.opsForValue().increment(attemptsKey);
    if (attempts == 1) {
        stringRedisTemplate.expire(attemptsKey, properties.invisibleDuration().multipliedBy(3));
    }
    if (attempts > 3) {
        log.error("DLQ forward exhausted ({} attempts), dropping message: topic={}, msgId={}",
            attempts, topic, msgId);
        meterRegistry.counter("messaging.dead.drop", "topic", topic, "group", group).increment();
        stringRedisTemplate.delete(dedupKey);
        return true;  // 返回 true 让调用方 ack，消息丢弃
    }

    try {
        String dlqTopic = "%APP_DLQ%" + group;
        org.apache.rocketmq.client.apis.message.Message dlqMsg =
            provider.newMessageBuilder()
                .setTopic(dlqTopic)
                .setBody(toByteArray(messageView.getBody()))
                .setKeys(msgId)
                .addProperty("originalTopic", topic)
                .addProperty("originalGroup", group)
                .addProperty("deadAt", Instant.now().toString())
                .build();
        producer.send(dlqMsg);
        log.warn("Message forwarded to DLQ: dlqTopic={}, originalTopic={}, msgId={}",
            dlqTopic, topic, msgId);
        return true;
    } catch (Exception e) {
        // E-02: 异常分类日志 — 区分网络超时、Broker 拒绝、序列化失败等
        stringRedisTemplate.delete(dedupKey);  // 转发失败，清除去重标记允许重试
        String errorType = e.getClass().getSimpleName();
        if (e instanceof java.net.TimeoutException || e.getCause() instanceof java.net.TimeoutException) {
            errorType = "TIMEOUT";
        } else if (e instanceof java.io.IOException) {
            errorType = "IO_ERROR";
        }
        log.error("Failed to forward message to DLQ [{}]: topic={}, msgId={}, attempt={}/3",
            errorType, topic, msgId, attempts, e);
        return false;
    }
}

private byte[] toByteArray(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
}
```

### 5.5 消息确认与重试

**PushConsumer 状态机**（5.x 新模型，替代 4.x 的 ACK/RECONSUME_LATER）：

```
消息生命周期（PushConsumer）：

  Producer.send() → [Broker Topic] → PushConsumer (POP 投递)
                                            │
                                    ┌───────┴───────┐
                                    │               │
                               ConsumeResult    ConsumeResult
                                 .SUCCESS         .FAILURE
                                    │               │
                                  完成      Broker 侧状态机
                                                    │
                                            ┌───────┴───────┐
                                            │               │
                                        Ready 等待      重试耗尽
                                        延迟重投递         │
                                            │         自动进入 %DLQ%
                                            │
                                        PushConsumer
                                        再次投递
```

**5.x PushConsumer 重试特点**：
- 重试间隔和最大投递次数由 Broker 端消费组元数据控制，客户端无法直接修改
- 非顺序消息：增量退避（1s → 5s → 10s → 30s → ...）
- 顺序消息（FIFO Topic）：固定间隔重试
- 重试投递时 `messageId` 不变（5.x 改进，与 4.x 不同）

**SimpleConsumer 重试机制**：
```
消息生命周期（SimpleConsumer）：

  receive(maxMessageNum, invisibleDuration) → List<MessageView>
      │
      ├── 处理成功 → ack(messageView) → 消息完成
      │
      └── 处理失败 → 不 ack → 消息在 invisibleDuration 后重新可见
                     │
                     └── 下次 receive() 再次拉取到该消息
```

- SimpleConsumer 无自动重试状态机，由应用层控制
- `invisibleDuration` 决定失败后多久消息重新可见（等效于重试延迟）
- 应用层在 receive 循环中记录重试次数，超过 `maxRetries` 时 ack 并记录日志
- **重试计数来源**：优先使用 `MessageView.getDeliveryAttempt()`（Broker 端权威计数）；
  若不可用则回退到本地 Caffeine 计数器（进程重启后重置，非精确保证）
- **AR-04 注意**：`getDeliveryAttempt()` 在 SimpleConsumer 场景下返回的是 Broker 侧投递计数，
  应用层进程重启后该计数不会重置。本地 Caffeine 计数器在进程重启后会重置为 0，
  两者可能不一致——此时以 `getDeliveryAttempt()` 为准

**ACK 失败场景**（at-least-once 语义下的标准风险）：

```
1. PushConsumer 处理消息 M 成功，返回 ConsumeResult.SUCCESS
2. 网络抖动导致 Broker 未收到确认
3. Broker 等待超时后重新投递 M
4. 消费者再次处理 M（重复处理）

缓解措施：
- 消费者必须幂等（见 §5.10）
- 这是所有 at-least-once 系统的标准行为
- 当前顺序（处理 → 返回成功）是正确的：崩溃仅导致重复消费，不丢消息
```

**消费超时导致并发重复处理**（仅 PushConsumer）：

PushConsumer 的 `consumeTimeout` 由 Broker 端消费组元数据控制。
如果消费端处理耗时超过此阈值，Broker 将消息重新投递，导致同一条消息被并发处理。

风险场景：
- RAG 索引任务中 LLM 调用可能超过消费超时
- 缓解：对耗时不可预测的任务使用 SimpleConsumer（无超时概念）

缓解：
- 为耗时任务使用 `ConsumerMode.SIMPLE`（推荐）
- 如使用 PushConsumer，确保消费超时设置合理
- 消费端实现幂等（业务层 DB 唯一约束 + 消息总线内建幂等检查，见 §5.10）
- 监控消费耗时 P99 指标（`messaging.consume.latency`）

### 5.6 死信处理

RocketMQ 消费重试超过 `maxDeliveryAttempts` 后，消息自动进入 `%DLQ%ConsumerGroup` Topic。

```java
// RocketMQMessageBus
// R-03: DCL 懒加载缓存 DeadLetterOperations，避免每次调用创建新实例
private volatile DeadLetterOperations deadLetterOps;

@Override
public DeadLetterOperations deadLetterOperations() {
    if (deadLetterOps == null) {
        synchronized (this) {
            if (deadLetterOps == null) {
                deadLetterOps = new DeadLetterOperations() {
                    @Override
                    public List<MessageEnvelope<?>> scanDeadLetters(String topic, int count) {
                        // 订阅 %DLQ%ConsumerGroup Topic 拉取死信消息
                        // 使用 SimpleConsumer 或 Admin API
                    }

                    @Override
                    public void replayDeadLetter(String topic, String messageId) {
                        // 从 DLQ 拉取消息，重新发送到主 Topic
                    }

                    @Override
                    public int deadLetterCount(String topic) {
                        // 通过 Broker Admin 接口查询 DLQ 积压量
                    }
                };
            }
        }
    }
    return deadLetterOps;
}
```

> **S-02**: `DeadLetterOperations` 是运维接口，仅供运维工具和管理界面使用。
> 业务代码不应依赖此接口——业务逻辑应通过正常的消息消费流程处理消息。

> **DLQ 满后行为**：RocketMQ DLQ 是普通 Topic，受 Broker `fileReservedTime`（默认 72h）控制，
> 过期后自动清理。DLQ 积压通过 `messaging.dead.count` + `messaging.consumer.lag` 指标监控。

### 5.7 有序消息

5.x 通过 FIFO Topic 实现有序消息，无需 4.x 的 `MessageQueueSelector` + `MessageListenerOrderly`。

**创建 FIFO Topic**（运维操作，非代码）：

```bash
mqadmin updateTopic -c DefaultCluster -t SMART_RAG_rag_index_document \
    -a +messageType=FIFO -n localhost:9876
```

**发送端 — 设置 messageGroup**：

```java
// 在 buildRocketMQMessage() 中自动设置（仅配置为 ordered 的 Topic 生效）
// MessageEnvelope.ordered(topic, payload, hashKey) → 仅当 topic 在 orderedTopics 中时设置 messageGroup
if (message.hashKey() != null && isOrderedTopic(message.topic())) {
    builder.setMessageGroup(message.hashKey());
}
// hashKey 非空但 topic 未配置为 ordered：hashKey 被忽略，消息作为普通消息发送（无序保证）
```

**消费端 — 无需特殊配置**：

5.x PushConsumer 对 FIFO Topic 自动保证同一 `messageGroup` 的消息按序消费，
无需选择 `MessageListenerOrderly`（5.x 已取消此区分）。

**有序消费约束**：
- 发送端通过 `MessageEnvelope.ordered(topic, payload, hashKey)` 指定分区键。
- FIFO Topic 下，同一 `messageGroup` 的消息严格按序投递。
- 消费端同一 `messageGroup` 的消息串行处理（Broker 端保证）。
- **并发约束**：FIFO Topic 的吞吐受 `messageGroup` 基数影响，
  高基数的 hashKey（如 documentId）可充分利用并行度。

**有序配置判定**：

FIFO Topic 是运维侧创建，但生产端需要知道哪些 Topic 设置 `messageGroup`：

```yaml
app:
  messaging:
    ordered-topics:
      - rag_index_document
```

```java
// RocketMQMessageBus.buildRocketMQMessage()
private boolean isOrderedTopic(String topic) {
    return properties.orderedTopics().contains(topic);
}
// 在 builder 中：仅有序 Topic 设置 messageGroup
if (message.hashKey() != null && isOrderedTopic(message.topic())) {
    builder.setMessageGroup(message.hashKey());
}
```

**Queue 热点问题**：

默认 4 个 Queue。如果 `messageGroup`（hashKey）分布不均（如某些热门文档消息量大），
会导致某个 Queue 积压而其他 Queue 空闲。缓解策略：
- 选择高基数的 `hashKey`（如 documentId 而非 teamId）
- 通过 `mqadmin` 预创建更多 Queue（如 16 或 32）
- 监控各 Queue 积压量（`mqadmin topicStats`），发现热点后扩容

**SimpleConsumer 与 FIFO Topic**：

SimpleConsumer 从 FIFO Topic 拉取消息时，Broker 保证同一 `messageGroup` 的消息按投递顺序返回。
但由于 SimpleConsumer 采用滑动窗口模式（Semaphore 控制并发），同一 `messageGroup` 的多条消息
可能被并发处理（不同线程同时获得 Semaphore 许可）。

如果业务要求同一 `messageGroup` 严格串行处理，需要在消费端额外约束：
- 方案 1：Semaphore 许可数设为 1（`concurrency=1`），牺牲吞吐换取严格有序
- 方案 2：在 processingPool 中对同一 `messageGroup` 的消息串行化（如按 hashKey 分组到单线程执行器）
- 方案 3：对于有序要求严格的场景，优先使用 PushConsumer（Broker 端保证同一 messageGroup 串行投递）

当前 RAG 索引场景（§7.3）使用 `hashKey=documentId`，同一文档的消息需按序处理。
建议使用方案 2：按 `hashKey` 分组到 `SingleThreadExecutor`，既保证有序又维持整体并发度。
如 Phase 1 不需严格有序（文档版本可覆盖），可暂不实现，后续按需加入。

### 5.8 Tag 过滤

```java
// 发送端 — 指定 Tag
messageBus.send(MessageEnvelope.of("chat_message", "save", payload));

// 消费端 — FilterExpression 按 Tag 过滤（构造时传入 ConsumerConfig）
messageBus.subscribe("chat_message", "save-group",
    ConsumerConfig.builder()
        .tagExpression("save")
        .build(),
    ChatMessagePayload.class,
    listener);

// 多 Tag 过滤
messageBus.subscribe("chat_message", "all-group",
    ConsumerConfig.builder()
        .tagExpression("save || update || delete")
        .build(),
    ChatMessagePayload.class,
    listener);
```

5.x 使用 `FilterExpression` 对象替代 4.x 的字符串过滤表达式。
`RocketMQMessageBus.subscribe()` 内部自动将 `ConsumerConfig.tagExpression()` 转换为
`new FilterExpression(tagExpression, FilterExpressionType.TAG)`。

Tag 过滤在 Broker 端执行，减少网络传输，比应用层过滤更高效。

### 5.9 优雅关闭

```java
void shutdown() {
    // E-03: 防御性检查 — 若 init() 失败或未调用，producer/subscriptions 可能为空
    if (producer == null && activeSubscriptions.isEmpty()) {
        shutdown = true;
        return;
    }

    Duration total = properties.shutdownTimeout();
    // 关闭顺序至关重要：(1) 停止接收新订阅 → (2) 停止消费者 → (3) 关闭生产者
    // 原因：如果先关 Producer，send() 会在消费端 close() 期间失败（如 fallback 写入场景）
    shutdown = true;  // 1. 停止接受新订阅

    // 超时分配：Subscriptions 70%，Producer 30%
    // 无 sendExecutor（5.x Producer 原生 sendAsync() 基于 gRPC async stub，无需线程池）
    // Producer.close() 等待在途 async send 完成，分配 30%
    Duration subscriptionTimeout = total.multipliedBy(70).dividedBy(100);
    Duration producerTimeout = total.minus(subscriptionTimeout);

    // 2. 关闭所有 Subscription（停止消费者），将剩余 deadline 传递给 close()（R-01）
    Deadline deadline = Deadline.after(subscriptionTimeout.toMillis(), TimeUnit.MILLISECONDS);
    for (RocketMQSubscription sub : activeSubscriptions) {
        long remaining = deadline.remainingTime(TimeUnit.MILLISECONDS);
        if (remaining <= 0) {
            log.warn("Shutdown timeout exhausted, skipping remaining {} subscriptions",
                activeSubscriptions.size() - activeSubscriptions.indexOf(sub));
            break;
        }
        // R-01: close(Duration) 将 timeout 传递到内部的 processingPool.awaitTermination()
        // 和 SimpleConsumer.close()，避免使用不可控的 invisibleDuration 作为超时
        sub.close(Duration.ofMillis(remaining));
    }
    // 3. 关闭 Producer（E-03: null 检查防止 init 失败场景的 NPE）
    if (producer != null) {
        try {
            producer.close();
            // 注意：5.x Producer.close() 不接受超时参数，依赖客户端内部超时
        } catch (IOException e) {
            log.warn("Error closing producer", e);
        }
    }
}
```

**关闭保证**：
- **PushConsumer 关闭**：`PushConsumer.close()` 停止接收新消息，
  Broker 端等待当前处理中的消息超时后重新投递。
- **SimpleConsumer 关闭**：停止后台 receive 线程，`SimpleConsumer.close()` 释放资源。
  未 ack 的消息在 `invisibleDuration` 后由 Broker 重新投递。
- **Producer 关闭**：`Producer.close()` 等待在途消息完成发送（5.x 实现 `AutoCloseable`）。
- **超时控制**：`MessagingProperties.shutdownTimeout`（默认 30s），按 70/30 比例分配给 Subscriptions、Producer。超时组件强制关闭，避免一个组件阻塞整个关闭流程。
- **未确认消息**：关闭后未 ACK 的消息由 Broker 自动重新投递（两种模式均适用）。

### 5.10 幂等消费

> at-least-once 语义下消息可能重复投递（消费者崩溃后 Broker 重新投递、
> 网络抖动导致重复 ACK、消费超时后 Broker 重新投递）。
> 消息总线提供内建幂等检查作为通用安全网，业务层幂等作为精确保证。

**设计决策：内建集成而非 AOP**

> `@IdempotentConsume` AOP 方案在本项目中不可行，原因：
> 1. `MessageHandler` 以 lambda 形式传入 `subscribe()`，Spring AOP 无法拦截 lambda。
> 2. 5.x 重试投递时消息从 Broker 侧状态机重新投递，
>    `messageId` 在 5.x 中保持不变（改进），但以业务 `deduplicationKey` 为幂等 key 更可靠。
>
> 改为在 `RocketMQMessageBus` 的 listener 包装层内集成幂等检查，
> 使用 `deduplicationKey` 作为幂等 key。

**幂等 Key 选择**：

| 场景 | 幂等 Key 来源 | 说明 |
|------|------------|------|
| `MessageEnvelope.deduplicationKey != null` | 生产端显式指定 | 跨重试稳定，推荐 |
| `MessageEnvelope.deduplicationKey == null` | 无稳定 key | 跳过总线级幂等，完全依赖业务层 |

**实现方式**：

> **P-02 / I-01 / DC-02**: 使用 Lua 脚本实现原子"先标记、失败回滚"幂等检查，
> 消除原 GET+SET 两轮 RTT 的并发窗口。遵循项目 `TokenCacheService` 的
> `DefaultRedisScript<>` 模式。

```java
// ==================== Lua 脚本定义 ====================

/**
 * IDEMPOTENT_MARK: 原子 SETNX — 标记消息正在处理。
 * KEYS[1] = messaging:idempotent:{topic}:{deduplicationKey}
 * ARGV[1] = TTL in seconds
 * Returns: 1 = key exists (duplicate, skip), 0 = mark succeeded (proceed)
 */
private static final RedisScript<Long> IDEMPOTENT_MARK = new DefaultRedisScript<>(
    "local result = redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) " +
    "if result then return 0 end " +
    "return 1",
    Long.class
);

// RocketMQMessageBus — 幂等检查包装
public class RocketMQMessageBus implements MessageBus {
    private final StringRedisTemplate redis;
    private final MessagingProperties properties;

    /**
     * 包装 listener，注入幂等检查。
     * <p>
     * P-02: 使用 Lua 原子 SETNX（"先标记"），消除 GET+SET 两轮 RTT 的并发窗口。
     * I-01: 标记与执行之间无窗口 — 标记成功后只有一个消费者能执行 listener。
     * <p>
     * 流程：
     * 1. Lua SETNX with TTL → if exists (返回 1), skip (duplicate)
     * 2. Execute handler.onMessage(msg)
     * 3. On success: key stays (marked as processed, TTL 自动过期)
     * 4. On failure: DELETE key (unmark, 允许合法重试通过)
     * 5. On Redis failure: degrade to business-layer idempotent
     */
    <T> MessageHandler<T> wrapWithIdempotent(
            MessageHandler<T> handler, String topic) {
        if (!properties.idempotent().enabled()) {
            return handler;
        }
        return msg -> {
            String idempotentKey = msg.deduplicationKey();
            if (idempotentKey == null || idempotentKey.isEmpty()) {
                handler.onMessage(msg);  // 无幂等 key，直接放行
                return;
            }
            String redisKey = "messaging:idempotent:" + topic + ":" + idempotentKey;
            boolean marked = false;
            try {
                // 步骤 1：Lua 原子标记（SETNX + EX 原子操作，1 RTT）
                Long isDuplicate = redis.execute(
                    IDEMPOTENT_MARK,
                    List.of(redisKey),
                    String.valueOf(properties.idempotent().ttlSeconds()));
                if (isDuplicate != null && isDuplicate == 1L) {
                    log.info("Duplicate message skipped: topic={}, key={}",
                        topic, idempotentKey);
                    return;
                }
                marked = true;

                // 步骤 2：执行消费逻辑（此时 Redis 已标记，并发消费者被阻断）
                handler.onMessage(msg);
                // 步骤 3：消费成功 — key 保留，TTL 自动过期
            } catch (Exception e) {
                if (marked) {
                    // listener 执行失败：DELETE key（unmark），允许合法重试
                    try { redis.delete(redisKey); } catch (Exception de) { /* ignore */ }
                    throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                }
                // Redis 操作失败（Lua 执行异常）：降级到业务层幂等
                log.warn("Idempotent check failed (Redis unavailable), " +
                         "delegating to business-layer: topic={}", topic, e);
                if (meterRegistry != null) {
                    meterRegistry.counter("messaging.idempotent.degraded", "topic", topic).increment();
                }
                try {
                    handler.onMessage(msg);
                } catch (Exception listenerEx) {
                    log.error("Listener failed during Redis-degraded path: topic={}, key={}",
                        topic, idempotentKey, listenerEx);
                    throw listenerEx;
                }
            }
        };
    }
}
```

**I-02 启动校验**：幂等 TTL 必须大于最大重试周期的 2 倍，避免重试时 key 已过期导致幂等失效。

```java
// MessagingProperties 校验（在 @PostConstruct 或 record compact constructor 中）
if (idempotent.enabled()) {
    long minTtl = invisibleDuration.toSeconds() * maxRetries * 2;
    if (idempotent.ttlSeconds() < minTtl) {
        throw new IllegalStateException(
            "idempotent.ttlSeconds (" + idempotent.ttlSeconds() +
            ") must be >= invisibleDuration * maxRetries * 2 (" + minTtl + ")");
    }
}
```

**subscribe() 集成点**：

幂等包装在 `subscribe()` 中、创建 5.x 消费者之前注入。
对 PushConsumer 和 SimpleConsumer 均适用——包装后的 `MessageHandler<T>`
在内部消费循环中被调用。

> **两层幂等的关系与执行顺序**：消息总线内建幂等采用"先标记、失败回滚"策略——
> 通过 Lua SETNX 原子标记后执行 listener，成功则保留标记（TTL 自动过期），
> 失败则 DELETE 回滚标记，确保合法重试不受阻断。
> Lua 原子操作消除了并发窗口（两个消费者不可能同时通过 SETNX），
> 但极端情况（Redis 故障、TTL 过期后重试）仍由业务层 DB 唯一约束兜底。
> 业务层幂等（DB 唯一约束 / 自然键）覆盖所有极端情况
> （Redis 故障、幂等 key 未设置、TTL 过期后重试）。两者互补，缺一不可。

> **DEGRADE-3 降级风险声明**：当 Redis 不可用时，幂等包装降级为直接调用 listener（L1833-1834），
> 此时并发重复消息可能同时通过降级路径，导致 listener 被双重执行。
> 这是 at-least-once 语义下有意为之的权衡——宁可重复处理也不阻塞消费（Redis 持续不可用会阻塞所有消费）。
> 降级概率 = P(Redis 故障) × P(并发重复消息同时到达)，极低但非零。
> 因此 **业务层 DB 唯一约束是消息消费幂等的最后防线，不是可选优化**。

> **消费端幂等 Checklist**（每个 listener 实现前必须确认）：
> - [ ] 业务 listener 是否实现了 DB 唯一约束或自然键去重？
> - [ ] `deduplicationKey` 是否覆盖所有重试场景（包括 Redis 降级路径）？
> - [ ] 幂等 key TTL 是否大于 Broker 最大重试窗口？（见 I-02 启动校验）
> - [ ] 副作用操作（推送外部系统、发送邮件）是否幂等或使用 Outbox 模式？

> **部分完成与副作用恢复**：通知类副作用（推送外部系统、发送邮件等）必须保证幂等。
> 对于非幂等的副作用操作，应使用 Outbox 模式（将副作用写入 DB Outbox 表，
> 由独立组件异步投递）或确保 at-least-once 语义在业务上可接受。
> 消息总线仅保证消息的 at-least-once 投递，不提供副作用的事务性保证。

### 5.11 消费组负载均衡

**5.x POP 模式**（核心改进）：

5.x gRPC 客户端使用 POP 消费模式替代 4.x 的 PULL 模式。
POP 模式的关键区别是**消息级负载均衡**：

| 维度 | 4.x PULL 模式 | 5.x POP 模式 |
|------|--------------|-------------|
| 分配粒度 | Queue 级（整个 Queue 分配给一个消费者） | 消息级（每条消息可投递给不同消费者） |
| 扩缩容 | Rebalance（重新分配所有 Queue，暂停 20-30s） | 无需 Rebalance，新消费者自动参与消费 |
| 消费者空闲 | 消费者数 > Queue 数时部分空闲 | 所有消费者均可接收消息 |
| 顺序保证 | Queue 内有序（依赖 Queue 分配） | FIFO Topic + messageGroup 保证 |

**PushConsumer 负载均衡**：

PushConsumer 使用 POP 模式自动进行消息级负载均衡。
消费者上线/下线时 Broker 自动调整投递目标，无需客户端 Rebalance。
这意味着消费者实例数不受 Queue 数量限制。

**SimpleConsumer 负载均衡**：

SimpleConsumer 通过 `receive()` 从 Broker 拉取消息，多实例并发拉取时
Broker 按消息粒度分配（同一消息仅分配给一个实例）。

**FIFO Topic 的有序保证**：

在 POP 模式下，FIFO Topic 的有序性通过 `messageGroup` 实现：
同一 `messageGroup` 的消息始终路由到同一消费者实例，保证按序处理。
消费者实例变更时，Broker 自动将 `messageGroup` 的后续消息路由到新实例。

**补充监控指标**：

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `messaging.consumer.assigned.groups` | Gauge | group, instance | 当前处理的 messageGroup 数量 |

### 5.12 运维前置条件

> 以下为一次性运维操作，必须在应用启动前完成。建议纳入 CI/CD 部署流程或运维 SOP。

> **Topic 命名约定（重要）**：
> - **业务代码层**：`MessageEnvelope.topic()` 与 `EtlDocumentConsumer.TOPIC` 等常量使用**裸名**（如 `rag_index_document`、`chat_message_save`）
> - **应用配置层**：`app.messaging.ordered-topics` 配置项同样使用裸名（与 `MessageEnvelope.topic()` 字段一致）
> - **RocketMQ 物理层**：`RocketMQMessageBus.send()/subscribe()` 在内部自动拼接 `topicPrefix + topic`（默认前缀 `SMART_RAG_`，由 `app.messaging.topic-prefix` 配置）
> - **运维脚本层**：`mqadmin` 命令必须使用**带前缀的全名**（如 `SMART_RAG_rag_index_document`），与 Broker 上的物理 Topic 名匹配
>
> 这套分层命名是为了：业务代码不感知前缀，运维侧通过前缀做环境隔离（如 `DEV_SMART_RAG_`、`PROD_SMART_RAG_`）。

**Topic 创建命令**：

```bash
# 聊天消息保存（标准 Topic）
mqadmin updateTopic -c DefaultCluster -t SMART_RAG_chat_message_save \
    -n localhost:9876

# Token 用量记录（标准 Topic）
mqadmin updateTopic -c DefaultCluster -t SMART_RAG_chat_usage_record \
    -n localhost:9876

# RAG 索引文档（FIFO Topic，有序消息）
mqadmin updateTopic -c DefaultCluster -t SMART_RAG_rag_index_document \
    -a +messageType=FIFO -n localhost:9876
```

**消费组配置**：

```bash
# PushConsumer 消费组：设置 maxDeliveryAttempts（默认 16）
mqadmin updateSubGroup -c DefaultCluster -g save-group \
    -a maxDeliveryAttempts=16 -n localhost:9876

mqadmin updateSubGroup -c DefaultCluster -g usage-group \
    -a maxDeliveryAttempts=16 -n localhost:9876

# SimpleConsumer 消费组：maxDeliveryAttempts 无效（SimpleConsumer 由应用层控制重试），
# 但需创建消费组以保证 Broker 注册
mqadmin updateSubGroup -c DefaultCluster -g index-group \
    -n localhost:9876
```

**Queue 数量规划建议**：

| Topic | 类型 | 建议 Queue 数 | 理由 |
|-------|------|--------------|------|
| `SMART_RAG_chat_message_save` | 标准 | 4（默认） | 吞吐适中，默认足够 |
| `SMART_RAG_chat_usage_record` | 标准 | 4（默认） | 低频写入，默认足够 |
| `SMART_RAG_rag_index_document` | FIFO | 16-32 | messageGroup 基数为 documentId，避免 Queue 热点 |

> **注意**：FIFO Topic 的 Queue 数影响有序消费的并行度。Queue 过少会导致 messageGroup 热点；
> Queue 过多会增加 Broker 内存开销。建议初始 16，根据 `mqadmin topicStats` 监控调整。

> **Phase C 启动前检查项**（必须全部满足才能开始 §9 Phase C 实现）：
> - [ ] Topic `SMART_RAG_chat_message_save` 已创建（标准 Topic）
> - [ ] Topic `SMART_RAG_chat_usage_record` 已创建（标准 Topic）
> - [ ] 消费组 `save-group` 已创建，`maxDeliveryAttempts=16`
> - [ ] 消费组 `usage-group` 已创建，`maxDeliveryAttempts=16`
> - [ ] §5.10 幂等检查依赖的 Redis 与 Caffeine 已就绪（Phase A 已落地）
> - [x] 消息总线 always-on — Phase 0 已移除 `app.messaging.enabled` 开关，`RocketMQMessageBus` 无条件装配（无需手动开启）
> - [ ] LLM SPI candidate ID 链路打通（commit `a98fa9b` 已落地）

**初始化脚本模板**（`scripts/init-rocketmq-topics.sh`）：

```bash
#!/bin/bash
set -euo pipefail
NAMESRV=${ROCKETMQ_NAMESRV:-localhost:9876}
CLUSTER=${CLUSTER_NAME:-DefaultCluster}

echo "=== Creating topics ==="
mqadmin updateTopic -c "$CLUSTER" -t SMART_RAG_chat_message_save -n "$NAMESRV"
mqadmin updateTopic -c "$CLUSTER" -t SMART_RAG_chat_usage_record -n "$NAMESRV"
mqadmin updateTopic -c "$CLUSTER" -t SMART_RAG_rag_index_document \
    -a +messageType=FIFO -n "$NAMESRV"

echo "=== Creating consumer groups ==="
mqadmin updateSubGroup -c "$CLUSTER" -g save-group \
    -a maxDeliveryAttempts=16 -n "$NAMESRV"
mqadmin updateSubGroup -c "$CLUSTER" -g usage-group \
    -a maxDeliveryAttempts=16 -n "$NAMESRV"
mqadmin updateSubGroup -c "$CLUSTER" -g index-group -n "$NAMESRV"

echo "=== RocketMQ init complete ==="
```

## 6. Spring 集成

### 6.1 配置属性

```java
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
    String topicPrefix,
    Duration shutdownTimeout,
    Set<String> orderedTopics,
    IdempotentConfig idempotent,
    CircuitBreakerConfig circuitBreaker,
    RocketMQConfig rocketmq
) {
    public MessagingProperties {
        if (topicPrefix == null || topicPrefix.isEmpty()) topicPrefix = "SMART_RAG_";
        if (shutdownTimeout == null) shutdownTimeout = Duration.ofSeconds(30);
        if (orderedTopics == null) orderedTopics = Collections.emptySet();
        if (idempotent == null) idempotent = new IdempotentConfig(true, 900);
        if (circuitBreaker == null) circuitBreaker = new CircuitBreakerConfig(5, 30000);
        if (rocketmq == null) rocketmq = new RocketMQConfig(null, null,
            null, 0, 0, null, null, null);
    }

    /** 幂等检查配置 */
    public record IdempotentConfig(
        boolean enabled,
        /** Redis key TTL in seconds — covers broker retry window (default 15 min) */
        long ttlSeconds
    ) {
        public IdempotentConfig {
            if (ttlSeconds <= 0) ttlSeconds = 900;
        }
        // TTL 校验：幂等 key 的 TTL 必须大于最大重试窗口，否则 TTL 过期后重试消息无法被幂等拦截。
        // 最小 TTL = invisibleDuration * maxRetries * 2（SimpleConsumer 默认：10min * 5 * 2 = 100min）。
        // 默认 900s（15min）适用于 PushConsumer（Broker 端 maxDeliveryAttempts * 重试间隔）
        // 和 SimpleConsumer 场景；如重试窗口较长需调大。
    }

    /** 熔断配置 */
    public record CircuitBreakerConfig(
        int failureThreshold,          // 连续失败次数阈值
        long cooldownMillis            // 熔断冷却时间（30s）
    ) {
        public CircuitBreakerConfig {
            if (failureThreshold <= 0) failureThreshold = 5;
            if (cooldownMillis <= 0) cooldownMillis = 30000;
        }
    }

    /** RocketMQ 5.x 客户端配置 */
    public record RocketMQConfig(
        String endpoints,                           // 必填，无默认值
        String producerGroup,
        Duration requestTimeout,
        int maxDeliveryAttempts,                    // 仅作文档参考，实际由 Broker 端消费组元数据决定（见下方说明）
        int maxMessageSize,                         // 4MB（含 payload + headers + properties，
                                                   // 实际 payload 应预留 ~1KB 给 headers 开销，
                                                   // 以避免接近 gRPC maxInboundMessageSize 限制）
        @Nullable Boolean enableSsl,                // 启用 TLS（gRPC over TLS）
        @Nullable String accessKey,                 // ACL 配置：可选，内网部署可不启用
        @Nullable String secretKey
    ) {
        public RocketMQConfig {
            if (producerGroup == null || producerGroup.isEmpty()) producerGroup = DEFAULT_PRODUCER_GROUP;
            if (requestTimeout == null) requestTimeout = Duration.ofSeconds(3);
            if (maxDeliveryAttempts <= 0) maxDeliveryAttempts = 16;
            if (maxMessageSize <= 0) maxMessageSize = 4194304;
        }
        public static final String DEFAULT_PRODUCER_GROUP = "smart-rag-producer";
    }
}
```

> **消息总线 always-on（Phase 0，2026-06 调整）**：
> 原设计的 `app.messaging.enabled` 开关与 `NoOpMessageBus` 已**移除**。原因：开关默认缺失时
> `@ConditionalOnProperty(matchIfMissing=true)` 使 NoOp 生效，导致 app 在 broker 已就绪的情况下
> 仍跑在 NoOp 上、静默丢弃所有消息（usage / RAG 索引 / chat 落库）。`MessagingAutoConfiguration`
> 现为无条件 `@Configuration`，`RocketMQMessageBus` 始终装配。`endpoints` 默认 `localhost:8081`
> 指向 Docker broker 的 Proxy，各环境通过 `ROCKETMQ_ENDPOINTS` 覆盖。业务代码注入 `MessageBus`
> 无需判空；运行期 broker 不可达时由 publisher 端 `MessagingException` 降级（见 §7.x）+ 熔断兜底。

> **`app.messaging.rocketmq.max-delivery-attempts` 仅作文档参考**：
> PushConsumer 的实际最大投递次数由 **Broker 端消费组元数据** `maxDeliveryAttempts` 决定，
> 通过 `mqadmin updateSubGroup -a maxDeliveryAttempts=N` 配置（见 §5.12）。
> 客户端代码无法直接 set。本字段保留在 record 中用于：
> - 与 Broker 端配置对齐的文档参考
> - §5.10 启动时 I-02 校验（幂等 TTL ≥ `invisibleDuration * maxRetries * 2`）读取
>
> SimpleConsumer 模式下，本字段无效——重试次数由应用层 `RetryPolicy.maxRetries` 控制（见 §4.6）。

> **Spring Boot record 绑定注意事项**：Spring Boot 3.x 对 record 使用构造器绑定，
> 不走 setter。本项目惯例使用 compact constructor 赋默认值（`if (x == null) x = default`），
> 与项目已有的 `ChatFallbackProperties`、`SnowflakeProperties` 等风格一致。
> 未在 YAML 中配置的字段由 Spring 传入 null/0/false（原始类型），compact constructor
> 负责补全。`endpoints` 故意不设默认值，强制用户显式配置。

**`application.yml` 配置示例**：

```yaml
app:
  messaging:
    topic-prefix: "SMART_RAG_"
    shutdown-timeout: 30s
    ordered-topics:
      - rag_index_document
    idempotent:
      enabled: true
      ttl-seconds: 900       # 15min
    circuit-breaker:
      failure-threshold: 5
      cooldown-millis: 30000
    rocketmq:
      endpoints: ${ROCKETMQ_ENDPOINTS:localhost:9876}
      producer-group: smart-rag-producer
      request-timeout: 3s
      max-delivery-attempts: 16    # 仅作文档参考，实际由 Broker 端消费组元数据决定
      max-message-size: 4194304    # 4MB
      # S-01: ACL 凭证必须通过环境变量注入，禁止硬编码。
      # JVM heap dump 会暴露明文配置值，生产环境务必使用 secrets 管理工具。
      # access-key: ${ROCKETMQ_ACCESS_KEY:}
      # secret-key: ${ROCKETMQ_SECRET_KEY:}
```

### 6.2 Auto-Configuration

```java
// Phase 0 (2026-06)：无条件装配 — 移除原 app.messaging.enabled 开关与 NoOpMessagingConfiguration。
// RocketMQMessageBus 始终激活；运行期 broker 不可达由 publisher 端降级（§7.x）+ 熔断兜底。
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    ClientServiceProvider rocketmqClientServiceProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean(destroyMethod = "shutdown")
    MessageBus rocketMQMessageBus(MessagingProperties properties,
                                   MessagePayloadCodec codec,
                                   ClientServiceProvider provider,
                                   @Autowired(required = false) StringRedisTemplate redis,
                                   @Autowired(required = false) MeterRegistry meterRegistry) {
        RocketMQMessageBus bus = new RocketMQMessageBus(properties, codec, provider, meterRegistry);
        if (redis != null) {
            bus.setRedisTemplate(redis);
        }
        return bus;
    }
}
```

> **与 4.x starter 的区别**：不依赖 `rocketmq-spring-boot-starter`（4.x 封装），
> 直接使用 `rocketmq-client-java`（5.x gRPC 客户端）。`ClientServiceProvider`、
> `Producer`、`PushConsumer`/`SimpleConsumer` 的生命周期由 `RocketMQMessageBus` 内部管理。

### 6.3 序列化抽象

```java
/**
 * 消息序列化 — 预留不同编码格式（JSON、Protobuf、Avro）。
 * 默认使用 Jackson JSON，与项目已有的 Redisson JsonJacksonCodec 一致。
 */
public interface MessagePayloadCodec {
    byte[] encode(Object payload);
    <T> T decode(byte[] data, Class<T> type);
}
```

**JacksonMessageCodec Schema 演进配置**：

```java
@Component
public class JacksonMessageCodec implements MessagePayloadCodec {
    private final ObjectMapper objectMapper;

    /**
     * P-03: 注入 Spring 自动配置的 ObjectMapper（copy + 定制），
     * 而非 new ObjectMapper()。保持与项目全局序列化配置一致
     * （如 JavaTimeModule、日期格式等已由 Spring Boot 自动注册）。
     */
    public JacksonMessageCodec(ObjectMapper springObjectMapper) {
        this.objectMapper = springObjectMapper.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public byte[] encode(Object payload) {
        return objectMapper.writeValueAsBytes(payload);
    }

    @Override
    public <T> T decode(byte[] data, Class<T> type) {
        return objectMapper.readValue(data, type);
    }
}
```

**Schema 演进规则**：

1. **仅允许加法变更**：新增字段必须提供默认值（`@DefaultValue` 或 `null`）。
   旧消费者遇到未知字段时，`FAIL_ON_UNKNOWN_PROPERTIES=false` 会静默忽略。
2. **禁止破坏性变更**：不重命名字段、不删除字段、不更改字段类型。
   如需废弃字段，保留为 `@Deprecated` 并保留默认值。
3. **Content-Type 协商**：`MessageEnvelope.headers` 默认包含 `"Content-Type": "application/json"`，
   预留未来格式协商（如 `application/protobuf`）。
   生产端在 `buildRocketMQMessage()` 中自动设置：

   ```java
   // 在 MessageEnvelope.of() 和 MessageEnvelope.deduplicated() 的默认 headers 中
   // 已包含 "Content-Type": "application/json"
   ```

#### 6.3.1 破坏性变更策略

当不可避免的破坏性变更发生时（如字段类型变更、payload 结构重组），遵循以下策略：

1. **创建新 Topic**：使用版本化 Topic 名称（如 `chat_message_save.v2`），旧 Topic 继续运行。
2. **创建新消费组**：新 Topic 使用独立消费组（如 `save-group-v2`），避免与旧消费者冲突。
3. **旧 Topic 设置 TTL 过期**：通过 Broker 管理接口设置旧 Topic 的 `fileReservedTime`，
   或在确认所有消费者迁移后关闭旧 Topic。
4. **迁移期双版本兼容**：消费者在迁移期间需同时处理新旧两种 payload 格式。
   可通过 `MessageEnvelope.headers` 中的 `Content-Type` 或自定义 `schema-version` 头区分版本：

   ```java
   // 消费端根据 schema-version 头选择解析路径
   String schemaVersion = msg.headers().getOrDefault("schema-version", "1");
   if ("1".equals(schemaVersion)) {
       V1Payload payload = codec.decode(data, V1Payload.class);
       // 适配转换
   } else {
       V2Payload payload = codec.decode(data, V2Payload.class);
       // 直接处理
   }
   ```

5. **迁移完成后清理**：确认旧 Topic 无新消息写入后，停止旧消费者，删除旧 Topic 和旧消费组。

> **何时使用此策略**：仅在 Schema 演进规则无法兼容时使用（如字段类型变更）。
> 常规的加法变更（新增字段）应遵循 §6.3 的 Schema 演进规则，无需创建新 Topic。

### 6.4 健康检查

```java
/**
 * 消息总线健康检查 — 监控 Producer 连通性、订阅活跃度和熔断器状态。
 * <p>
 * 通过 Spring Boot Actuator /health 端点暴露，支持运维监控和告警。
 */
@Component
public class MessagingHealthIndicator extends AbstractHealthIndicator {

    private final MessageBusManagement busManagement;

    public MessagingHealthIndicator(MessageBusManagement busManagement) {
        this.busManagement = busManagement;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        // 1. 检查 Producer 连通性
        boolean producerHealthy = busManagement.isProducerHealthy();
        if (!producerHealthy) {
            builder.down()
                .withDetail("producer", "unreachable")
                .withDetail("action", "Check RocketMQ Broker/Proxy connectivity");
            return;
        }

        // 2. 检查活跃订阅数
        int activeSubscriptions = busManagement.activeSubscriptionCount();
        if (activeSubscriptions == 0) {
            builder.up()
                .withDetail("producer", "healthy")
                .withDetail("subscriptions", "none")
                .withDetail("warning", "No active subscriptions registered");
            return;
        }

        // 3. 检查熔断器状态
        String circuitBreakerState = busManagement.circuitBreakerState();

        builder.up()
            .withDetail("producer", "healthy")
            .withDetail("activeSubscriptions", activeSubscriptions)
            .withDetail("circuitBreaker", circuitBreakerState);
    }
}
```

**健康检查项**：

| 检查项 | 判定条件 | DOWN 触发 |
|--------|----------|-----------|
| Producer 连通性 | 检查 producer 非空 + 内部状态（5.x Producer 基于 Guava Service，可通过 ((Service) producer).isRunning() 判断；如不可访问则降级为 producer != null 检查） | Broker/Proxy 不可达 |
| 活跃订阅数 | `MessageBusManagement.activeSubscriptionCount()` | 降级为 UP + 警告 |
| 熔断器状态 | `MessageBusManagement.circuitBreakerState()` 返回 open/closed/half-open | 仅作为 detail 展示，不单独触发 DOWN |

在 `MessagingAutoConfiguration` 中注册：

```java
@Bean
HealthIndicator messagingHealthIndicator(MessageBusManagement busManagement) {
    return new MessagingHealthIndicator(busManagement);
}
```

> **设计说明**：健康检查方法（`isProducerHealthy()`、`activeSubscriptionCount()`、`circuitBreakerState()`）
> 从 `MessageBus` SPI 接口分离到独立的 `MessageBusManagement` 接口，避免业务代码依赖管理方法。
> `RocketMQMessageBus` 同时实现 `MessageBus` 和 `MessageBusManagement`，
> Spring 自动按类型注入。

> **S-04 Actuator 安全配置**：健康检查暴露了 Producer 连通性、活跃订阅数、熔断器状态等内部信息。
> 熔断器开启意味着 Broker 不可用，是潜在攻击窗口。生产环境必须限制 health 端点访问：
> ```yaml
> management:
>   endpoints:
>     web:
>       exposure:
>         include: health,info,metrics  # 按需暴露，禁止使用 *
>   endpoint:
>     health:
>       show-details: when-authorized   # 仅认证用户可见详情
> ```
> 并通过 Spring Security 限制 `/actuator/health` 的访问角色。

## 7. 业务集成方式

### 7.1 聊天消息异步保存

> **接入点（2026-06 更新）**：当前 chat 模块走 **Mode Strategy** 双路径。
> publisher 必须同时挂载在两处，否则流式响应（多轮对话主路径）会持续走同步落库。
> - 同步路径：`ChatServiceImpl.processResult()` L190（替换 `conversationHelper.saveMessagesAndNotify(...)` 一行）
> - 流式路径：`MultiTurnModeStrategy.executeStream()` L96 的 `Flux.doFinally`（替换其中 saveMessagesAndNotify 调用）

> **payload 模型**：使用 `ChatMessagePayload(String conversationId, String userMessage, String assistantContent, String candidateId)`。
> 字段名 `candidateId` 取代旧设计的模型标识符字段名，对齐 LLM SPI 统一（见 §1.3）。

```java
// 发送端 — 共用 publisher（同步与流式两路径共用）
@Component
public class ChatMessagePublisher {
    private final MessageBus messageBus;
    private final ChatConversationHelper conversationHelper;

    /**
     * 发送消息保存事件，失败时降级为同步写入。
     * 不使用 Transactional Outbox——同 JVM 内异步解耦，
     * send() 失败时回退到同步路径即可。
     */
    public void publishMessageSave(String conversationId, String userMessage,
                                    String assistantContent, String candidateId,
                                    @Nullable org.springframework.ai.chat.model.ChatResponse aiResponse,
                                    long elapsedMs) {
        // deduplicationKey = conversationId + ":" + md5(userMessage)
        // 保证同一会话不同消息不互斥；同一条消息重试时被总线级 SETNX 拦截
        String deduplicationKey = conversationId + ":"
            + DigestUtils.md5Hex(userMessage);
        ChatMessagePayload payload = new ChatMessagePayload(
            conversationId, userMessage, assistantContent, candidateId);
        MessageEnvelope<ChatMessagePayload> message = MessageEnvelope.deduplicated(
            "chat_message_save", payload, deduplicationKey);

        try {
            messageBus.send(message);
        } catch (MessagingException e) {
            // Bus 失败同步降级：直接走原 saveMessagesAndNotify，保留事务、双消息写入、onNewMessages 全部语义
            log.warn("Message bus unavailable, falling back to synchronous save", e);
            conversationHelper.saveMessagesAndNotify(
                conversationId, userMessage, assistantContent,
                candidateId, aiResponse, elapsedMs);
        }
    }
}
```

**接入示例 1：同步路径 `ChatServiceImpl.processResult()`**

```java
// ChatServiceImpl.java L190 原调用：
// conversationHelper.saveMessagesAndNotify(pctx.conversationId(), userContent, ...);
// 替换为：
chatMessagePublisher.publishMessageSave(
    pctx.conversationId(), userContent, assistantContent,
    candidateId, result.springAiResponse(), elapsedMs);
```

**接入示例 2：流式路径 `MultiTurnModeStrategy.executeStream()`**

```java
// MultiTurnModeStrategy.java L96 原调用（Flux.doFinally 内）：
// conversationHelper.saveMessagesAndNotify(ctx.conversationId(), ...);
// 替换为：
chatMessagePublisher.publishMessageSave(
    ctx.conversationId(), userContent, accumulatedContent,
    candidateId, lastChatResponse, ctx.elapsed());
```

> **`saveMessagesAndNotify` 整体下沉到 consumer**：
> 经核实，"notify" 是 `conversationService.onNewMessages(conversationId, userContent, 2)` —— 
> 更新会话计数 + 触发标题生成，**不是 SSE 推送**。延迟几十毫秒对客户端 UI 可接受
> （UI 通过 SSE 流拿到的 assistant 内容已直接展示，不依赖落库）。
> 因此整个方法（事务 + 双消息写入 + onNewMessages）整体迁到 consumer，无需在 publisher 端拆分。

> **事务边界（DC-01）**：两个 publisher 接入点**当前都不在事务上下文**——
> `processResult` 是 LLM 响应完成后的纯落库收尾；`executeStream.doFinally` 是流式结束后的回调。
> 因此直接使用 `messageBus.send(message)` 是安全的。
>
> 若未来引入"DB 写入 + 消息发送需要原子性"的事务场景（如保存 chat record 后立即通知外部系统），
> 必须改用 `messageBus.sendAfterCommit(message)`（见 §4.2 SPI 定义），底层通过
> `TransactionSynchronizationManager` 在事务提交后发送：
>
> ```java
> // DC-01: 事务上下文中调用必须使用 sendAfterCommit()
> messageBus.sendAfterCommit(message);  // 事务内：提交后发送；非事务：立即发送
> ```

> **Bus 失败同步降级路径明确化**：
> - 触发条件：`messageBus.send` 抛 `MessagingException`（Producer 不可达、Broker 拒绝、序列化失败等）
> - 降级行为：catch 块内同步调 `ChatConversationHelper.saveMessagesAndNotify`，行为与 Phase C 前完全一致
> - 双写风险消除：deduplicationKey = `conversationId + ":" + md5(userMessage)`，由 §5.10 总线级 Redis SETNX 拦截
>   - 场景：bus send 网络超时，Broker 实际已入队但客户端抛 TimeoutException → 同步降级写入 → 
>     consumer 后续拉到同消息 → Redis SETNX 命中 deduplicationKey → 跳过 → DB 不产生重复行
> - 业务层兜底：DB 唯一约束 `(conversation_id, message_index)`（已有，不变）作为最后防线

```java
@Component
public class ChatMessageSaveConsumer implements SmartLifecycle {
    private final MessageBus messageBus;
    private final ChatConversationHelper conversationHelper;
    private Subscription subscription;

    @Override
    public void start() {
        subscription = messageBus.subscribe(
            "chat_message_save",
            "save-group",
            ConsumerConfig.DEFAULT,  // ConsumerMode.PUSH
            ChatMessagePayload.class,
            (MessageEnvelope<ChatMessagePayload> msg) -> {
                var p = msg.payload();
                // 两层幂等：(1) 总线级 Redis SETNX 基于 deduplicationKey 拦截重复
                // (2) 业务级 DB 唯一约束 (conversation_id, message_index) 兜底
                // saveMessagesAndNotify 内部依赖 DB 唯一约束处理极端情况
                // （Redis 故障、幂等 key 未设置、TTL 过期后重试）。
                //
                // 注意：consumer 端调用时 aiResponse 传 null（usage 已走 §7.2 独立链路），
                // durationMs 传 0（流式响应耗时已在用量链路记录）。
                // 这与原同步路径的语义略有差异——原路径从 aiResponse 提取 totalTokens 写入 ASSISTANT 消息，
                // consumer 端无法重建 aiResponse。两种缓解方案：
                //   方案 A：payload 增加 totalTokens 字段（推荐，简单透明）
                //   方案 B：consumer 端通过 usageService 查询最近一条 usage 反查
                // 实现任务选 A：ChatMessagePayload 增加 long totalTokens 字段，
                //   publisher 从 aiResponse.getMetadata().getUsage().getTotalTokens() 提取后传入。
                conversationHelper.saveMessagesAndNotify(
                    p.conversationId(), p.userMessage(),
                    p.assistantContent(), p.candidateId(),
                    /* aiResponse */ null, /* durationMs */ 0);
            });
    }

    @Override
    public void stop() {
        if (subscription != null) subscription.close();
    }

    @Override
    public boolean isRunning() {
        return subscription != null && subscription.isActive();
    }
}
```

> **为什么不使用 Transactional Outbox？**
> 本项目消息总线的消费者与生产者是同一服务（同 JVM），目标是降低响应延迟而非跨服务解耦。
> Transactional Outbox 模式（DB Outbox 表 + 轮询线程）引入的复杂度远超收益。
> send() 失败时降级为同步写入已完全覆盖 RocketMQ 不可用的场景。

### 7.2 Token 用量异步记录

> **接入点（2026-06 更新）**：publisher **直接挂在 `ChatUsageTracker.recordUsage()` 内部**。
> 当前 `ChatUsageTracker` 已经是用量记录的中心化入口（替换散落各处的 `usageService.recordUsage` 调用），
> 内部已 try/catch 吞咽异常，与 §7.2"非关键路径，失败仅记日志"的设计语义天然吻合。
> 改造点比原设计假设的少：只改 `ChatUsageTracker` 一个文件，不动 `AbstractModeStrategy` / 
> `MultiTurnModeStrategy` / `SimpleModeStrategy` 任何调用点。

> **字段重命名说明**：LLM SPI 统一后（commit `a98fa9b`），模型标识符全部使用
> registry candidate ID 命名 `candidateId`。`UsagePayload` / deduplicationKey / 
> `TokenUsage` 实体的语义同步。DB schema 列名 `model_id` 保留（兼容历史数据），
> 写入的值是 candidate ID 字符串。

```java
// 发送端 — ChatUsageTracker.recordUsage 内部改造
@Component
public class ChatUsageTracker {
    private final MessageBus messageBus;
    // 不再直接持有 UsageService 引用——由 consumer 端调用

    public void recordUsage(String conversationId, String candidateId,
                            org.springframework.ai.chat.model.ChatResponse aiResponse,
                            long durationMs) {
        try {
            Usage usage = aiResponse != null ? aiResponse.getMetadata().getUsage() : null;
            long promptTokens = extractOrNeg(usage, Usage::getPromptTokens);
            long completionTokens = extractOrNeg(usage, Usage::getCompletionTokens);
            long totalTokens = extractOrNeg(usage, Usage::getTotalTokens);

            // deduplicationKey = conversationId + ":" + candidateId
            // 同一会话同一模型的多次调用不被去重（每次都是独立 usage 记录），
            // 但 Broker 重试 / consumer 端 ACK 失败时同一记录被去重。
            // 注：若同一会话连续多次调用同一模型需要分别计费，应加入时间戳或调用序号
            // 到 deduplicationKey 中（具体策略由实现任务决定）。
            String deduplicationKey = conversationId + ":" + candidateId + ":"
                + System.currentTimeMillis();
            UsagePayload payload = new UsagePayload(
                conversationId, candidateId,
                promptTokens, completionTokens, totalTokens, durationMs);
            messageBus.send(MessageEnvelope.deduplicated("chat_usage_record", payload, deduplicationKey));
            log.debug("Usage published: candidate={}, prompt={}, completion={}, total={}, duration={}ms",
                candidateId, promptTokens, completionTokens, totalTokens, durationMs);
        } catch (Exception e) {
            // 非关键路径，不降级（与 ChatUsageTracker 现有 try/catch 吞咽语义一致）
            log.error("Failed to publish usage: conversationId={}, candidate={}",
                ConversationIdUtil.mask(conversationId), candidateId, e);
        }
    }

    // 无 AI 响应的降级版本（保留原 ChatUsageTracker 第二个 recordUsage 重载）
    public void recordUsage(String conversationId, String candidateId, long durationMs) {
        // 同样改为 messageBus.send，payload 中 token 三字段填 -1
    }
}
```

```java
// 消费端 — UsageRecordConsumer（PushConsumer）
@Component
public class UsageRecordConsumer implements SmartLifecycle {
    private final MessageBus messageBus;
    private final UsageService usageService;
    private Subscription subscription;

    @Override
    public void start() {
        subscription = messageBus.subscribe(
            "chat_usage_record",
            "usage-group",
            ConsumerConfig.DEFAULT,  // ConsumerMode.PUSH
            UsagePayload.class,
            (MessageEnvelope<UsagePayload> msg) -> {
                var p = msg.payload();
                // 两层幂等：(1) 总线级 Redis SETNX 基于 deduplicationKey 拦截重复
                // (2) UsageService 内部业务层幂等（如 token_usage 表的唯一约束或自然键）
                usageService.recordUsage(
                    p.conversationId(), p.candidateId(),
                    p.promptTokens(), p.completionTokens(),
                    p.totalTokens(), p.durationMs());
            });
    }

    @Override
    public void stop() {
        if (subscription != null) subscription.close();
    }

    @Override
    public boolean isRunning() {
        return subscription != null && subscription.isActive();
    }
}
```

> **`UsagePayload` 字段定义**：
> ```java
> public record UsagePayload(
>     String conversationId,
>     String candidateId,         // 对齐 LLM SPI 统一命名
>     long promptTokens,
>     long completionTokens,
>     long totalTokens,
>     long durationMs
> ) {}
> ```
>
> **DB schema 列名兼容性**：`token_usage` 表 `model_id` 列名不改（避免数据迁移），
> 但写入的值是 candidate ID 字符串。`UsageServiceImpl.recordUsage` 参数名 `candidateId`
> 与 `UsagePayload.candidateId()` 一致；mybatis 映射保留 `model_id` 列名。

### 7.3 RAG 索引任务削峰

> **接入点（已落地）**：Phase B 已完成。
> 发送端 `EtlDispatchServiceImpl.dispatchAsync()`（L136），消费端 `EtlDocumentConsumer`
> （`TOPIC="rag_index_document"`，`GROUP="index-group"`，`ConsumerMode.SIMPLE`）。

> **payload 类型说明**：实际使用 `EtlCandidate`（携带文件元数据：bucket、objectKey、fileName、
> mimeType、fileSize、userId、teamId），不是早期设计假设的 `IndexTask(documentId, teamId)`。
> ETL 处理需要这些元数据下载文件、解析、分块、embed。

```java
// 发送端 — EtlDispatchServiceImpl.dispatchAsync()
// hashKey = documentId（FIFO 有序，由 app.messaging.ordered-topics 配置启用）
// deduplicationKey = documentId（幂等，防止同一文档重复投递）
String dedupKey = String.valueOf(documentId);
EtlCandidate candidate = new EtlCandidate(
    documentId, bucket, objectKey, fileName, mimeType, fileSize, userId, teamId);
messageBus.send(new MessageEnvelope<>(
    null,                              // id (transport-assigned)
    EtlDocumentConsumer.TOPIC,         // topic = "rag_index_document"（裸名，Bus 内部拼前缀）
    null,                              // tag
    candidate,                         // payload
    dedupKey,                          // hashKey (= documentId) → messageGroup
    dedupKey,                          // deduplicationKey (= documentId)
    Map.of(),                          // headers
    System.currentTimeMillis()));
```

```java
// 消费端 — EtlDocumentConsumer（已落地）
@Component
public class EtlDocumentConsumer implements SmartLifecycle {
    public static final String TOPIC = "rag_index_document";
    static final String GROUP = "index-group";

    private static final ConsumerConfig CONSUMER_CONFIG = ConsumerConfig.builder()
        .consumerMode(ConsumerMode.SIMPLE)
        .batchSize(5)
        .invisibleDuration(Duration.ofMinutes(30))
        .retryPolicy(RetryPolicy.SIMPLE_DEFAULT)  // maxRetries=5（应用层控制）
        .build();

    @Override
    public void start() {
        MessageHandler<EtlCandidate> handler = candidate -> {
            // 调 EtlDispatchService.dispatch（不是早期假设的 processDocument）
            List<EtlResult> results = etlDispatchService.dispatch(List.of(candidate));
            if (!results.isEmpty()
                && EtlStatus.COMPLETED.equals(results.getFirst().status())) {
                // 触发领域事件（与 Phase B 前线程池路径行为一致）
                eventPublisher.publishEvent(new EtlCompletedEvent(
                    candidate.documentId(), candidate.userId(), candidate.teamId()));
            }
        };
        subscription = messageBus.subscribe(
            TOPIC, GROUP, CONSUMER_CONFIG, EtlCandidate.class, handler);
    }
    // stop() / isRunning() 略
}
```

> **为什么 RAG 索引使用 SimpleConsumer**：
> RAG 文档索引涉及 LLM 调用（embedding + chunking），处理时间不可预测（秒级到分钟级）。
> PushConsumer 的消费超时机制可能导致消息被并发重复投递。
> SimpleConsumer 无超时概念，通过 `invisibleDuration` 控制失败后的重新可见时间，
> 避免超时导致的重复处理。

> **C-04 有序性限制说明**：Phase 1 不保证 SimpleConsumer 对 FIFO Topic 的严格有序消费。
> SimpleConsumer 的 `receive()` 返回消息不保证按 `messageGroup` 排序——
> 并发 receive 或 ack 延迟可能导致同一 group 内消息乱序处理。
> 如需严格有序，应使用 PushConsumer（Broker 端保证 messageGroup 内串行投递）。
> 对于 RAG 索引场景（同一文档的不同 chunk 可乱序处理），SimpleConsumer 已足够。

> **实际配置位置**：
> - Topic `rag_index_document` 在 `app.messaging.ordered-topics` 中标记为 FIFO（`application.yml:68-69`）
> - 消费组 `index-group` 由 §5.12 运维脚本创建（SimpleConsumer 仍需注册消费组）
> - `hashKey` 与 `deduplicationKey` 都用 `documentId`，前者触发 FIFO 分区，后者触发幂等检查

## 8. 改动文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `infrastructure/messaging/MessageBus.java` | 新增 | SPI 接口 |
| `infrastructure/messaging/MessageEnvelope.java` | 新增 | 消息信封 record |
| `infrastructure/messaging/MessageHandler.java` | 新增 | 消息处理器接口 |
| `infrastructure/messaging/Subscription.java` | 新增 | 订阅句柄 |
| `infrastructure/messaging/ConsumerConfig.java` | 新增 | 消费者配置 |
| `infrastructure/messaging/ConsumerMode.java` | 新增 | 消费模式枚举（PUSH / SIMPLE） |
| `infrastructure/messaging/RetryPolicy.java` | 新增 | 重试策略 |
| `infrastructure/messaging/MessagingProperties.java` | 新增 | 配置属性 |
| `infrastructure/messaging/MessagingAutoConfiguration.java` | 新增 | 无条件装配（Phase 0 起 always-on） |
| `infrastructure/messaging/MessagingHealthIndicator.java` | 新增 | 健康检查（Producer/订阅/熔断器） |
| `infrastructure/messaging/MessagePayloadCodec.java` | 新增 | 序列化接口 |
| `infrastructure/messaging/idempotent/IdempotentConfig.java` | 新增 | 幂等配置 record |
| `infrastructure/messaging/JacksonMessageCodec.java` | 新增 | JSON 序列化 |
| `infrastructure/messaging/exception/MessagingException.java` | 新增 | 基础异常 |
| `infrastructure/messaging/exception/MessagePublishException.java` | 新增 | 发送异常 |
| `infrastructure/messaging/exception/MessageConsumeException.java` | 新增 | 消费异常 |
| `infrastructure/messaging/exception/PermanentConsumeException.java` | 新增 | 永久性消费异常（跳过重试） |
| `infrastructure/messaging/rocketmq/RocketMQMessageBus.java` | 新增 | RocketMQ 5.x 核心实现 |
| `infrastructure/messaging/rocketmq/RocketMQSubscription.java` | 新增 | 订阅管理（Push/Simple 双模式） |
| `application.yml` | 修改 | 新增 `app.messaging.*` 配置段 |
| `pom.xml` | 修改 | 新增 `rocketmq-client-java` 依赖 |

## 9. 迁移步骤

> **Phase 0（2026-06，前置修订）— 移除 `app.messaging.enabled` 开关与 `NoOpMessageBus`**：
> Phase A 原设计的 enabled 开关默认缺失时经 `matchIfMissing=true` 落到 NoOp，导致 app 在 broker 已就绪时
> 仍跑 NoOp、静默丢弃所有消息。Phase 0 将 `MessagingAutoConfiguration` 改为无条件装配、删除
> `NoOpMessageBus`/`NoOpMessagingConfiguration`、移除所有 consumer 的 `@ConditionalOnProperty(enabled)`，
> 消息总线从此 always-on（`endpoints` 默认 `localhost:8081` 指向 Docker broker Proxy）。下方 Phase A 描述
> 保留为历史记录，其中 enabled/NoOp 相关项已被 Phase 0 取代。

### Phase A — SPI 层 + 核心实现

**目标**：`MessageBus` 接口 + RocketMQ 5.x 发送/消费核心路径可用。（Phase 0 后 always-on，无 enabled 开关。）

1. 添加 `rocketmq-client-java` + `caffeine` 依赖到 `pom.xml`。
2. 创建 `infrastructure/messaging/` 包结构，实现 SPI 接口、record 类型和 `ConsumerMode` 枚举。
3. 实现 `RocketMQMessageBus`：
   - 5.x `Producer` 发送（`ClientServiceProvider` + `ClientConfiguration`）
   - `PushConsumer` 订阅（`ConsumerMode.PUSH`）
   - `SimpleConsumer` 订阅（`ConsumerMode.SIMPLE`）
   - 注入 `MeterRegistry`，实现 `messaging.send.count` 和 `messaging.consume.count` 两个核心计数器
4. 实现 `JacksonMessageCodec`（复用项目已有的 `ObjectMapper`）。
5. 实现 `NoOpMessageBus`（`enabled=false` 时的空实现）。
6. 实现 `MessagingAutoConfiguration` 条件装配。
7. 通过 `mqadmin` 创建 FIFO Topic（`rag_index_document`）。
8. 编写集成测试：`RocketMQMessageBusTest`（使用 Testcontainers RocketMQ 5.x）。
9. 运行 §5.12 初始化脚本，创建 Topic 和消费组。

**退出条件**：`enabled=true` 时 PushConsumer 和 SimpleConsumer 两条路径完整跑通；
以下 O-01 Phase A 必须指标可在 Actuator 端点查询：
`messaging.send.count`、`messaging.consume.count`、`messaging.send.latency`、
`messaging.consume.latency`、`messaging.retry.count`、`messaging.dead.count`。

### Phase B — RAG 索引任务迁移（最低风险，使用 SimpleConsumer）

**目标**：将 ETL 调度从线程池迁移到消息总线。

1. `EtlDispatchServiceImpl.dispatchAsync()` 的 `etlIoExecutor.execute()` 替换为 `messageBus.send()`。
2. 创建 `EtlDocumentConsumer` 订阅 `rag_index_document`，使用 `ConsumerMode.SIMPLE`。
3. ETL 已有幂等保证（document 状态 + Redisson 分布式锁），迁移风险最低。

**退出条件**：文档上传 → 消息总线投递 → SimpleConsumer 拉取 → ETL 处理 → 完整链路跑通。

### Phase C — 聊天消息保存 + 用量记录迁移（使用 PushConsumer）

**目标**：将聊天相关异步场景迁移到消息总线。覆盖 **同步路径**（`processResult`）与
**流式路径**（`MultiTurnModeStrategy.executeStream`）两个 publisher 接入点。

> **前置条件**：§5.12 Phase C 启动前检查项全部满足；Phase A/B 已落地。

**迁移顺序（先低风险后高风险）**：

1. **Step 1 — usage 记录迁移**（影响面小，先做）：
   - 改造 `ChatUsageTracker.recordUsage`：将原 `usageService.recordUsage(...)` 调用替换为
     `messageBus.send(MessageEnvelope.deduplicated("chat_usage_record", payload, dedupKey))`
   - 新增 `UsageRecordConsumer` 订阅 `chat_usage_record`（`ConsumerMode.PUSH`，group=`usage-group`）
   - consumer 内部调 `UsageServiceImpl.recordUsage`，签名参数同步改为 `candidateId`
   - 改造 `ChatUsageTracker` 不持有 `UsageService` 直接引用（解耦发送端与 DB 写入端）
   - 验证：流式 + 同步两路径的 `AbstractModeStrategy.recordUsage` 调用链路都通过 bus 走通

2. **Step 2 — chat 消息保存迁移**（影响面大，后做）：
   - 新增 `ChatMessagePublisher.publishMessageSave(...)`（见 §7.1）
   - 新增 `ChatMessagePayload` record（字段 `conversationId`、`userMessage`、`assistantContent`、`candidateId`、`totalTokens`）
   - `ChatServiceImpl.processResult()` L190 原同步调用替换为 `chatMessagePublisher.publishMessageSave(...)`
   - `MultiTurnModeStrategy.executeStream()` L96 的 `doFinally` 内同步调用替换为 `chatMessagePublisher.publishMessageSave(...)`
   - 新增 `ChatMessageSaveConsumer` 订阅 `chat_message_save`（`ConsumerMode.PUSH`，group=`save-group`）
   - consumer 内部调 `ChatConversationHelper.saveMessagesAndNotify`（事务、双消息写入、`onNewMessages` 全保留）
   - `ChatConversationHelper.saveMessagesAndNotify` 失败入 legacy `MessageDeadLetterQueue` 的逻辑保持不变（Phase D 才退役）

3. **Step 3 — legacy Redis DLQ 排空**：
   - 持续监控 `MessageDeadLetterQueue.size()`，确保新增条目趋近 0
   - 残留条目通过 `DeadLetterRetryScheduler` 排空
   - 这一步不阻塞 Phase D 启动，但 legacy DLQ 在 7 天滚动窗口内仍有新条目时**禁止**删除（见 Phase D 前置条件）

**退出条件**（必须全部满足）：
- [ ] **流式路径端到端验证**：`MultiTurnModeStrategy.executeStream` SSE 流关闭 →
      `doFinally` 内 `chatMessagePublisher.publishMessageSave` 触发 → broker 入队 →
      consumer 拉取 → `saveMessagesAndNotify` 落库 → `onNewMessages` 更新会话元数据 →
      客户端 UI 不依赖落库即可展示对话（这条是流式异步化的核心保证）
- [ ] **同步路径端到端验证**：`processResult` 路径 publish → consume → 落库完整跑通
- [ ] **bus 失败降级验证**：模拟 `MessagingException`（如 stop broker）→ catch 块同步调
      `saveMessagesAndNotify` → 数据正确落库 → deduplicationKey 防双写验证通过
- [ ] **usage 链路验证**：流式 + 同步两路径的 `AbstractModeStrategy.recordUsage` →
      `ChatUsageTracker` → bus → `UsageRecordConsumer` → `UsageServiceImpl.recordUsage` 完整跑通
- [ ] **legacy `MessageDeadLetterQueue` 在 7 天滚动窗口内 0 新条目**（Phase D 删除前置条件）
- [ ] 重启后未落库的消息（broker 持久化）能被 consumer 重新拉取并落库（at-least-once 验证）

### Phase D — 文档替代 + 旧 DLQ 清理

**目标**：完成所有迁移，清理遗留代码。

> **前置条件**：§9 Phase C 退出条件全部满足（特别是 legacy `MessageDeadLetterQueue` 7 天 0 新条目）。
> 在 Phase C 未完成前，本阶段 Step 2 / Step 3 不可执行。

1. 将 `DocumentSupersedeService` 的 `@EventListener` + `@Async` 迁移到消息总线。
2. **确认 Phase C 退出条件全部满足后**，移除 `chat/service/MessageDeadLetterQueue.java`。
3. **同步移除** `chat/service/DeadLetterRetryScheduler.java`（其职责由 RocketMQ
   `%DLQ%{group}` + 应用层 `%APP_DLQ%{group}` 接管，见 §5.6）。
4. 移除 `ChatConversationHelper` 对 `MessageDeadLetterQueue` 的依赖
   （`saveMessagesAndNotify` 失败路径改为日志告警 + 依赖 broker 重试）。
5. 实现剩余 Micrometer 指标：`messaging.consumer.lag`、`messaging.consumer.receive.last.success`
   （`messaging.send.latency`、`messaging.consume.latency`、`messaging.retry.count`、
   `messaging.dead.count` 已在 Phase A 实现，见 O-01）。
6. 实现 `TracePropagator`（MDC traceId 传播）—— Phase A 已落地 `TracePropagator` 接口接入三处，
   本步补上真正的 MDC / Spring Micrometer Tracing 实现（替换当前 NO_OP）。

**退出条件**：所有 `@Async` / `@EventListener` 异步模式替换完毕；旧 DLQ 代码已移除；
§3.1 定义的 Micrometer 指标全部可查询；traceId 跨消息传播验证通过。

> **EX-01 / EX-02 Phase 2+ 扩展**（不在当前 Phase A-D 范围内）：
> - **拦截器 SPI**（EX-01）：`MessageInterceptor` 接口支持 beforeSend/afterSend/beforeConsume/afterConsume
>   钩子，用于通用横切逻辑（日志增强、指标埋点、安全审计）。Phase 1 通过硬编码 listener 包装实现，
>   Phase 2 抽取为可插拔 SPI。
> - **动态配置**（EX-02）：支持运行时修改 `invisibleDuration`、`maxRetries`、`concurrency` 等参数，
>   无需重启消费者。通过 Spring Cloud Config / Nacos 配置中心 + `@RefreshScope` 实现。
>   Phase 1 参数在启动时绑定，运行时不可变。

## 10. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| RocketMQ 5.x Proxy 运维复杂度 | 高 | Proxy 嵌入 Broker（5.x 默认模式）；引入 Dashboard；制定部署/监控 SOP |
| 消息重复消费 | 中 | 总线级幂等检查（Redis SETNX）+ 业务层幂等（DB 唯一约束）双层防护 |
| PushConsumer 消费超时 | 中 | 耗时不可预测的任务使用 SimpleConsumer；双层幂等兜底 |
| FIFO Topic messageGroup 热点 | 中 | 选择高基数 hashKey（documentId）；监控 Queue 积压；必要时扩容 Queue |
| NameServer 单点故障 | 中 | NameServer 集群部署（建议至少 2 节点） |
| Broker 磁盘满导致写入失败 | 中 | 监控磁盘使用率，配置 `diskMaxUsedSpaceRatio` |
| 5.x gRPC 客户端成熟度 | 中 | `rocketmq-client-java` 已发布多版，社区活跃；集成测试充分覆盖 |
| 消费者积压 | 低 | 监控 `messaging.consumer.lag`，配置告警 |
| 现有 DLQ 迁移期间消息丢失 | 低 | Phase C 完成前 legacy `MessageDeadLetterQueue` 与新总线 `%DLQ%` / `%APP_DLQ%` 并行运行；Phase D 前置条件强制 7 天滚动窗口 0 新条目才能删除（见 §9 Phase C/D 依赖） |
| DLQ 积压无告警 | 中 | 配置 `messaging.dead.count` > 100 条/小时告警；人工介入 SOP：查看死信 → 修复根因 → replay |
| gRPC 客户端重连失败 | 低 | 5.x gRPC 客户端内置自动重连机制，NameServer/Broker 暂时不可达时客户端自动重试连接。关键路径（chat_message_save）已有 send() 降级兜底；非关键路径（chat_usage_record）连续失败仅记日志，不影响主流程。监控 `messaging.send.count{result=fail}` 告警 |
| 流式 SSE 异步化导致 UI 与落库时机错位 | 低 | client SSE 拿到 assistant 内容后直接渲染（不依赖落库）；落库后通过 `onNewMessages` 更新会话列表元数据（计数+标题），延迟几十毫秒可接受。验证条目见 §9 Phase C 退出条件。回滚方案：publisher 内 `messageBus.send` 失败时同步降级调 `saveMessagesAndNotify`，行为与 Phase C 前完全一致 |
| Phase C/D 强依赖未识别导致过早删除 legacy DLQ | 中 | §9 Phase D 前置条件强制 "Phase C 退出条件全部满足"；Phase D Step 2/3 删除前必须 grep 确认 0 引用 + 监控 7 天 0 新条目。CI 加 grep lint 防止 `MessageDeadLetterQueue` 被误回引 |

## 11. 参考资料

- 项目内部：`MessageDeadLetterQueue.java` — 现有 DLQ 模式
- 项目内部：`DeadLetterRetryScheduler.java` — 现有重试调度
- 项目内部：`FallbackAutoConfiguration.java` — 条件装配模式参考
- Apache RocketMQ 5.x 官方文档：https://rocketmq.apache.org/docs/
- RocketMQ 5.x 消费者类型：https://rocketmq.apache.org/docs/featureBehavior/06consumertype/
- RocketMQ 5.x 消费者重试策略：https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/
- RocketMQ 5.x gRPC 客户端（Java）：https://github.com/apache/rocketmq-clients/tree/master/java
- RocketMQ `rocketmq-client-java` Maven：https://central.sonatype.com/artifact/org.apache.rocketmq/rocketmq-client-java
- RocketMQ 5.x FIFO 消息：https://rocketmq.apache.org/docs/featureBehavior/03fifomessage/
