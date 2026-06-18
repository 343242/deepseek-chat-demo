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

## Notes

- 详细 `design.md` / `implement.md` 在激活时补齐。
- 建议拆两步：先 `receive.last.success`（纯应用层、零外部依赖），再 `lag`（需 `rocketmq-tools` + Admin API）。
- 设计依据：`docs/design/messaging-bus.md` §3.1（O-03）+ §5.11 + §9 Phase D Step 5。
