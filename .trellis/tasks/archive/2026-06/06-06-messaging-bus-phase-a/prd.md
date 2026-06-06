# Messaging Bus Phase A — SPI 层 + RocketMQ 5.x 核心实现

> 设计文档: `docs/design/messaging-bus.md`
> 分支: `agentic-rag-dev`

## 目标

实现基于 SPI 的消息总线抽象层，使用 Apache RocketMQ 5.x gRPC 客户端作为消息中间件。
`enabled=false` 时零行为变更（NoOpMessageBus），`enabled=true` 时 PushConsumer 和 SimpleConsumer 两条路径完整可用。

## 范围

### In Scope (Phase A)

1. Maven 依赖: `rocketmq-client-java` 5.2.0 + `testcontainers` (集成测试)
2. SPI 接口: `MessageBus`, `MessageHandler`, `Subscription`, `ConsumerConfig`, `ConsumerMode`, `RetryPolicy`
3. 消息模型: `Message<T>` record (含 of/ordered/deduplicated 工厂方法)
4. 异常层次: `MessagingException` → `MessagePublishException` / `MessageConsumeException` / `PermanentConsumeException` + `MessagingErrorCode` (D类 400001-499999)
5. RocketMQ 5.x 核心实现: `RocketMQMessageBus` (Producer send/sendAsync + PushConsumer + SimpleConsumer)
6. 订阅管理: `RocketMQSubscription` (生命周期管理, 幂等 close)
7. 发送熔断: `SendCircuitBreaker` (per-topic, 三态 CLOSED/OPEN/HALF_OPEN)
8. 序列化: `MessagePayloadCodec` + `JacksonMessageCodec` (复用 Spring ObjectMapper)
9. 配置: `MessagingProperties` record (prefix=`app.messaging`, 含 RocketMQConfig/IdempotentConfig/CircuitBreakerConfig)
10. Auto-Configuration: `MessagingAutoConfiguration` (条件装配) + `NoOpMessagingConfiguration`
11. 健康检查: `MessagingHealthIndicator` (Producer/订阅/熔断器状态)
12. 空实现: `NoOpMessageBus` + `NoOpDeadLetterOperations`
13. Micrometer 指标: O-01 Phase A 必须指标 (send.count, consume.count, send.latency, consume.latency, retry.count, dead.count)
14. SimpleConsumer DLQ: 应用层 `sendToDeadLetter()` (Redis SETNX 去重 + 重试限制)
15. 幂等消费: `wrapWithIdempotent()` (Lua SETNX 原子标记, Redis 降级)
16. 集成测试: Testcontainers RocketMQ 5.x

### Out of Scope

- Phase B-D 业务迁移 (RAG 索引、聊天保存等)
- 事务消息
- SQL92 过滤
- TracePropagator (MDC 追踪传播, Phase D)
- DeadLetterOperations 完整实现 (仅骨架)
- `messaging.consumer.lag` 指标 (需 MQAdminExt, Phase D)

## 实现步骤

### Step 1: Maven 依赖 + 包结构

**文件**: `pom.xml`
- 添加 `rocketmq-client-java` 5.2.0
- 添加 `testcontainers` + `testcontainers-rocketmq` (test scope)

**创建包**: `com.smart.rag.infrastructure.messaging` 及子包:
```
infrastructure/messaging/
├── MessageBus.java
├── MessageBusManagement.java
├── Message.java
├── MessageHandler.java
├── Subscription.java
├── ConsumerConfig.java
├── ConsumerMode.java
├── RetryPolicy.java
├── MessagingProperties.java
├── MessagingAutoConfiguration.java
├── MessagingHealthIndicator.java
├── NoOpMessageBus.java
├── MessagePayloadCodec.java
├── JacksonMessageCodec.java
├── DeadLetterOperations.java
├── TracePropagator.java
├── SendCircuitBreaker.java
├── CircuitBreakerState.java
├── idempotent/
│   └── IdempotentConfig.java  (内部 record, 在 MessagingProperties 中)
├── exception/
│   ├── MessagingErrorCode.java
│   ├── MessagingException.java
│   ├── MessagePublishException.java
│   ├── MessageConsumeException.java
│   └── PermanentConsumeException.java
└── rocketmq/
    ├── RocketMQMessageBus.java
    └── RocketMQSubscription.java
```

