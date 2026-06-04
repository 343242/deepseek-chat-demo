# 消息总线抽象层设计

> 目标：设计一个基于 SPI 的消息总线抽象层，使用 Apache RocketMQ 5.x 作为消息中间件实现，
> 后续可切换到其他实现而无需修改上游业务代码。
>
> 核心需求：可靠持久化（消息不丢）、消费组（多消费者负载均衡）、削峰填谷（消费端背压控制）、
> 有序消息、Tag 过滤、死信自动管理。
>
> 约束：引入 Apache RocketMQ 5.x gRPC 客户端（`rocketmq-client-java`）；遵循项目已有的
> auto-configuration 模式（`@ConditionalOnBean` + `@EnableConfigurationProperties` + `record` properties）。
> 客户端版本基于 RocketMQ 5.5.0 Broker。

## 1. 背景

### 1.1 当前异步能力

| 能力 | 实现 | 局限 |
|------|------|------|
| 进程内异步 | `@EventListener` + `@Async` | 不跨进程，不持久化，重启丢失 |
| 简单队列 | Redisson `RQueue` | 无 ACK，无消费组，无重试 |
| 死信队列 | `MessageDeadLetterQueue`（`RQueue`） | 手工重试调度，无消费组语义 |
| 线程池异步 | `EtlTaskExecutorBridge` | 不持久化，进程崩溃即丢失 |

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
  新消息总线上线后需按迁移计划逐步替换（见 §9 Phase D）。
- Redisson 3.52.0 继续用于分布式锁、限流、缓存，与消息总线职责分离。

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
| `messaging.consume.count` | Counter | topic, group, result | 消费计数 |
| `messaging.consume.latency` | Timer | topic, group | 消费处理延迟 |
| `messaging.retry.count` | Counter | topic, group, attempt | 重试计数 |
| `messaging.dead.count` | Counter | topic, group | 死信计数 |
| `messaging.consumer.lag` | Gauge | topic, group | 消费延迟（积压量） |

追踪传播：生产端从当前 MDC/Span 提取 traceId 写入 `Message.headers`，
消费端在调用 listener 前自动恢复到 MDC。通过 `TracePropagator` 封装注入和提取逻辑，
与 Spring Micrometer Tracing 集成。

## 4. 核心抽象

### 4.1 消息模型

```java
/**
 * 消息信封 — 与传输层解耦的通用消息包装。
 */
public record Message<T>(
    @Nullable String id,                // 传输层分配，发送前为 null
    String topic,                       // 目标 Topic
    @Nullable String tag,               // 消息标签（用于 Broker 端过滤，null 表示无 Tag）
    T payload,                          // 业务载荷
    @Nullable String hashKey,           // 有序消息分区键（null 表示无序）
    @Nullable String deduplicationKey,  // 消费端幂等键（生产端设置，消费端从 msg.getKeys() 恢复，跨重试稳定）
    Map<String, String> headers,        // 扩展头（traceId、contentType 等）
    long timestamp                      // 创建时间戳
) {
    public static <T> Message<T> of(String topic, T payload) {
        return new Message<>(null, topic, null, payload, null, null, Map.of(),
            System.currentTimeMillis());
    }

    /** 创建带 Tag 的消息，支持 Broker 端过滤 */
    public static <T> Message<T> of(String topic, String tag, T payload) {
        return new Message<>(null, topic, tag, payload, null, null, Map.of(),
            System.currentTimeMillis());
    }

    /**
     * 创建有序消息。
     * 同一 hashKey 的消息路由到同一分区（5.x 通过 messageGroup 实现），保证消费顺序。
     * 不同 hashKey 之间无序。
     */
    public static <T> Message<T> ordered(String topic, T payload, String hashKey) {
        return new Message<>(null, topic, null, payload, hashKey, null, Map.of(),
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
    public static <T> Message<T> deduplicated(String topic, T payload, String deduplicationKey) {
        return new Message<>(null, topic, null, payload, null, deduplicationKey, Map.of(),
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
    String send(Message<?> message);

    /** 异步发送消息 */
    CompletableFuture<String> sendAsync(Message<?> message);

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
                               MessageListener<T> listener);

    // ==================== 生命周期 ====================

    /** 关闭总线：停止所有消费者、释放连接 */
    void shutdown();

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
 * 死信操作 — 运维接口，支持死信查看和重放。
 */
public interface DeadLetterOperations {
    /** 扫描指定 topic 的死信消息 */
    List<Message<?>> scanDeadLetters(String topic, int count);

    /** 将指定死信消息重新投递到主 topic */
    void replayDeadLetter(String topic, String messageId);

    /** 获取指定 topic 的死信数量 */
    int deadLetterCount(String topic);
}
```

### 4.3 消息监听器

```java
/**
 * 消息监听器 — 业务代码实现此接口处理消息。
 * <p>
 * 抛出异常表示消费失败，触发 RocketMQ 重试；正常返回表示消费成功。
 * 实现必须是幂等的（消息可能被重复投递）。
 */
@FunctionalInterface
public interface MessageListener<T> {
    void onMessage(Message<T> message);
}
```

### 4.4 订阅生命周期

