# PRD — D-5: 消费端指标补全（lag / receive.last.success / assigned.groups）

## Goal

补齐设计文档 §3.1 / §5.11 定义但未实现的消费端 Micrometer 指标。

## Background

现状（`MessagingMetrics.java`）：已有 `send.count` / `consume.count` / `send.latency` / `consume.latency` /
`retry.count` / `dead.count` + bonus（`send.payload.size`、`send.post_commit_fail`、`idempotent.degraded`）。

缺失（`grep` 零命中）：

- `messaging.consumer.lag`（Gauge，topic+group）——设计文档标注需 Broker Admin API（`MQAdminExt`），Phase D 实现
- `messaging.consumer.receive.last.success`（Gauge，topic+group，**O-03**）——SimpleConsumer receive 循环最近成功时间戳，检测消费者卡死
- `messaging.consumer.assigned.groups`（Gauge，group+instance，**§5.11**）——当前处理的 messageGroup 数

## Requirements

- **R1**：`messaging.consumer.receive.last.success` —— SimpleConsumer 每次 receive 成功更新时间戳，注册为 Gauge（**实现成本低、价值明确（卡死检测），优先做**）。
- **R2**：`messaging.consumer.lag` —— 集成 `MQAdminExt`（`consumerProgress`）采集积压量；需引入 `rocketmq-tools` 依赖 + Broker admin 连接配置。
- **R3**：`messaging.consumer.assigned.groups` —— 消费者侧统计当前 inflight/处理的 messageGroup（5.x 客户端 API 支持度需确认）。

## Open Questions（design.md 解决）

- **Q1**：`rocketmq-tools` 依赖引入 + Broker admin ACL 配置（accessKey/secretKey）是否在本次范围？
- **Q2**：lag 采集频率与开销（Admin API 调用有成本，建议定时采样而非每消息）。
- **Q3**：`assigned.groups` 5.x 客户端是否暴露 API？若无则降级或推迟。

## Acceptance Criteria

- [ ] 三项指标在 `/actuator/metrics` 可查询
- [ ] `messaging.consumer.receive.last.success` 端到端验证（SimpleConsumer 场景）
- [ ] lag 指标采集不显著影响吞吐（采样式）
- [ ] `design.md` 记录依赖与采集策略决策

## Resolution（2026-06-18 核对存档）

- **R1 `receive.last.success`** — ✅ **DONE**（commit `b5868cb`）：SimpleConsumer receive 成功后记 epoch ms，per-(topic,group) AtomicLong 懒注册 gauge。
- **R2 `consumer.lag`** — ⏸ **DEFERRED**：需 `rocketmq-tools`（pom 未引入，重依赖）+ Broker admin（nameserver + ACL，本环境未配）。公开客户端 API（含 `LitePushConsumer`）不暴露 broker offset，应用层算不出 lag。**已被 RocketMQ Dashboard（:8082）+ 既有 `receive.last.success`（卡死检测）覆盖**；待多实例规模 / 真有积压告警需求时连同 `rocketmq-tools` 一起评估。
- **R3 `assigned.groups`** — ❌ **INFEASIBLE / 已关闭**：核对 `rocketmq-client-java` 5.2.0 公开 API（`PushConsumer`/`SimpleConsumer`/`LitePushConsumer`/`MessageView`）均不暴露 assignment/partition；仅内部 `apache/rocketmq/v2` protobuf 有（非公开 API，硬用=脆耦合）。`MessageView.getMessageGroup()` 可在应用层近似"近期见过的 group"，但非 broker 真实分配、单实例价值边际。

**LitePushConsumer 核对**（应要求核查）：确为公开 API（`ClientServiceProvider.newLitePushConsumerBuilder()`），但特征是「lite topic 订阅模型」（`subscribeLite`/`getLiteTopicSet`/`MessageView.getLiteTopic()`），**非** lag/assignment 可观测性入口——不解 R2/R3 阻塞。

**§5.6 `DeadLetterOperations`（关联项，d5 范围外但一并核对）**：scan/replay 应用层可行（订阅 `%DLQ%{group}` + resend，零新依赖），但 **S-02 ops-only、当前无调用方**；看死信已被 RocketMQ Dashboard 覆盖；`deadLetterCount` 需 admin（同 R2 阻塞）。→ 维持 `UNSUPPORTED` 桩，待有运维工具需要该 SPI 时再做。

**结论**：R1 落地；R2/R3/§5.6 收益低、有真实阻塞（API/infra），且真实运维需求已被 Dashboard + 既有指标覆盖——**不为凑设计清单堆投机代码，本轮收尾**。

## Notes

- 设计依据：`docs/design/messaging-bus.md` §3.1（O-03）+ §5.11 + §9 Phase D Step 5。
