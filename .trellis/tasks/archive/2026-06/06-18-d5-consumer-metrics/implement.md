# Implement — D-5 Step 1: `messaging.consumer.receive.last.success`

## 步骤

1. **MessagingMetrics.java**：
   - [ ] 加 import：`Tags`、`Map`、`ConcurrentHashMap`、`AtomicLong`
   - [ ] 加字段 `lastReceiveSuccess`（ConcurrentHashMap）
   - [ ] 加 `recordReceiveSuccess(topic, group)`（computeIfAbsent + 懒注册 gauge + set now）
2. **SimpleConsumerReceiveLoop.java**：receive 循环 `backoffMs = 1000;` 后插 `metrics.recordReceiveSuccess(topic, group);`
3. **测试 MessagingMetricsTest.java**（新）：
   - [ ] 注册 + 值更新（SimpleMeterRegistry，值在 [before, now+1s]）
   - [ ] 同 (topic,group) 二次调用幂等（gauge 只 1 个）
   - [ ] null registry 不抛
4. **验证**：`./mvnw test -Dtest=MessagingMetricsTest,SimpleConsumerReceiveLoopTest`（若有）
5. `gitnexus_detect_changes`（索引刷新后）/ grep 核验
6. commit（不 push）

## 后续（本轮不做）
- R2 `consumer.lag`：需引入 `rocketmq-tools` + `MQAdminExt`（Broker admin 连接 + ACL），单独推进。
- R3 `assigned.groups`：先确认 5.x 客户端是否暴露当前处理 messageGroup 数的 API。