```java
/**
 * 订阅句柄 — 管理单个消费组的生命周期。
 */
public interface Subscription extends AutoCloseable {
    String topic();
    String group();
    boolean isActive();
    void pause();
    void resume();
    @Override
    void close();  // 停止消费、释放资源
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
    Duration consumeTimeout,       // PUSH: 单条消息消费超时，默认 15min
    Duration invisibleDuration,    // SIMPLE: 消费失败后消息不可见时长，默认 10min
    String tagExpression,          // Tag 过滤表达式，默认 "*"（接收所有 Tag）
    RetryPolicy retryPolicy        // 重试策略
) {
    public static final ConsumerConfig DEFAULT = new ConsumerConfig(
        ConsumerMode.PUSH, 20, 32,
        Duration.ofMinutes(15), Duration.ofMinutes(10),
        "*", RetryPolicy.DEFAULT);

    public static Builder builder() { return new Builder(); }
    // builder 省略
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

## 5. RocketMQ 5.x 实现

### 5.1 核心映射

| SPI 概念 | RocketMQ 5.x 概念 | 说明 |
|----------|------------------|------|
| `MessageBus` | `Producer` + `PushConsumer` / `SimpleConsumer` 管理 | 统一入口 |
| `Message.topic` | Topic | Topic 名称 |
| `Message.tag` | Tag | Broker 端过滤标签 |
| `Message.hashKey` | `messageGroup`（FIFO Topic） | 分区路由（替代 4.x 的 `MessageQueueSelector`） |
| `Message.deduplicationKey` | `keys` 字段 | Broker 端去重查询 |
| `Message.headers` | `properties` | 用户自定义属性 |
| `MessageListener` | `MessageListener`（返回 `ConsumeResult`） | 消息处理回调 |
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
├── Message.java                       (消息信封)
├── MessageListener.java               (监听器接口)
├── Subscription.java                  (订阅句柄)
├── ConsumerConfig.java                (消费者配置)
├── ConsumerMode.java                  (消费模式枚举)
├── RetryPolicy.java                   (重试策略)
├── MessagingProperties.java           (@ConfigurationProperties)
├── MessagingAutoConfiguration.java    (条件装配)
├── NoOpMessageBus.java                (disabled 时空实现)
├── MessagePayloadCodec.java           (序列化抽象)
├── JacksonMessageCodec.java           (JSON 序列化实现)
├── idempotent/
│   └── IdempotentConfig.java          (幂等配置 record)
├── exception/
│   ├── MessagingException.java
│   ├── MessagePublishException.java
│   └── MessageConsumeException.java
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
```

> **实现复杂度**：核心实现仅需 `RocketMQMessageBus` + `RocketMQSubscription` 两个类。
> 发送端使用 5.x `Producer`，消费端根据 `ConsumerMode` 创建 `PushConsumer` 或 `SimpleConsumer`。
> 重试调度和死信路由由 Broker 原生处理，无需自建调度器。

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

public class RocketMQMessageBus implements MessageBus {
    private final Producer producer;
    private final MessagingProperties properties;
    private final MessagePayloadCodec codec;
    private final ClientServiceProvider provider;
    private final ExecutorService sendExecutor;
    // CopyOnWriteArrayList：读多写少（shutdown 迭代遍历，subscribe 少量写入），无需显式同步
    private final CopyOnWriteArrayList<RocketMQSubscription> activeSubscriptions
        = new CopyOnWriteArrayList<>();
    private volatile boolean shutdown;
    @Nullable private final MeterRegistry meterRegistry;

    public RocketMQMessageBus(MessagingProperties properties,
                               MessagePayloadCodec codec,
                               @Nullable MeterRegistry meterRegistry) {
        this.properties = properties;
        this.codec = codec;
        this.meterRegistry = meterRegistry;
        this.provider = ClientServiceProvider.loadService();
        this.sendExecutor = Executors.newFixedThreadPool(4,
            r -> new Thread(r, "mq-send-async"));

        ClientConfiguration clientConfig = ClientConfiguration.newBuilder()
            .setEndpoints(properties.rocketmq().endpoints())
            .setRequestTimeout(properties.rocketmq().requestTimeout())
            .build();

        try {
            this.producer = provider.newProducerBuilder()
                .setClientConfiguration(clientConfig)
                .build();
        } catch (ClientException e) {
            throw new MessagingException("Failed to create RocketMQ Producer", e);
        }
    }

    @Override
    public String send(Message<?> message) {
        try {
            org.apache.rocketmq.client.apis.message.Message rmqMsg =
                buildRocketMQMessage(message);
            SendReceipt receipt = producer.send(rmqMsg);
            return receipt.getMessageId().toString();
        } catch (Exception e) {
            throw new MessagePublishException(
                "Failed to send message to topic: " + message.topic(), e);
        }
    }

