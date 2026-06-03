# 消息总线抽象层设计

> 目标：设计一个基于 SPI 的消息总线抽象层，使用 Apache RocketMQ 作为消息中间件实现，
> 后续可切换到其他实现而无需修改上游业务代码。
>
> 核心需求：可靠持久化（消息不丢）、消费组（多消费者负载均衡）、削峰填谷（消费端背压控制）、
> 有序消息、Tag 过滤、死信自动管理。
>
> 约束：引入 Apache RocketMQ 作为消息中间件；遵循项目已有的 auto-configuration
> 模式（`@ConditionalOnBean` + `@EnableConfigurationProperties` + `record` properties）。

## 1. 背景

### 1.1 当前异步能力

| 能力 | 实现 | 局限 |
|------|------|------|
| 进程内异步 | `@EventListener` + `@Async` | 不跨进程，不持久化，重启丢失 |
| 简单队列 | Redisson `RQueue` | 无 ACK，无消费组，无重试 |
| 死信队列 | `MessageDeadLetterQueue`（`RQueue`） | 手工重试调度，无消费组语义 |
| 线程池异步 | `EtlTaskExecutorBridge` | 不持久化，进程崩溃即丢失 |

### 1.2 为什么选择 RocketMQ

RocketMQ 是 Apache 基金会顶级项目，专为分布式消息场景设计，与本项目需求高度匹配：

- **原生消费组**：Broker 自动管理消费组，多消费者实例间负载均衡。
- **内置 16 级延迟重试**：10s→30s→1m→2m→...→2h，消费失败后自动重试，无需自建调度器。
- **自动死信路由**：重试耗尽后消息自动进入 `%DLQ%ConsumerGroup` Topic，无需自建 DLQ 管理器。
- **Broker 端 Tag 过滤**：减少网络传输，比应用层过滤更高效。
- **MessageQueueSelector**：基于 hash key 的有序消息路由，原生支持。
- **事务消息**：半消息 + 本地事务回查，为未来的分布式事务场景预留能力。
- **专属 Dashboard + 管控工具**：成熟的运维生态。

**结论**：消息总线的核心需求（可靠投递、消费组、重试、死信）全部是 RocketMQ 原生能力，
实现仅需 3 个核心类（MessageBus + Subscription + Configuration）。

### 1.3 已有基础设施

- 项目使用 `app.*` 前缀的 `@ConfigurationProperties`，多数用 Java `record`。
- Auto-configuration 通过 `@ConditionalOnBean` 条件创建。
- `MessageDeadLetterQueue` + `DeadLetterRetryScheduler` 已有 DLQ 重试模式可参考，
  新消息总线上线后需按迁移计划逐步替换（见 §9 Phase D）。
- Redisson 3.52.0 继续用于分布式锁、限流、缓存，与消息总线职责分离。

## 2. 设计目标

### 2.1 目标

