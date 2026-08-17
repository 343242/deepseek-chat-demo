# PRD — 用量统计模块重写：统一采集 + 数据面完备

## Goal

将散落在调用点/仅存内存的 LLM 用量采集统一为单一装饰器机制，并以显式 user 维度重写存储与查询面，使阻塞式/流式/CHAT（单轮多轮）/Agent 的 token 与耗时数据真实、完备、可分页可聚合可管理。

## 痛点（现状缺陷）

1. **流式聊天 token 恒为 -1**：`AbstractModeStrategy.doFinally` 调 `recordUsage(tracker, ctx, null)`，从不传末帧 ChatResponse；末帧 usage 元数据其实已由 `ChatModelAdapter.buildResponseMetadata` 注入，只是没人消费。
2. **Agent 模式完全不记用量**：`AgentModeStrategy` 不走 `AbstractModeStrategy` 的 usage 记录；`TokenCountingChatModel` 只在内存累计供护栏检查，从不落库——两套采集机制互不相通。
3. **无统一咽喉点**：`ChatModelAdapter` 在 5 处散装 `new`（ChatServiceImpl×2、AgentModeStrategy、IntentClassifier、RewriteClientResolver），横切能力没有单一插入点。
4. **数据面缺口**：
   - `token_usage` 表无 `user_id` 列，用户隔离靠 conversation_id 前缀 LIKE——管理员无法查他人用量；
   - 无按天/月时间桶聚合；无总计（summary）端点；
   - 聚合无排序；明细不分页全量返回，且强制必须传 model 或 conversation 之一；
   - token 未知时用 -1 哨兵污染 SUM。
5. **幂等键碰撞**：dedup key `conversationId:candidateId:毫秒桶` 在 Agent 同毫秒多轮时碰撞。
6. **废弃快照**：`sql/schema.sql` 与 Flyway 双源，docker-compose 已注明废弃。

## Requirements

- 阻塞式/流式/CHAT（单轮/多轮）/AGENT/INTENT/REWRITE 全部 LLM 调用经**同一装饰器**（`UsageRecordingChatModel`）采集 usage；每 `call()/stream()` 一次 = 一条用量事件；失败请求也记录（success=false）。
- 采集不干扰主路径：经 MessageBus（outbox → Redis Stream）异步落库，发布失败仅计数不抛出。
- AgentGuardrails 轮间 token 上限检查消费**同一个**装饰器实例（合并 `TokenCountingChatModel`，删除原类）。
- `usage_event` 表（Flyway V28）：显式 user_id、scene、event_id 唯一幂等、token 列 NULL 表未知；DROP 旧 token_usage；新增 `usage:view:all` 权限仅绑 ADMIN。
- 查询端点：records（分页+过滤）、summary（总计+成功率）、timeline（day/month 时间桶补零）、stats（dim=model|scene|user，可排序）；管理员（usage:view:all）可传 userId 查他人，dim=user 仅管理员。

## Acceptance Criteria

- [ ] 流式 CHAT 请求落库含真实 prompt/completion/total tokens（非 -1/NULL，当厂商返回 usage 时）
- [ ] Agent 请求每轮 ReAct 各落一条 usage 事件（scene=AGENT），护栏上限检查行为不变
- [ ] INTENT/REWRITE 场景调用落库（scene 对应）
- [ ] 明细端点分页返回、支持 scene/model/conversation/时间过滤，不再强制传 model/conversation
- [ ] timeline 支持 day|month，空桶补零；summary 含请求数/token 求和/时长/成功率
- [ ] stats 支持 dim=model|scene|user + sort/order 白名单；无 usage:view:all 的用户传 userId 被拒
- [ ] 删除清单全部落地，无兼容层：ChatUsageTracker、UsagePayload、TokenCountingChatModel、TokenUsage(+Mapper/XML)、旧 UsageService/Controller、-1 哨兵、前缀 LIKE 隔离、sql/schema.sql
- [ ] mvn test 全绿；detect_changes 影响面与预期一致；grep 确认旧形态清零

## 非目标

- 前端 usage 页实现（另开任务；仅补 PERMISSION 常量）。
- embedding/rerank 采集（EmbeddingModel 边界，装配点模式可平移）。
- cacheHitTokens 落库（需扩展 Spring AI Usage 表达）。