    @Override
    public CompletableFuture<String> sendAsync(Message<?> message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            org.apache.rocketmq.client.apis.message.Message rmqMsg =
                buildRocketMQMessage(message);
            // 5.x Producer 不提供 sendAsync，在独立线程池中执行同步发送
            sendExecutor.submit(() -> {
                try {
                    SendReceipt receipt = producer.send(rmqMsg);
                    future.complete(receipt.getMessageId().toString());
                } catch (Exception e) {
                    future.completeExceptionally(
                        new MessagePublishException(
                            "Async send failed: " + message.topic(), e));
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(
                new MessagePublishException(
                    "Failed to initiate async send: " + message.topic(), e));
        }
        return future;
    }

    private org.apache.rocketmq.client.apis.message.Message buildRocketMQMessage(
            Message<?> message) {
        var builder = org.apache.rocketmq.client.apis.message.MessageBuilder
            .newBuilder()
            .setTopic(properties.topicPrefix() + message.topic())
            .setBody(codec.encode(message.payload()));

        if (message.tag() != null) {
            builder.setTag(message.tag());
        }
        if (message.deduplicationKey() != null) {
            builder.setKeys(message.deduplicationKey());
        }
        // messageGroup 设置见 §5.7 有序消息配置（仅 FIFO Topic 设置，避免非 FIFO Topic 设置无效属性）
        message.headers().forEach(builder::addProperty);

        return builder.build();
    }
}
```

#### 5.3.1 发送熔断

> 当 RocketMQ Broker 不可用或网络异常时，发送操作会持续超时等待，占用线程池资源。
> 引入轻量级熔断器避免级联故障。无外部依赖，使用原子变量 + volatile 实现半开/开/关三态。

```java
// RocketMQMessageBus — 发送熔断器
public class RocketMQMessageBus implements MessageBus {

    // ==================== 熔断器 ====================

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastFailureTime = 0;

    /**
     * 发送前检查熔断状态。
     * 连续 N 次失败且在冷却期内 → fast-fail，跳过发送直接走 fallback。
     */
    private void checkCircuitBreaker() {
        int failures = failureCount.get();
        if (failures >= properties.circuitBreaker().failureThreshold()
            && (System.currentTimeMillis() - lastFailureTime)
                < properties.circuitBreaker().cooldownMillis()) {
            throw new MessagePublishException(
                "Circuit breaker OPEN: " + failures + " consecutive failures, "
                + "cooldown until " + new Date(lastFailureTime
                    + properties.circuitBreaker().cooldownMillis()));
        }
    }

    /**
     * 发送成功时重置熔断器。
     */
    private void recordSuccess() {
        failureCount.set(0);
    }

    /**
     * 发送失败时记录。
     */
    private void recordFailure() {
        failureCount.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();
    }

    @Override
    public String send(Message<?> message) {
        checkCircuitBreaker();
        try {
            org.apache.rocketmq.client.apis.message.Message rmqMsg =
                buildRocketMQMessage(message);
            SendReceipt receipt = producer.send(rmqMsg);
            recordSuccess();
            return receipt.getMessageId().toString();
        } catch (Exception e) {
            recordFailure();
            throw new MessagePublishException(
                "Failed to send message to topic: " + message.topic(), e);
        }
    }
}
```

`MessagingProperties` 中新增熔断配置：

```java
/** 熔断配置 */
public record CircuitBreakerConfig(
    @DefaultValue("5") int failureThreshold,          // 连续失败次数阈值
    @DefaultValue("30000") long cooldownMillis          // 熔断冷却时间（30s）
) {}
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
                                   MessageListener<T> listener) {
    // 注入幂等包装
    MessageListener<T> wrappedListener = wrapWithIdempotent(listener, topic);

    String fullTopic = properties.topicPrefix() + topic;
    ClientConfiguration clientConfig = buildClientConfiguration();
    FilterExpression filterExpression = new FilterExpression(
        config.tagExpression(), FilterExpressionType.TAG);

    SubscriptionExpressions subscriptionExpressions = new SubscriptionExpressions(
        Map.of(fullTopic, filterExpression));

    try {
        if (config.consumerMode() == ConsumerMode.SIMPLE) {
            return createSimpleSubscription(topic, group, config,
                payloadType, wrappedListener, clientConfig, subscriptionExpressions);
        } else {
            return createPushSubscription(topic, group, config,
                payloadType, wrappedListener, clientConfig, subscriptionExpressions);
        }
    } catch (ClientException e) {
        throw new MessagingException(
            "Failed to create subscription: " + topic, e);
    }
}

// ==================== PushConsumer ====================

private <T> Subscription createPushSubscription(
        String topic, String group, ConsumerConfig config,
        Class<T> payloadType, MessageListener<T> listener,
        ClientConfiguration clientConfig,
        SubscriptionExpressions subscriptionExpressions) throws ClientException {

    MessageListener pushListener = messageView -> {
        try {
            T payload = codec.decode(
                toByteArray(messageView.getBody()), payloadType);
            Message<T> message = new Message<>(
                messageView.getMessageId().toString(),
                topic,
                messageView.getTag().orElse(null),
                payload,
                null,  // hashKey: 消费端无需
                messageView.getKeys().stream().findFirst().orElse(null),
                messageView.getProperties(),
                messageView.getBornTimestamp()
            );
            listener.onMessage(message);
            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("Push consume failed: topic={}, msgId={}",
                topic, messageView.getMessageId(), e);
            return ConsumeResult.FAILURE;
        }
    };

    PushConsumer pushConsumer = provider.newPushConsumerBuilder()
        .setClientConfiguration(clientConfig)
        .setConsumerGroup(group)
        .setSubscriptionExpressions(subscriptionExpressions.getSubscriptionExpressions())
        .setMessageListener(pushListener)
        .build();

    var subscription = new RocketMQSubscription(topic, group,
        pushConsumer, null, null);
    activeSubscriptions.add(subscription);
    return subscription;
}

// ==================== SimpleConsumer ====================

private <T> Subscription createSimpleSubscription(
        String topic, String group, ConsumerConfig config,
        Class<T> payloadType, MessageListener<T> listener,
        ClientConfiguration clientConfig,
        SubscriptionExpressions subscriptionExpressions) throws ClientException {

    SimpleConsumer simpleConsumer = provider.newSimpleConsumerBuilder()
        .setClientConfiguration(clientConfig)
        .setConsumerGroup(group)
        .setAwaitDuration(Duration.ofSeconds(30))
        .setSubscriptionExpressions(subscriptionExpressions.getSubscriptionExpressions())
        .build();

    // 后台线程轮询 receive()（接收循环单线程）
    ScheduledExecutorService receiveExecutor = Executors.newSingleThreadScheduledExecutor(
        r -> new Thread(r, "simple-consumer-" + topic));
    AtomicBoolean running = new AtomicBoolean(true);
    int maxRetries = config.retryPolicy().maxRetries();

    // 重试计数器：msgId → 已重试次数
    // 使用 Caffeine 替代 ConcurrentHashMap：自动过期防止 OOM，覆盖重启场景。
    // 注意：如果 5.x MessageView 未来提供 getDeliveryAttempt()，应优先使用该 API
    // 替代本地计数器，避免应用重启后计数丢失的问题。
    Cache<String, AtomicInteger> retryCounter = Caffeine.newBuilder()
        .expireAfterWrite(config.invisibleDuration().multipliedBy(maxRetries * 2))
        .build();

    // 处理线程池：批次内消息并行处理，接收循环保持单线程
    int processingConcurrency = Math.max(1, config.concurrency());
    ExecutorService processingPool = Executors.newFixedThreadPool(
        processingConcurrency,
        r -> new Thread(r, "simple-process-" + topic + "-" + r.hashCode()));

    receiveExecutor.submit(() -> {
        while (running.get()) {
            try {
                List<MessageView> messages = simpleConsumer.receive(
                    config.batchSize(), config.invisibleDuration());
                if (messages.isEmpty()) {
                    continue;
                }
                // 批次内每条消息并行处理，各自独立 ack/nack
                CompletableFuture<?>[] futures = messages.stream()
                    .map(messageView -> CompletableFuture.runAsync(() -> {
                        String msgId = messageView.getMessageId().toString();
                        try {
                            T payload = codec.decode(
                                toByteArray(messageView.getBody()), payloadType);
                            Message<T> message = new Message<>(
                                msgId,
                                topic,
                                messageView.getTag().orElse(null),
                                payload,
                                null,
                                messageView.getKeys().stream().findFirst().orElse(null),
                                messageView.getProperties(),
                                messageView.getBornTimestamp()
                            );
                            listener.onMessage(message);
                            simpleConsumer.ack(messageView);
                            retryCounter.invalidate(msgId);  // 成功后清除计数
                        } catch (Exception e) {
                            int attempts = retryCounter.get(msgId, k -> new AtomicInteger(0))
                                .incrementAndGet();
                            if (attempts >= maxRetries) {
                                log.error("Simple consume exhausted retries ({}): topic={}, msgId={}",
                                    attempts, topic, msgId, e);
                                // 超过重试上限：转发到死信 Topic（SimpleConsumer 无 Broker 自动 DLQ 路由）
                                sendToDeadLetter(messageView, topic, group);
                                simpleConsumer.ack(messageView);
                                retryCounter.invalidate(msgId);
                            } else {
                                log.warn("Simple consume failed ({}/{}): topic={}, msgId={}",
                                    attempts, maxRetries, topic, msgId, e);
                                // 不 ack → 消息在 invisibleDuration 后重新可见
                            }
                        }
                    }, processingPool))
                    .toArray(CompletableFuture[]::new);
                // 等待当前批次全部处理完成后再拉取下一批
                CompletableFuture.allOf(futures).join();
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Simple receive error: topic={}", topic, e);
                }
            }
        }
        // 退出循环后关闭处理线程池
        processingPool.shutdown();
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Simple receive error: topic={}", topic, e);
                }
            }
        }
    });

    var subscription = new RocketMQSubscription(topic, group,
        null, simpleConsumer, receiveExecutor);
    subscription.setRunningFlag(running);
    activeSubscriptions.add(subscription);
    return subscription;
}

