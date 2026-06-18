# Design — D-5 Step 1: `messaging.consumer.receive.last.success`（O-03）

> **本轮范围仅 R1（`receive.last.success`）**。R2（`consumer.lag`，需 `MQAdminExt`/`rocketmq-tools`）
> 与 R3（`assigned.groups`，5.x API 支持度未定）推迟，后续按需推进。

## 目标指标

`messaging.consumer.receive.last.success`（Gauge，tags: `topic`、`group`）= SimpleConsumer
最近一次**成功 receive** 的 epoch ms。值 `0` 表示从未成功过。

## 机制

`SimpleConsumerReceiveLoop` 的 receive 循环里，`simpleConsumer.receive(...)` **无异常返回**后
（即便本次拉取为空）立即更新时间戳。receive() 抛异常（进入指数退避 catch 分支）时**不更新**。

- gauge 值持续刷新 = 消费者存活、Broker 可达、receive 循环在转。
- gauge 值陈旧（长时间不刷新）= receive 持续抛异常 / 线程卡死 → **监控判据**：
  `now() - last.success > N × invisibleDuration` → P1 告警（消费者卡死）。

> 设计文档 §3.1 O-03 原文："最近一次成功 receive 的时间戳（epoch ms），用于检测消费者卡死"。

## 实现

### MessagingMetrics
- 新增 `Map<String, AtomicLong> lastReceiveSuccess`（key = `topic:group`，topic 无冒号，安全）。
- `recordReceiveSuccess(topic, group)`：`computeIfAbsent` 拿到 per-key `AtomicLong`，**懒注册** gauge
  （`registry.gauge(name, Tags.of(...), holder, AtomicLong::doubleValue)`，幂等），再 `set(now)`。
- registry 为 null 时 no-op（与现有方法一致）。

### SimpleConsumerReceiveLoop
- receive 循环 `backoffMs = 1000;` 之后插一行 `metrics.recordReceiveSuccess(topic, group);`
  （在 `messages.isEmpty()` 判空之前——空拉取也算成功 receive）。

## 兼容性 / 风险

- 低。纯增量指标，不改任何现有行为。
- gauge 用 `doubleValue`：epoch ms (~1.7e12) 在 double 精确表示范围内，无精度损失。
- 仅 SimpleConsumer 路径有此指标；PushConsumer 由 Broker 自动投递无 receive 循环，O-03 本就针对 SimpleConsumer。
