# Implement — 用量统计模块重写

按序执行；每步后编译保持绿。

1. [x] V28__usage_rework.sql：DROP token_usage、CREATE usage_event（IDENTITY PK、UUID 唯一、索引×3）、usage:view:all 权限 seed（幂等）；删除 sql/schema.sql。
2. [x] usage 包基础件：UsageScene 枚举、UsageContext、UsageEventPayload（Nullable Long）、UsageRecorder（MessageBus.sendAsync + deduplicated(eventId UUID)）。
3. [x] UsageRecordingChatModel（call/stream 采集、轮末 usage 求和、估算+estimated、success、totals 暴露）+ ChatModelAssembler（唯一组装点）。
4. [x] 5 调用点接入：ChatServiceImpl.chat/chatStream（scene=CHAT）、AgentModeStrategy（AGENT，护栏同实例）、IntentClassifier（INTENT）、RewriteClientResolver（REWRITE）；删 AbstractModeStrategy.usageRecorded CAS 段、processResult 记录调用。
5. [x] UsageEvent 实体 + UsageEventMapper(+XML insert) + UsageRecordConsumer 重写（新 topic、eventId 幂等）。
6. [x] 查询层：UsageQueryService + UsageController（records/summary/timeline/stats）+ DTO；删除旧 UsageController/Service/Impl/TokenUsageMapper/UsageStats/TokenUsageDTO。
7. [x] 前端 constants.ts 增 USAGE_VIEW_ALL。
8. [x] 测试：ChatModelAssemblerTest、UsageRecordingChatModelTest、UsageRecordConsumerTest、UsageQueryServiceTest、UsageControllerTest、AgentGuardrails 行为不变测试；更新受影响旧测试。
9. [x] 验证：mvn test 全绿 → detect_changes → grep 残留清零（token_usage、-1 哨兵、ConversationIdUtil.buildLikePrefix 用于 usage、ChatUsageTracker）。

## 验证命令

```bash
mvn -q compile && mvn -q test
grep -rn "ChatUsageTracker\|TokenCountingChatModel\|token_usage" src/ || echo clean
```