private ClientConfiguration buildClientConfiguration() {
    return ClientConfiguration.newBuilder()
        .setEndpoints(properties.rocketmq().endpoints())
        .setRequestTimeout(properties.rocketmq().requestTimeout())
        .build();
}

/**
 * 将消息转发到死信 Topic。
 * <p>
 * 注意：SimpleConsumer 的 DLQ 是应用层实现的。Broker 仅对 PushConsumer 自动路由到
 * %DLQ%{consumerGroup}（基于 maxDeliveryAttempts）。SimpleConsumer 无消费组级别的
 * maxDeliveryAttempts 配置，因此需要应用层在重试耗尽后手动转发。
 */
private void sendToDeadLetter(MessageView messageView, String topic, String group) {
    try {
        String dlqTopic = "%DLQ%" + group;
        org.apache.rocketmq.client.apis.message.Message dlqMsg =
            org.apache.rocketmq.client.apis.message.MessageBuilder.newBuilder()
                .setTopic(dlqTopic)
                .setBody(messageView.getBody())
                .setKeys(messageView.getMessageId().toString())
                .addProperty("originalTopic", topic)
                .addProperty("originalGroup", group)
                .addProperty("deadAt", Instant.now().toString())
                .build();
        producer.send(dlqMsg);
        log.warn("Message forwarded to DLQ: dlqTopic={}, originalTopic={}, msgId={}",
            dlqTopic, topic, messageView.getMessageId());
    } catch (Exception e) {
        log.error("Failed to forward message to DLQ: topic={}, msgId={}",
            topic, messageView.getMessageId(), e);
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
@Override
public DeadLetterOperations deadLetterOperations() {
    return new DeadLetterOperations() {
        @Override
        public List<Message<?>> scanDeadLetters(String topic, int count) {
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
```

> **DLQ 满后行为**：RocketMQ DLQ 是普通 Topic，受 Broker `fileReservedTime`（默认 72h）控制，
> 过期后自动清理。DLQ 积压通过 `messaging.dead.count` + `messaging.consumer.lag` 指标监控。

### 5.7 有序消息

5.x 通过 FIFO Topic 实现有序消息，无需 4.x 的 `MessageQueueSelector` + `MessageListenerOrderly`。

**创建 FIFO Topic**（运维操作，非代码）：

```bash
mqadmin updateTopic -c DefaultCluster -t SMART_RAG_rag.index.document \
    -a +messageType=FIFO -n localhost:9876
```

**发送端 — 设置 messageGroup**：

```java
// 在 buildRocketMQMessage() 中自动设置
// Message.ordered(topic, payload, hashKey) → builder.setMessageGroup(hashKey)
if (message.hashKey() != null) {
    builder.setMessageGroup(message.hashKey());
}
```

**消费端 — 无需特殊配置**：

5.x PushConsumer 对 FIFO Topic 自动保证同一 `messageGroup` 的消息按序消费，
无需选择 `MessageListenerOrderly`（5.x 已取消此区分）。

**有序消费约束**：
- 发送端通过 `Message.ordered(topic, payload, hashKey)` 指定分区键。
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
      - rag.index.document
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

### 5.8 Tag 过滤

```java
// 发送端 — 指定 Tag
messageBus.send(Message.of("chat.message", "save", payload));

// 消费端 — FilterExpression 按 Tag 过滤（构造时传入 ConsumerConfig）
messageBus.subscribe("chat.message", "save-group",
    ConsumerConfig.builder()
        .tagExpression("save")
        .build(),
    ChatMessagePayload.class,
    listener);

// 多 Tag 过滤
messageBus.subscribe("chat.message", "all-group",
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
    Duration timeout = properties.shutdownTimeout();
    // 关闭顺序至关重要：(1) 停止接收新订阅 → (2) drain 在途发送 → (3) 停止消费者 → (4) 关闭生产者
    // 原因：如果先关 Producer，send() 会在消费端 close() 期间失败（如 fallback 写入场景）
    shutdown = true;  // 1. 停止接受新订阅
    // 2. 关闭异步发送线程池，等待在途发送完成
    sendExecutor.shutdown();
    try {
        if (!sendExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("Send executor did not terminate in time");
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    // 3. 关闭所有 Subscription（停止消费者）
    for (RocketMQSubscription sub : activeSubscriptions) {
        sub.close();
    }
    // 4. 关闭 Producer（5.x Producer 实现 AutoCloseable）
    try {
        producer.close();
    } catch (IOException e) {
        log.warn("Error closing producer", e);
    }
}
```

**关闭保证**：
- **PushConsumer 关闭**：`PushConsumer.close()` 停止接收新消息，
  Broker 端等待当前处理中的消息超时后重新投递。
- **SimpleConsumer 关闭**：停止后台 receive 线程，`SimpleConsumer.close()` 释放资源。
  未 ack 的消息在 `invisibleDuration` 后由 Broker 重新投递。
- **Producer 关闭**：`Producer.close()` 等待在途消息完成发送（5.x 实现 `AutoCloseable`）。
- **超时控制**：`MessagingProperties.shutdownTimeout`（默认 30s）。
- **未确认消息**：关闭后未 ACK 的消息由 Broker 自动重新投递（两种模式均适用）。

### 5.10 幂等消费

> at-least-once 语义下消息可能重复投递（消费者崩溃后 Broker 重新投递、
> 网络抖动导致重复 ACK、消费超时后 Broker 重新投递）。
> 消息总线提供内建幂等检查作为通用安全网，业务层幂等作为精确保证。

**设计决策：内建集成而非 AOP**

> `@IdempotentConsume` AOP 方案在本项目中不可行，原因：
> 1. `MessageListener` 以 lambda 形式传入 `subscribe()`，Spring AOP 无法拦截 lambda。
> 2. 5.x 重试投递时消息从 Broker 侧状态机重新投递，
>    `messageId` 在 5.x 中保持不变（改进），但以业务 `deduplicationKey` 为幂等 key 更可靠。
>
> 改为在 `RocketMQMessageBus` 的 listener 包装层内集成幂等检查，
> 使用 `deduplicationKey` 作为幂等 key。

**幂等 Key 选择**：

| 场景 | 幂等 Key 来源 | 说明 |
|------|------------|------|
| `Message.deduplicationKey != null` | 生产端显式指定 | 跨重试稳定，推荐 |
| `Message.deduplicationKey == null` | 无稳定 key | 跳过总线级幂等，完全依赖业务层 |

**实现方式**：

```java
// RocketMQMessageBus — 幂等检查包装
public class RocketMQMessageBus implements MessageBus {
    private final StringRedisTemplate redis;
    private final MessagingProperties properties;

    /**
     * Lua 脚本：SETNX + EXPIRE 原子操作
     * KEYS[1] = "messaging:idempotent:{topic}:{deduplicationKey}"
     * ARGV[1] = ttl in seconds
     * 返回 1 = 首次消费（放行），0 = 重复消费（跳过）
     */
    private static final String LUA_SETNX_EXPIRE =
        "if redis.call('setnx', KEYS[1], '1') == 1 then " +
        "  redis.call('expire', KEYS[1], tonumber(ARGV[1])) " +
        "  return 1 " +
        "else " +
        "  return 0 " +
        "end";

    /**
     * 包装 listener，注入幂等检查。
     * Redis 不可用时静默放行（降级到业务层幂等），不抛异常。
     */
    <T> MessageListener<T> wrapWithIdempotent(
            MessageListener<T> listener, String topic) {
        if (!properties.idempotent().enabled()) {
            return listener;
        }
        return msg -> {
            String idempotentKey = msg.deduplicationKey();
            if (idempotentKey == null || idempotentKey.isEmpty()) {
                listener.onMessage(msg);  // 无幂等 key，直接放行
                return;
            }
            String redisKey = "messaging:idempotent:" + topic + ":" + idempotentKey;
            try {
                Long result = redis.execute(
                    new DefaultRedisScript<>(LUA_SETNX_EXPIRE, Long.class),
                    List.of(redisKey),
                    String.valueOf(properties.idempotent().ttlSeconds()));
                if (result != null && result == 1) {
                    listener.onMessage(msg);  // 首次消费
                } else {
                    log.info("Duplicate message skipped: topic={}, key={}",
                        topic, idempotentKey);
                }
            } catch (Exception e) {
                // Redis 不可用时降级到业务层幂等，不阻塞消费
                log.warn("Idempotent check failed (Redis unavailable), " +
                         "delegating to business-layer: topic={}", topic, e);
                listener.onMessage(msg);
            }
        };
    }
}
```

**subscribe() 集成点**：

幂等包装在 `subscribe()` 中、创建 5.x 消费者之前注入。
对 PushConsumer 和 SimpleConsumer 均适用——包装后的 `MessageListener<T>`
在内部消费循环中被调用。

> **两层幂等的关系**：消息总线内建幂等（Redis SETNX）拦截大部分重复，
> 避免无效 DB 写入。业务层幂等（DB 唯一约束 / 自然键）兜底极端情况
>（Redis 故障、幂等 key 未设置、TTL 过期后重试）。两者互补，缺一不可。

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

**Topic 创建命令**：

```bash
# 聊天消息保存（标准 Topic）
mqadmin updateTopic -c DefaultCluster -t SMART_RAG_chat.message.save \
    -n localhost:9876

# Token 用量记录（标准 Topic）
mqadmin updateTopic -c DefaultCluster -t SMART_RAG_chat.usage.record \
    -n localhost:9876

# RAG 索引文档（FIFO Topic，有序消息）
mqadmin updateTopic -c DefaultCluster -t SMART_RAG_rag.index.document \
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
| `SMART_RAG_chat.message.save` | 标准 | 4（默认） | 吞吐适中，默认足够 |
| `SMART_RAG_chat.usage.record` | 标准 | 4（默认） | 低频写入，默认足够 |
| `SMART_RAG_rag.index.document` | FIFO | 16-32 | messageGroup 基数为 documentId，避免 Queue 热点 |

> **注意**：FIFO Topic 的 Queue 数影响有序消费的并行度。Queue 过少会导致 messageGroup 热点；
> Queue 过多会增加 Broker 内存开销。建议初始 16，根据 `mqadmin topicStats` 监控调整。

**初始化脚本模板**（`scripts/init-rocketmq-topics.sh`）：

```bash
#!/bin/bash
set -euo pipefail
NAMESRV=${ROCKETMQ_NAMESRV:-localhost:9876}
CLUSTER=${CLUSTER_NAME:-DefaultCluster}

echo "=== Creating topics ==="
mqadmin updateTopic -c "$CLUSTER" -t SMART_RAG_chat.message.save -n "$NAMESRV"
mqadmin updateTopic -c "$CLUSTER" -t SMART_RAG_chat.usage.record -n "$NAMESRV"
mqadmin updateTopic -c "$CLUSTER" -t SMART_RAG_rag.index.document \
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
    @DefaultValue("false") boolean enabled,
    @DefaultValue("SMART_RAG_") String topicPrefix,
    @DefaultValue("30s") Duration shutdownTimeout,
    Set<String> orderedTopics,
    @DefaultValue IdempotentConfig idempotent,
    @DefaultValue CircuitBreakerConfig circuitBreaker,
    @DefaultValue RocketMQConfig rocketmq
) {
    /** 幂等检查配置 */
    public record IdempotentConfig(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("90000") long ttlSeconds     // 25h
    ) {}

    /** RocketMQ 5.x 客户端配置 */
    public record RocketMQConfig(
        String endpoints,                           // 必填，无默认值
        @DefaultValue("smart-rag-producer") String producerGroup,
        @DefaultValue("3s") Duration requestTimeout,
        @DefaultValue("16") int maxDeliveryAttempts,
        @DefaultValue("4194304") int maxMessageSize, // 4MB
        // ACL 配置：可选，内网部署可不启用，生产环境推荐启用
        @Nullable String accessKey,
        @Nullable String secretKey
    ) {}
}
```

> **Spring Boot record binding 注意事项**：Spring Boot 3.x 对 record 使用构造器绑定，
> 不走 setter。`@DefaultValue` 注解为未配置的字段提供默认值。无 `@DefaultValue` 且
> 在 YAML 中未配置的字段将为 null/0/false（原始类型）。`endpoints` 故意不设默认值，
> 强制用户显式配置。嵌套 record 的 `@DefaultValue`（无参数）表示使用该 record 的
> 全默认构造。

**`application.yml` 配置示例**：

```yaml
app:
  messaging:
    enabled: true
    topic-prefix: "SMART_RAG_"
    shutdown-timeout: 30s
    ordered-topics:
      - rag.index.document
    idempotent:
      enabled: true
      ttl-seconds: 90000   # 25h
    rocketmq:
      endpoints: ${ROCKETMQ_ENDPOINTS:localhost:9876}
      producer-group: smart-rag-producer
      request-timeout: 3s
      max-delivery-attempts: 16
      # ACL 配置（可选）：内网部署可不启用，生产环境推荐启用
      # access-key: ${ROCKETMQ_ACCESS_KEY:}
      # secret-key: ${ROCKETMQ_SECRET_KEY:}
```

### 6.2 Auto-Configuration

```java
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class MessagingAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    MessageBus rocketMQMessageBus(MessagingProperties properties,
                                   MessagePayloadCodec codec,
                                   @Autowired(required = false) StringRedisTemplate redis,
                                   @Autowired(required = false) MeterRegistry meterRegistry) {
        RocketMQMessageBus bus = new RocketMQMessageBus(properties, codec, meterRegistry);
        if (redis != null) {
            bus.setRedisTemplate(redis);
        }
        return bus;
    }
}

/**
 * 消息总线未启用时的空实现。
 * 业务代码注入 MessageBus 后无需判空，所有操作为 no-op。
 * <p>
 * 行为约定：
 * - send() / sendAsync()：记录 DEBUG 日志，返回空字符串 / 立即完成的 future
 * - subscribe()：记录 WARN 日志，返回 NoOpSubscription（isActive()=false）
 * - shutdown()：无操作
 */
@Configuration
@ConditionalOnProperty(name = "app.messaging.enabled",
    havingValue = "false", matchIfMissing = true)
public class NoOpMessagingConfiguration {
    @Bean
    MessageBus noOpMessageBus() {
        return new NoOpMessageBus();
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

## 7. 业务集成方式

### 7.1 聊天消息异步保存

```java
// 发送端 — ChatServiceImpl.processResult()
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
                                    String assistantContent, String modelId,
                                    @Nullable ChatResponse aiResponse, long elapsedMs) {
        try {
            // deduplicationKey = conversationId + userMessage 摘要，保证同一会话不同消息不互斥
            String deduplicationKey = conversationId + ":"
                + DigestUtils.md5Hex(userMessage);
            messageBus.send(Message.deduplicated("chat.message.save",
                new ChatMessagePayload(conversationId, userMessage,
                                       assistantContent, modelId),
                deduplicationKey));
        } catch (MessagingException e) {
            log.warn("Message bus unavailable, falling back to synchronous save", e);
            conversationHelper.saveMessagesAndNotify(
                conversationId, userMessage, assistantContent,
                modelId, aiResponse, elapsedMs);
        }
    }
}

// 消费端 — 独立消费者（PushConsumer，处理时间可预测）
@Component
public class ChatMessageSaveConsumer implements SmartLifecycle {
    private final MessageBus messageBus;
    private final ChatConversationHelper conversationHelper;
    private Subscription subscription;

    @Override
    public void start() {
        subscription = messageBus.subscribe(
            "chat.message.save",
            "save-group",
            ConsumerConfig.DEFAULT,  // ConsumerMode.PUSH
            ChatMessagePayload.class,
            (Message<ChatMessagePayload> msg) -> {
                var p = msg.payload();
                // 两层幂等：(1) 总线级 Redis SETNX 基于 deduplicationKey 拦截重复
                // (2) 业务级 DB 唯一约束 (conversation_id, message_index) 兜底
                // conversationHelper.saveMessagesAndNotify 内部依赖 DB 唯一约束
                // 处理极端情况（Redis 故障、幂等 key 未设置、TTL 过期后重试）。
                conversationHelper.saveMessagesAndNotify(
                    p.conversationId(), p.userMessage(),
                    p.assistantContent(), p.modelId(), null, 0);
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

```java
// 发送端 — 非关键路径，失败仅记日志
try {
    messageBus.send(Message.deduplicated("chat.usage.record",
        new UsagePayload(conversationId, modelId, tokens, elapsedMs),
        conversationId + ":" + modelId));  // deduplicationKey
} catch (MessagingException e) {
    log.warn("Failed to publish usage record: {}", e.getMessage());
    // 非关键路径，不降级
}

// 消费端（PushConsumer，批量聚合后写 DB）
messageBus.subscribe("chat.usage.record", "usage-group",
    ConsumerConfig.builder()
        .consumerMode(ConsumerMode.PUSH)
        .build(),
    UsagePayload.class,
    (Message<UsagePayload> msg) -> usageService.recordUsage(msg.payload()));
```

### 7.3 RAG 索引任务削峰

```java
// 发送端 — 文档上传后投递索引任务
// hashKey = documentId（FIFO 有序），deduplicationKey = documentId（幂等）
messageBus.send(new Message<>("rag.index.document", null,
    new IndexTask(documentId, teamId), documentId, documentId,
    Map.of(), System.currentTimeMillis()));

// 消费端 — SimpleConsumer：按 LLM 调用速率消费，无消费超时风险
messageBus.subscribe("rag.index.document", "index-group",
    ConsumerConfig.builder()
        .consumerMode(ConsumerMode.SIMPLE)
        .batchSize(5)
        .invisibleDuration(Duration.ofMinutes(30))
        .retryPolicy(RetryPolicy.SIMPLE_DEFAULT)  // maxRetries=5
        .build(),
    IndexTask.class,
    (Message<IndexTask> msg) -> etlService.processDocument(msg.payload()));
```

> **为什么 RAG 索引使用 SimpleConsumer**：
> RAG 文档索引涉及 LLM 调用（embedding + chunking），处理时间不可预测（秒级到分钟级）。
> PushConsumer 的消费超时机制可能导致消息被并发重复投递。
> SimpleConsumer 无超时概念，通过 `invisibleDuration` 控制失败后的重新可见时间，
> 避免超时导致的重复处理。

## 8. 改动文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `infrastructure/messaging/MessageBus.java` | 新增 | SPI 接口 |
| `infrastructure/messaging/Message.java` | 新增 | 消息信封 record |
| `infrastructure/messaging/MessageListener.java` | 新增 | 监听器接口 |
| `infrastructure/messaging/Subscription.java` | 新增 | 订阅句柄 |
| `infrastructure/messaging/ConsumerConfig.java` | 新增 | 消费者配置 |
| `infrastructure/messaging/ConsumerMode.java` | 新增 | 消费模式枚举（PUSH / SIMPLE） |
| `infrastructure/messaging/RetryPolicy.java` | 新增 | 重试策略 |
| `infrastructure/messaging/MessagingProperties.java` | 新增 | 配置属性 |
| `infrastructure/messaging/MessagingAutoConfiguration.java` | 新增 | 条件装配 |
| `infrastructure/messaging/NoOpMessageBus.java` | 新增 | 未启用时的空实现 |
| `infrastructure/messaging/MessagePayloadCodec.java` | 新增 | 序列化接口 |
| `infrastructure/messaging/idempotent/IdempotentConfig.java` | 新增 | 幂等配置 record |
| `infrastructure/messaging/JacksonMessageCodec.java` | 新增 | JSON 序列化 |
| `infrastructure/messaging/exception/MessagingException.java` | 新增 | 基础异常 |
| `infrastructure/messaging/exception/MessagePublishException.java` | 新增 | 发送异常 |
| `infrastructure/messaging/exception/MessageConsumeException.java` | 新增 | 消费异常 |
| `infrastructure/messaging/rocketmq/RocketMQMessageBus.java` | 新增 | RocketMQ 5.x 核心实现 |
| `infrastructure/messaging/rocketmq/RocketMQSubscription.java` | 新增 | 订阅管理（Push/Simple 双模式） |
| `application.yml` | 修改 | 新增 `app.messaging.*` 配置段 |
| `pom.xml` | 修改 | 新增 `rocketmq-client-java` 依赖 |

## 9. 迁移步骤

### Phase A — SPI 层 + 核心实现

**目标**：`MessageBus` 接口 + RocketMQ 5.x 发送/消费核心路径可用。默认 `enabled=false`，零行为变更。

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
7. 通过 `mqadmin` 创建 FIFO Topic（`rag.index.document`）。
8. 编写集成测试：`RocketMQMessageBusTest`（使用 Testcontainers RocketMQ 5.x）。
9. 运行 §5.12 初始化脚本，创建 Topic 和消费组。

**退出条件**：`enabled=true` 时 PushConsumer 和 SimpleConsumer 两条路径完整跑通；
`messaging.send.count` 和 `messaging.consume.count` 指标可在 Actuator 端点查询。

### Phase B — RAG 索引任务迁移（最低风险，使用 SimpleConsumer）

**目标**：将 ETL 调度从线程池迁移到消息总线。

1. `EtlDispatchServiceImpl.dispatchAsync()` 的 `etlIoExecutor.execute()` 替换为 `messageBus.send()`。
2. 创建 `EtlDocumentConsumer` 订阅 `rag.index.document`，使用 `ConsumerMode.SIMPLE`。
3. ETL 已有幂等保证（document 状态 + Redisson 分布式锁），迁移风险最低。

**退出条件**：文档上传 → 消息总线投递 → SimpleConsumer 拉取 → ETL 处理 → 完整链路跑通。

### Phase C — 聊天消息保存 + 用量记录迁移（使用 PushConsumer）

**目标**：将聊天相关异步场景迁移到消息总线。

1. 接入 `chat.message.save`（消息持久化，`ConsumerMode.PUSH`）。
2. 接入 `chat.usage.record`（用量记录，`ConsumerMode.PUSH`）。
3. 将现有 Redis DLQ 中的残留条目排空后停止消费。

**退出条件**：两个业务场景通过消息总线完成异步处理，重启后消息不丢失。

### Phase D — 文档替代 + 旧 DLQ 清理

**目标**：完成所有迁移，清理遗留代码。

1. 将 `DocumentSupersedeService` 的 `@EventListener` + `@Async` 迁移到消息总线。
2. 确认旧 `MessageDeadLetterQueue` 为空且 7 天无新条目后移除。
3. 移除 `DeadLetterRetryScheduler`。
4. 实现剩余 Micrometer 指标：`messaging.send.latency`、`messaging.consume.latency`、
   `messaging.retry.count`、`messaging.dead.count`、`messaging.consumer.lag`
   （`messaging.send.count` 和 `messaging.consume.count` 已在 Phase A 实现）。
5. 实现 `TracePropagator`（MDC traceId 传播）。

**退出条件**：所有 `@Async` / `@EventListener` 异步模式替换完毕；旧 DLQ 代码已移除；
§3.1 定义的 7 个 Micrometer 指标全部可查询；traceId 跨消息传播验证通过。

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
| 现有 DLQ 迁移期间消息丢失 | 低 | 新旧 DLQ 并行运行，确认旧 DLQ 清空后再移除 |

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
