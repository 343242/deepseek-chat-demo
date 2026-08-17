# Redis Stream MQ 迁移 — 总览（Parent）

## Goal

将 smart-rag 的消息总线后端从 RocketMQ 5.x 迁移到 Redis Stream，并在其上叠加 Outbox
publisher 可靠性层，形成统一的、运维更轻量的 Redis 原生 MQ 栈。

**最终架构**：
```
Publisher → OutboxMessageBus(装饰器) → RedisStreamMessageBus(新, 取代 RocketMQ) → Redis Stream
```

## 交付物（两个 child 任务，可独立验证）

| Child | 职责 | 可独立验证 |
|-------|------|-----------|
| `08-05-redis-stream-message-bus` | `RedisStreamMessageBus implements MessageBus`——XADD/XREADGROUP/XACK/XAUTOCLAIM/DLQ，**直接替换** `RocketMQMessageBus`（删除 RocketMQ 全部代码/依赖/脚本） | ✅ 单独装配即可跑通三个 topic（无 Outbox） |
| `08-05-publisher-reliability-outbox` | `OutboxMessageBus` 装饰器 + Relay + Redisson 协调（leader/共享熔断），delegate 是 `RedisStreamMessageBus` | ✅ MQ 透明 |

## 执行顺序（依赖关系）

1. **Child 1（RedisStreamMessageBus）先行**——MQ 后端，整个迁移的基础。完成后 app 仅靠 Redis Stream
   跑通三个 topic（含 FIFO/重试/DLQ），且 RocketMQ 全部代码/依赖/脚本已删（`grep = 0`）。
2. **Child 2（Outbox）后行**——装饰器，delegate 是 `RedisStreamMessageBus`。完成后 publisher
   可靠性（outbox + 非阻塞 + 有限重试 + usage 可观测）到位。
3. **收尾（parent 验收）**——`docker-compose` 移除 RocketMQ 容器（rmqnamesrv/rmqbroker/rmqdashboard）、
   Redis 持久化策略调整（`noeviction`/AOF everysec）、全链路压测。

> Child 1 是干净 cutover（直接替换，无灰度开关）。RocketMQ 容器移除归 parent 收尾
> （compose 层面，与代码删除解耦）。

## 跨 Child 契约（接口冻结点）

| 契约 | 冻结方 | 内容 |
|------|--------|------|
| `MessageBus` SPI | 既有 | `send/sendAsync/subscribe/shutdown/sendAfterCommit/deadLetterOperations` 不改 |
| `MessageEnvelope` | 既有 | 不改；`hashKey` 在 Redis 下用于"业务层 per-key 串行"提示（实际由 `EtlDispatchServiceImpl` 的 `RLock` 保证） |
| `MessageBusManagement` | Child 1 扩展 | 加 `isCircuitBreakerOpen(topic)`，供 Child 2 的 `SharedCircuitBreakerGate` 读 |
| 消费端 `IdempotentHandler` | 既有（不动） | SETNX 幂等包装，对 RedisStreamMessageBus 透明复用 |
| DLQ stream 命名 | Child 1 定义 | `dlq:{topicPrefix}{topic}`；Child 2 的 relay `dead` 行与此无关（不同层） |

## Parent 验收条件

- [x] `RedisStreamMessageBus` 是唯一 `MessageBus` 实现（child 1，已删 RocketMQ 代码/依赖/脚本）。
- [x] `OutboxMessageBus`（`@Primary`）装饰 `RedisStreamMessageBus`，publisher 业务逻辑零改动（child 2）。
- [x] 三个 topic（`chat_message_save` / `chat_usage_record` / `rag_index_document`）全链路跑通：
      send → outbox → RedisStream → consumer → 落库/索引。
- [x] RocketMQ 代码/依赖/脚本移除（child 1）：`grep -rn 'rocketmq\|RocketMQ' src/main pom.xml scripts/` = 0。
- [x] RocketMQ 容器移除（parent 收尾）：`docker-compose` 删 rmqnamesrv/rmqbroker/rmqdashboard，
      `grep -rn 'rocketmq\|RocketMQ\|rmqbroker\|rmqnamesrv' docker-compose*.yml` = 0。
- [x] Redis 持久化策略生效：`maxmemory-policy noeviction`（或 stream key 豁免），AOF everysec。
- [x] 2c4g 服务器（仅 Redis，无 RocketMQ 容器）冷启 + 三条链路冒烟通过。

## Out of Scope

- Redis Cluster 分片（单 Redis + Sentinel 足够，见前置分析）。
- `MessageEnvelope` / `MessageBus` SPI 重设计。
- 消费端 `IdempotentHandler` 重写（保持 SETNX）。