- 提供与具体消息中间件解耦的 `MessageBus` SPI 接口。
- 使用 RocketMQ 原生能力实现消费组、ACK、重试、死信、有序消息、Tag 过滤。
- 支持 Topic 级别的有序消息（同一 hash key 路由到同一 Queue）。
- 支持消费端背压（可配置拉取批次、并发数）。
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
    @Nullable String deduplicationKey,  // 生产端去重键（null 表示无需去重）
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
     * 同一 hashKey 的消息路由到同一 Queue，保证消费顺序。
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
     */
    public static <T> Message<T> of(String topic, T payload, String deduplicationKey) {
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
 * 底层异常（MQClientException、RemotingException 等）应作为 cause 链入。
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
     * @param config      消费者配置（并发度、批次大小等）
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
 * 消费者配置 — 每个订阅的独立配置。
 */
public record ConsumerConfig(
    int concurrency,           // 并发消费线程数，默认 20
    int batchSize,             // 每次拉取消息数，默认 32
    Duration consumeTimeout,   // 单条消息消费超时，默认 15min
    String tagExpression,      // Tag 过滤表达式，默认 "*"（接收所有 Tag）
    RetryPolicy retryPolicy    // 重试策略
) {
    public static final ConsumerConfig DEFAULT = new ConsumerConfig(
        20, 32, Duration.ofMinutes(15), "*", RetryPolicy.DEFAULT);

    public static Builder builder() { return new Builder(); }
    // builder 省略
}
```

### 4.6 重试策略

```java
/**
 * 重试策略 — 基于 RocketMQ 原生 16 级延迟重试。
 * <p>
 * RocketMQ 延迟级别：
 * 1:10s  2:30s  3:1m  4:2m  5:3m  6:4m  7:5m  8:6m
 * 9:7m  10:8m  11:9m  12:10m  13:20m  14:30m  15:1h  16:2h
 * <p>
 * 重试由 Broker 端自动调度，无需应用层实现退避算法。
 * 消费端返回 RECONSUME_LATER 即触发重试。
 * 超过 maxRetries 后消息自动进入 %DLQ% Topic。
 */
public record RetryPolicy(
    int maxRetries             // 最大重试次数，默认 16
) {
    public static final RetryPolicy DEFAULT = new RetryPolicy(16);

    /** 无重试 */
    public static final RetryPolicy NO_RETRY = new RetryPolicy(0);

    public int maxRetries() { return maxRetries; }
}
```

> **设计决策**：相比前版设计的 `RetryPolicy` 接口 + `ExponentialBackoffRetryPolicy` 实现，
> 本版简化为 `record RetryPolicy(int maxRetries)`。原因：
> 1. RocketMQ 内置 16 级延迟重试，无需自定义延迟计算。
> 2. `shouldRetry()` 由 `attempt < maxRetries` 隐式决定，无需接口方法。
> 3. 如未来后端需要自定义重试调度，可扩展为接口 + 多实现。

## 5. RocketMQ 实现

### 5.1 核心映射

| SPI 概念 | RocketMQ 概念 | 说明 |
|----------|--------------|------|
| `MessageBus` | `DefaultMQProducer` + `DefaultMQPushConsumer` 管理 | 统一入口 |
| `Message.topic` | Topic | Topic 名称 |
| `Message.tag` | Tag | Broker 端过滤标签 |
| `Message.hashKey` | `MessageQueueSelector` key | Queue 分区路由 |
| `Message.deduplicationKey` | `keys` 字段 | Broker 端去重查询 |
| `Message.headers` | `properties` | 用户自定义属性 |
| `MessageListener` | `MessageListenerConcurrently` / `MessageListenerOrderly` | 消息处理回调 |
| `Subscription` | `DefaultMQPushConsumer` 实例 | 消费者生命周期 |
| `RetryPolicy.maxRetries` | `maxReconsumeTimes` | 最大重试次数 |
| `DeadLetterOperations` | `%DLQ%ConsumerGroup` Topic | 死信管理 |

### 5.2 包结构

```
infrastructure/messaging/
├── MessageBus.java                    (SPI 接口)
├── Message.java                       (消息信封)
├── MessageListener.java               (监听器接口)
├── Subscription.java                  (订阅句柄)
├── ConsumerConfig.java                (消费者配置)
├── RetryPolicy.java                   (重试策略)
├── MessagingProperties.java           (@ConfigurationProperties)
├── MessagingAutoConfiguration.java    (条件装配)
├── NoOpMessageBus.java                (disabled 时空实现)
├── MessagePayloadCodec.java           (序列化抽象)
├── JacksonMessageCodec.java           (JSON 序列化实现)
├── idempotent/
│   ├── IdempotentConsume.java         (幂等消费注解)
│   └── IdempotentConsumeAspect.java   (AOP 切面：Redis Lua 幂等检查)
├── exception/
│   ├── MessagingException.java
│   ├── MessagePublishException.java
│   └── MessageConsumeException.java
└── rocketmq/
    ├── RocketMQMessageBus.java        (核心实现：RocketMQTemplate + DefaultMQPushConsumer 管理)
    └── RocketMQSubscription.java      (订阅管理：DefaultMQPushConsumer 生命周期)
```

> **实现复杂度**：核心实现仅需 `RocketMQMessageBus` + `RocketMQSubscription`
> 两个类。发送端委托 `RocketMQTemplate`（由 starter 自动装配），消费端使用
> `DefaultMQPushConsumer` 程序式管理以支持 SPI `subscribe()` 动态注册。
> 重试调度和死信路由由 Broker 原生处理。

### 5.3 发送消息

> **实现选择**：发送端使用 `RocketMQTemplate`（由 `rocketmq-spring-boot-starter` 自动装配），
> 而非直接创建 `DefaultMQProducer`。原因：
> 1. `RocketMQTemplate` 封装了 `DefaultMQProducer` 生命周期管理，减少样板代码。
> 2. 内置 `MessageConverter` 支持和发送重试，与 Spring 生态无缝集成。
> 3. 事务消息 `sendMessageInTransaction()` 可直接调用，为 Phase 2 预留能力。

```java
// RocketMQMessageBus
public class RocketMQMessageBus implements MessageBus {
    private final RocketMQTemplate rocketMQTemplate;
    private final MessagingProperties properties;
    private final MessagePayloadCodec codec;

    public RocketMQMessageBus(RocketMQTemplate rocketMQTemplate,
                               MessagingProperties properties,
                               MessagePayloadCodec codec) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
        this.codec = codec;
    }

    @Override
    public String send(Message<?> message) {
        try {
            String destination = properties.topicPrefix() + message.topic();
            org.springframework.messaging.Message<byte[]> springMsg =
                buildSpringMessage(message);
            SendResult result = rocketMQTemplate.syncSend(destination, springMsg);
            return result.getMsgId();
        } catch (Exception e) {
            throw new MessagePublishException(
                "Failed to send message to topic: " + message.topic(), e);
        }
    }

    @Override
    public CompletableFuture<String> sendAsync(Message<?> message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            String destination = properties.topicPrefix() + message.topic();
            org.springframework.messaging.Message<byte[]> springMsg =
                buildSpringMessage(message);
            rocketMQTemplate.asyncSend(destination, springMsg, new SendCallback() {
                @Override
                public void onSuccess(SendResult result) {
                    future.complete(result.getMsgId());
                }
                @Override
                public void onException(Throwable e) {
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

    private org.springframework.messaging.Message<byte[]> buildSpringMessage(
            Message<?> message) {
        MessageBuilder<byte[]> builder = MessageBuilder
            .withPayload(codec.encode(message.payload()))
            .setHeader("KEYS", message.deduplicationKey())
            .setHeader("TAGS", message.tag());
        message.headers().forEach(builder::setHeader);
        return builder.build();
    }
}
```

### 5.4 消费消息

```java
// RocketMQMessageBus.subscribe()
@Override
public <T> Subscription subscribe(String topic, String group,
                                   ConsumerConfig config,
                                   Class<T> payloadType,
                                   MessageListener<T> listener) {
    DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group);
    consumer.setNamesrvAddr(properties.rocketmq().nameServer());
    consumer.setConsumeThreadMin(config.concurrency());
    consumer.setConsumeThreadMax(config.concurrency());
    consumer.setConsumeMessageBatchMaxSize(config.batchSize());
    consumer.setConsumeTimeout((long) config.consumeTimeout().toMinutes());
    consumer.setMaxReconsumeTimes(config.retryPolicy().maxRetries());

    String fullTopic = properties.topicPrefix() + topic;
    try {
        consumer.subscribe(fullTopic, config.tagExpression());
    } catch (MQClientException e) {
        throw new MessagingException("Failed to subscribe: " + fullTopic, e);
    }

    boolean ordered = hasOrderedConfig(topic);
    if (ordered) {
        consumer.registerMessageListener(
            new OrderlyListener<>(codec, payloadType, listener, topic));
    } else {
        consumer.registerMessageListener(
            new ConcurrentlyListener<>(codec, payloadType, listener, topic));
    }

    try {
        consumer.start();
    } catch (MQClientException e) {
        throw new MessagingException("Failed to start consumer: " + group, e);
    }

    var subscription = new RocketMQSubscription(topic, group, consumer);
    activeSubscriptions.add(subscription);
    return subscription;
}

// 并发消费监听器
private record ConcurrentlyListener<T>(
    MessagePayloadCodec codec, Class<T> payloadType,
    MessageListener<T> listener, String topic
) implements MessageListenerConcurrently {

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : msgs) {
            try {
                T payload = codec.decode(msg.getBody(), payloadType);
                Message<T> message = new Message<>(
                    msg.getMsgId(), topic, msg.getTags(),
                    payload, null, null,
                    msg.getProperties(), msg.getBornTimestamp()
                );
                listener.onMessage(message);
            } catch (Exception e) {
                log.error("Consume failed: topic={}, msgId={}",
                    topic, msg.getMsgId(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
```

### 5.5 消息确认与重试

```
消息生命周期：

  Producer.send() → [Broker Topic] → Push Consumer
                                            │
                                    ┌───────┴───────┐
                                    │               │
                                  成功             失败
                                    │               │
                              CONSUME_SUCCESS   RECONSUME_LATER
                                    │               │
                                  完成       Broker 自动重试（16 级延迟）
                                                    │
                                           ┌───────┴───────┐
                                           │               │
                                          成功          重试耗尽
                                           │               │
                                         完成       自动进入 %DLQ%
```

**ACK 失败场景**（at-least-once 语义下的标准风险）：

```
1. 消费者处理消息 M 成功
2. 返回 CONSUME_SUCCESS 前消费者进程崩溃
3. Broker 未收到确认，等待超时后重新投递
4. 新消费者实例再次处理 M（重复处理）

缓解措施：
- 消费者必须幂等（见 §4.3）
- 这是所有 at-least-once 系统的标准行为
- 当前顺序（处理 → 返回成功）是正确的：崩溃仅导致重复消费，不丢消息
```

### 5.6 死信处理

RocketMQ 消费重试超过 `maxReconsumeTimes` 后，消息自动进入 `%DLQ%ConsumerGroup` Topic。

```java
// RocketMQMessageBus
@Override
public DeadLetterOperations deadLetterOperations() {
    return new DeadLetterOperations() {
        @Override
        public List<Message<?>> scanDeadLetters(String topic, int count) {
            // 消费 %DLQ%ConsumerGroup Topic 中的死信消息
            // 使用 DefaultMQPullConsumer 拉取
        }

        @Override
        public void replayDeadLetter(String topic, String messageId) {
            // 从 DLQ 拉取消息，重新发送到主 Topic
        }

        @Override
        public int deadLetterCount(String topic) {
            // 使用 DefaultMQAdminExt 查询 DLQ 积压量
        }
    };
}
```

> **DLQ 满后行为**：RocketMQ DLQ 是普通 Topic，受 Broker `fileReservedTime`（默认 72h）控制，
> 过期后自动清理。DLQ 积压通过 `messaging.dead.count` + `messaging.consumer.lag` 指标监控。
> DLQ 积压通过 `messaging.dead.count` + `messaging.consumer.lag` 指标监控。

### 5.7 有序消息

```java
// 发送端 — 使用 MessageQueueSelector 路由
producer.send(mqMsg, new MessageQueueSelector() {
    @Override
    public MessageQueue select(List<MessageQueue> mqs,
                                org.apache.rocketmq.common.message.Message msg,
                                Object arg) {
        String key = (String) arg;
        int index = Math.abs(key.hashCode()) % mqs.size();
        return mqs.get(index);
    }
}, message.hashKey());

// 消费端 — 使用 MessageListenerOrderly 保证顺序
consumer.registerMessageListener(new OrderlyListener<>(...));
```

**有序消费约束**：
- 发送端通过 `Message.ordered(topic, payload, hashKey)` 指定分区键。
- 消费端使用 `MessageListenerOrderly`，同一 Queue 严格按序消费。
- `MessageListenerOrderly` 内部单线程逐条处理，`concurrency` 控制 Queue 级别并行度（每个 Queue 一个消费线程）。
- Broker 端通过分布式锁保证同一 Queue 同一时刻只有一个消费者实例。
- **并发约束**：有序 Topic 的 `concurrency` 不应超过 Topic 的 Queue 数量（默认 4），
  超出后多余的线程空闲。

### 5.8 Tag 过滤

```java
// 发送端 — 指定 Tag
messageBus.send(Message.of("chat.message", "save", payload));

// 消费端 — 按 Tag 过滤
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

Tag 过滤在 Broker 端执行，减少网络传输，比应用层过滤更高效。

### 5.9 优雅关闭

```java
void shutdown() {
    Duration timeout = properties.shutdownTimeout();
    // 1. 关闭所有 Push Consumer（不再接收新消息，等待当前批次处理完成）
    for (RocketMQSubscription sub : activeSubscriptions) {
        sub.close();
    }
    // 2. 关闭 Producer（等待在途消息完成发送）
    producer.shutdown();
}
```

**关闭保证**：
- **Consumer 关闭**：`DefaultMQPushConsumer.shutdown()` 等待当前处理中的消息完成。
  未确认的消息由 Broker 在超时后重新投递到其他消费者实例。
- **Producer 关闭**：`RocketMQTemplate` 的 `DefaultMQProducer` 由 starter 管理生命周期，
  随 ApplicationContext 关闭自动销毁。
- **超时控制**：`MessagingProperties.shutdownTimeout`（默认 30s）。
- **未确认消息**：关闭后未 ACK 的消息由 Broker 在超时后自动重新投递。

### 5.10 幂等消费

> at-least-once 语义下消息可能重复投递（消费者崩溃后 Broker 重新投递、网络抖动导致重复 ACK）。
> 业务层幂等是第一道防线，`@IdempotentConsume` 切面是通用安全网。

```java
/**
 * 幂等消费注解 — 标注在 MessageListener 实现上。
 * 基于消息 ID + consumer group 在 Redis 中记录消费状态，
 * 同一消息重复投递时自动跳过。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentConsume {
    /** 幂等键 TTL，默认 24h（覆盖最大重试周期 16×2h） */
    long ttlSeconds() default 24 * 3600;
}

// IdempotentConsumeAspect — AOP 切面
@Aspect
@Component
public class IdempotentConsumeAspect {
    private final StringRedisTemplate redis;

    /**
     * Lua 脚本：SETNX + EXPIRE 原子操作
     * KEYS[1] = "messaging:idempotent:{groupId}:{msgId}"
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

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, IdempotentConsume idempotent) {
        Message<?> msg = extractMessage(pjp.getArgs());
        String key = "messaging:idempotent:" + msg.topic() + ":" + msg.id();
        Long result = redis.execute(
            new DefaultRedisScript<>(LUA_SETNX_EXPIRE, Long.class),
            List.of(key), String.valueOf(idempotent.ttlSeconds()));
        if (result != null && result == 1) {
            return pjp.proceed();  // 首次消费，放行
        }
        log.info("Duplicate message skipped: topic={}, id={}", msg.topic(), msg.id());
        return null;  // 重复消费，跳过
    }
}
```

**使用方式**：

```java
// 业务消费者 — 同时依赖业务层幂等（DB 唯一约束）和通用幂等切面
messageBus.subscribe("chat.message.save", "save-group",
    ConsumerConfig.DEFAULT,
    ChatMessagePayload.class,
    (Message<ChatMessagePayload> msg) -> {
        // @IdempotentConsume 在此处由 AOP 切面拦截
        // 业务层幂等：conversationId + messageIndex 唯一约束
        conversationHelper.saveMessagesAndNotify(...);
    });
```

> **两层幂等的关系**：`@IdempotentConsume` 是通用去重安全网（Redis SETNX），
> 业务层幂等（DB 唯一约束 / 自然键）是精确保证。两者互补：
> - 切面拦截大部分重复，避免无效 DB 写入。
> - 业务层兜底极端情况（如 Redis 故障时切面失效）。

## 6. Spring 集成

### 6.1 配置属性

```java
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
    boolean enabled,             // 总开关，默认 false（需显式启用）
    String topicPrefix,          // Topic 前缀，默认 "SMART_RAG_"
    Duration shutdownTimeout,    // 关闭超时，默认 30s
    RocketMQConfig rocketmq      // RocketMQ 专属配置
) {
    public MessagingProperties() {
        this(false, "SMART_RAG_", Duration.ofSeconds(30), new RocketMQConfig());
    }

    public record RocketMQConfig(
        String nameServer,              // NameServer 地址，必填
        String producerGroup,           // 生产者组名，默认 "smart-rag-producer"
        Duration sendTimeout,           // 发送超时，默认 3s
        int retryTimesWhenSendFailed,   // 同步发送失败重试次数，默认 2
        int maxReconsumeTimes,          // 默认最大重试次数，默认 16
        int consumeThreadMin,           // 默认最小消费线程数，默认 20
        int consumeThreadMax,           // 默认最大消费线程数，默认 64
        Duration consumeTimeout,        // 默认消费超时，默认 15min
        int maxMessageSize              // 最大消息大小（字节），默认 4MB
    ) {
        public RocketMQConfig() {
            this("localhost:9876", "smart-rag-producer",
                 Duration.ofSeconds(3), 2, 16, 20, 64,
                 Duration.ofMinutes(15), 4 * 1024 * 1024);
        }
    }
}
```

**`application.yml` 配置示例**：

```yaml
app:
  messaging:
    enabled: true
    topic-prefix: "SMART_RAG_"
    shutdown-timeout: 30s
    rocketmq:
      name-server: ${ROCKETMQ_NAMESERVER:localhost:9876}
      producer-group: smart-rag-producer
      send-timeout: 3s
      max-reconsume-times: 16
```

### 6.2 Auto-Configuration

```java
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MessagePayloadCodec jacksonMessageCodec(ObjectMapper objectMapper) {
        return new JacksonMessageCodec(objectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    MessageBus rocketMQMessageBus(RocketMQTemplate rocketMQTemplate,
                                   MessagingProperties properties,
                                   MessagePayloadCodec codec) {
        return new RocketMQMessageBus(rocketMQTemplate, properties, codec);
    }
}

/**
 * 消息总线未启用时的空实现。
 * 业务代码注入 MessageBus 后无需判空，所有操作为 no-op。
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
            messageBus.send(Message.of("chat.message.save",
                new ChatMessagePayload(conversationId, userMessage,
                                       assistantContent, modelId)));
        } catch (MessagingException e) {
            log.warn("Message bus unavailable, falling back to synchronous save", e);
            conversationHelper.saveMessagesAndNotify(
                conversationId, userMessage, assistantContent,
                modelId, aiResponse, elapsedMs);
        }
    }
}

// 消费端 — 独立消费者
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
            ConsumerConfig.DEFAULT,
            ChatMessagePayload.class,
            (Message<ChatMessagePayload> msg) -> {
                var p = msg.payload();
                // 幂等保证：conversationHelper 使用 conversationId + messageIndex
                // 作为唯一约束。重复消费时 DB 唯一约束冲突被捕获并忽略。
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
    messageBus.send(Message.of("chat.usage.record",
        new UsagePayload(conversationId, modelId, tokens, elapsedMs)));
} catch (MessagingException e) {
    log.warn("Failed to publish usage record: {}", e.getMessage());
    // 非关键路径，不降级
}

// 消费端（批量聚合后写 DB）
messageBus.subscribe("chat.usage.record", "usage-group",
    ConsumerConfig.builder()
        .concurrency(2)
        .batchSize(50)
        .build(),
    UsagePayload.class,
    (Message<UsagePayload> msg) -> usageService.recordUsage(msg.payload()));
```

### 7.3 RAG 索引任务削峰

```java
// 发送端 — 文档上传后投递索引任务
messageBus.send(Message.ordered("rag.index.document",
    new IndexTask(documentId, teamId), documentId));  // hashKey = documentId

// 消费端 — 按 LLM 调用速率消费，不阻塞上传接口
messageBus.subscribe("rag.index.document", "index-group",
    ConsumerConfig.builder()
        .concurrency(3)
        .batchSize(5)
        .consumeTimeout(Duration.ofMinutes(30))
        .retryPolicy(new RetryPolicy(5))
        .build(),
    IndexTask.class,
    (Message<IndexTask> msg) -> etlService.processDocument(msg.payload()));
```

## 8. 改动文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `infrastructure/messaging/MessageBus.java` | 新增 | SPI 接口 |
| `infrastructure/messaging/Message.java` | 新增 | 消息信封 record |
| `infrastructure/messaging/MessageListener.java` | 新增 | 监听器接口 |
| `infrastructure/messaging/Subscription.java` | 新增 | 订阅句柄 |
| `infrastructure/messaging/ConsumerConfig.java` | 新增 | 消费者配置 |
| `infrastructure/messaging/RetryPolicy.java` | 新增 | 重试策略 |
| `infrastructure/messaging/MessagingProperties.java` | 新增 | 配置属性 |
| `infrastructure/messaging/MessagingAutoConfiguration.java` | 新增 | 条件装配 |
| `infrastructure/messaging/NoOpMessageBus.java` | 新增 | 未启用时的空实现 |
| `infrastructure/messaging/MessagePayloadCodec.java` | 新增 | 序列化接口 |
| `infrastructure/messaging/idempotent/IdempotentConsume.java` | 新增 | 幂等消费注解 |
| `infrastructure/messaging/idempotent/IdempotentConsumeAspect.java` | 新增 | AOP 幂等切面（Redis Lua） |
| `infrastructure/messaging/JacksonMessageCodec.java` | 新增 | JSON 序列化 |
| `infrastructure/messaging/exception/MessagingException.java` | 新增 | 基础异常 |
| `infrastructure/messaging/exception/MessagePublishException.java` | 新增 | 发送异常 |
| `infrastructure/messaging/exception/MessageConsumeException.java` | 新增 | 消费异常 |
| `infrastructure/messaging/rocketmq/RocketMQMessageBus.java` | 新增 | RocketMQ 实现 |
| `infrastructure/messaging/rocketmq/RocketMQSubscription.java` | 新增 | 订阅管理 |
| `application.yml` | 修改 | 新增 `app.messaging.*` 配置段 |
| `pom.xml` | 修改 | 新增 `rocketmq-spring-boot-starter` 依赖 |

## 9. 迁移步骤

### Phase A — SPI 层 + 核心实现

**目标**：`MessageBus` 接口 + RocketMQ 发送/消费核心路径可用。默认 `enabled=false`，零行为变更。

1. 添加 `rocketmq-spring-boot-starter` 依赖到 `pom.xml`。
2. 创建 `infrastructure/messaging/` 包结构，实现 SPI 接口和 record 类型。
3. 实现 `RocketMQMessageBus`：`send()`、`sendAsync()`、`subscribe()`。
4. 实现 `JacksonMessageCodec`（复用项目已有的 `ObjectMapper`）。
5. 实现 `NoOpMessageBus`（`enabled=false` 时的空实现）。
6. 实现 `MessagingAutoConfiguration` 条件装配。
7. 编写集成测试：`RocketMQMessageBusTest`（使用 Testcontainers RocketMQ）。

**退出条件**：`enabled=true` 时消息发送 → 消费者接收 → ACK 完整链路跑通。

### Phase B — RAG 索引任务迁移（最低风险）

**目标**：将 ETL 调度从线程池迁移到消息总线。

1. `EtlDispatchServiceImpl.dispatchAsync()` 的 `etlIoExecutor.execute()` 替换为 `messageBus.publish()`。
2. 创建 `EtlDocumentConsumer` 订阅 `rag.index.document`。
3. ETL 已有幂等保证（document 状态 + Redisson 分布式锁），迁移风险最低。

**退出条件**：文档上传 → 消息总线投递 → ETL 处理 → 完成链路跑通。

### Phase C — 聊天消息保存 + 用量记录迁移

**目标**：将聊天相关异步场景迁移到消息总线。

1. 接入 `chat.message.save`（消息持久化）。
2. 接入 `chat.usage.record`（用量记录）。
3. 将现有 Redis DLQ 中的残留条目排空后停止消费。

**退出条件**：两个业务场景通过消息总线完成异步处理，重启后消息不丢失。

### Phase D — 文档替代 + 旧 DLQ 清理

**目标**：完成所有迁移，清理遗留代码。

1. 将 `DocumentSupersedeService` 的 `@EventListener` + `@Async` 迁移到消息总线。
2. 确认旧 `MessageDeadLetterQueue` 为空且 7 天无新条目后移除。
3. 移除 `DeadLetterRetryScheduler`。
4. 实现 §3.1 定义的 7 个 Micrometer 指标。
5. 实现 `TracePropagator`（MDC traceId 传播）。

**退出条件**：所有 `@Async` / `@EventListener` 异步模式替换完毕；旧 DLQ 代码已移除；监控指标可查询。

## 10. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| RocketMQ 运维复杂度增加 | 高 | 引入 RocketMQ Dashboard；制定部署/监控 SOP |
| 消息重复消费 | 中 | 消费端必须幂等；`MessageListener` 文档明确标注幂等要求 |
| NameServer 单点故障 | 中 | NameServer 集群部署（建议至少 2 节点） |
| Broker 磁盘满导致写入失败 | 中 | 监控磁盘使用率，配置 `diskMaxUsedSpaceRatio` |
| 消费者积压 | 低 | 监控 `messaging.consumer.lag`，配置告警 |
| 现有 DLQ 迁移期间消息丢失 | 低 | 新旧 DLQ 并行运行，确认旧 DLQ 清空后再移除 |
| 有序消息 Queue 数量不足 | 低 | 默认 4 Queue，如需更高并发可通过 AdminTool 动态扩容 |

## 11. 参考资料

- 项目内部：`MessageDeadLetterQueue.java` — 现有 DLQ 模式
- 项目内部：`DeadLetterRetryScheduler.java` — 现有重试调度
- 项目内部：`FallbackAutoConfiguration.java` — 条件装配模式参考
- Apache RocketMQ 官方文档：https://rocketmq.apache.org/docs/
- RocketMQ Spring Boot Starter：https://github.com/apache/rocketmq-spring
- RocketMQ 消费者最佳实践：https://rocketmq.apache.org/docs/bestPractice/consumer/
- RocketMQ 消息重试机制：https://rocketmq.apache.org/docs/bestPractice/consumer/#retry