### Step 2: SPI 接口 + 消息模型

- `Message<T>` record: id, topic, tag, payload, hashKey, deduplicationKey, headers, timestamp
- `MessageBus` interface: send, sendAsync, subscribe, shutdown, sendAfterCommit, deadLetterOperations
- `MessageBusManagement` interface: isProducerHealthy, activeSubscriptionCount, circuitBreakerState
- `MessageHandler<T>`: @FunctionalInterface, onMessage(Message<T>)
- `Subscription`: AutoCloseable, topic, group, isActive, pause, resume, close
- `ConsumerConfig` record + Builder (边界校验)
- `ConsumerMode` enum: PUSH, SIMPLE
- `RetryPolicy` record: DEFAULT, SIMPLE_DEFAULT, NO_RETRY
- `DeadLetterOperations` interface
- `TracePropagator` interface

### Step 3: 异常层次

- `MessagingErrorCode` enum (400001-400005)
- `MessagingException extends AbstractException`
- `MessagePublishException`, `MessageConsumeException`, `PermanentConsumeException`
- 融入项目已有 `AbstractException` + `IErrorCode` 体系

### Step 4: RocketMQ 核心实现

- `RocketMQMessageBus`: Producer + PushConsumer/SimpleConsumer 管理
  - 构造函数: ClientServiceProvider 注入
  - send(): 同步发送 + 熔断检查 + 指标记录
  - sendAsync(): 5.x 原生 CompletableFuture
  - subscribe(): Push/Simple 双模式分发
  - createPushSubscription(): MessageListener → ConsumeResult
  - createSimpleSubscription(): receive 循环 + Semaphore 滑动窗口 + Caffeine 重试计数
  - buildRocketMQMessage(): SPI Message → RocketMQ Message 映射
  - validateAndEncode(): 校验 + 编码合并
  - sendToDeadLetter(): 应用层 DLQ (Redis 去重)
  - wrapWithIdempotent(): Lua SETNX 幂等包装
- `RocketMQSubscription`: 幂等 close, AtomicBoolean 守卫
- `SendCircuitBreaker`: per-topic 三态熔断

### Step 5: Spring 集成

- `MessagingProperties`: @ConfigurationProperties(prefix="app.messaging"), record + compact constructor
- `JacksonMessageCodec`: 注入 Spring ObjectMapper copy
- `MessagingAutoConfiguration`: @ConditionalOnProperty + @EnableConfigurationProperties
- `NoOpMessagingConfiguration`: matchIfMissing=true
- `MessagingHealthIndicator`: AbstractHealthIndicator

### Step 6: 配置 + 应用 yml

- `application.yml`: `app.messaging.*` 配置段 (默认 enabled=false)
- `docker-compose.yml`: RocketMQ NameServer + Broker 服务

### Step 7: 集成测试

- `RocketMQMessageBusTest`: Testcontainers RocketMQ 5.x
- 测试场景: send, sendAsync, PushConsumer subscribe, SimpleConsumer subscribe, 熔断, 幂等

## 退出条件

1. `enabled=true` 时 PushConsumer 和 SimpleConsumer 两条路径完整跑通
2. O-01 Phase A 指标可在 Actuator 端点查询:
   - `messaging.send.count` / `messaging.send.latency`
   - `messaging.consume.count` / `messaging.consume.latency`
   - `messaging.retry.count` / `messaging.dead.count`
3. `enabled=false` 时 NoOpMessageBus 所有方法安全 no-op
4. 健康检查端点 (/actuator/health) 报告 Producer + 订阅 + 熔断器状态
5. 编译通过 + 集成测试通过

## 约束

- 遵循项目已有 record + compact constructor 配置模式 (参考 ChatFallbackProperties)
- 遵循 AbstractException + IErrorCode 异常体系
- 遵循 FallbackAutoConfiguration 条件装配模式
- 5.x gRPC 客户端, 不依赖 4.x Remoting 协议
- 所有配置通过 `app.messaging.*` 前缀, 与项目 `app.*` 惯例一致
- 不修改任何现有业务代码 (Phase A 纯新增)
