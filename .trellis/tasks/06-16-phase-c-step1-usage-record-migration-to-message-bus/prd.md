# Phase C Step 1: Chat usage 记录迁移到消息总线 (PoC)

## Goal

将 `ChatUsageTracker` 的用量记录从同步直调 `UsageService.recordUsage` 迁移到消息总线：
生产端 `messageBus.send(chat_usage_record)`，消费端新增 `UsageRecordConsumer` 异步落库。

这是 Phase C 的**可行性验证**——跑通一条最低风险链路
（SPI → Producer → PushConsumer → DB），证明消息总线设计在 chat 模块可落地，
再扩到高风险的 `chat_message_save` 双路径（Step 2）。

范围严格限定 `docs/design/messaging-bus.md` §9 Phase C **Step 1**。
Step 2（chat 消息保存）、Step 3（legacy DLQ 排空）不在本任务。

## What I already know (代码现状)

### 接入点：ChatUsageTracker
- `chat/service/ChatUsageTracker.java`，`@Component`，构造注入 `UsageService`
- 两个 `recordUsage` 重载：
  - `recordUsage(conversationId, modelId, ChatResponse aiResponse, durationMs)` — 从 `ChatResponse.metadata.usage` 提取 prompt/completion/total tokens
  - `recordUsage(conversationId, modelId, durationMs)` — 降级版，默认 -1
- 内部 try/catch 吞咽异常，仅 `log.error`（"非关键路径"语义）
- 调用方：`AbstractModeStrategy.recordUsage()`（同步 `execute` + 流式 `executeStream` 两路径都走）

### 消费目标：UsageService / UsageServiceImpl
- `chat/service/UsageService.java`：`recordUsage(conversationId, modelId, promptTokens, completionTokens, totalTokens, durationMs)`
- `chat/service/impl/UsageServiceImpl.java`：DB 写入

### 消息总线基础设施（Phase A + B 已落地）
- `MessageBus` SPI + `MessageEnvelope`（工厂方法 `of / ordered / deduplicated`）
- `RocketMQMessageBus`（`enabled=true`）+ `NoOpMessageBus`（`enabled=false`，默认）
- **`NoOpMessageBus.send()`：log.debug + 返回 `"no-op-"+UUID`，消息丢弃**
- `MessagingAutoConfiguration`：`@ConditionalOnProperty(app.messaging.enabled)` 门控
- consumer 模式参考：`rag/etl/EtlDocumentConsumer`（`implements SmartLifecycle`，`start()` 里 `messageBus.subscribe`）

### 配置约定
- Topic 裸名 `chat_usage_record`，物理名 `SMART_RAG_chat_usage_record`（`topicPrefix` 拼接）
- 消费组 `usage-group`（运维侧 `maxDeliveryAttempts=16`）
- `app.messaging.enabled` 默认 `false`

## Decisions（已定）

### D1: `enabled=false`（NoOp bus）时 usage 接受丢失 + 声明
- usage 经 `messageBus.send()` 发布；NoOp bus（`enabled=false`）丢弃消息 → usage 不记录
- **开发与生产环境均 `enabled=true`**（需本地/环境 RocketMQ 可用），NoOp 仅 CI / 单测 mock 场景触发，实际几乎不丢
- 不做双路径 fallback（会让 `ChatUsageTracker` 耦合 bus 具体类型，违反 SPI 解耦）
- 落地：`ChatUsageTracker` javadoc + PR 说明此行为

### D2: 全链路 `modelId` → `candidateId` 参数名 rename
- `ChatUsageTracker.recordUsage` / `UsageService.recordUsage` / `UsageServiceImpl` / 调用方 javadoc 与局部变量
- 内部服务接口，无对外 API 字段名约束；Java 按位置传参，调用方不受影响
- 与 §9 Step 1、§7.2、LLM SPI 统一（candidateId）对齐

## Open Questions

（无）

## Implementation Plan

1. **payload record**：`UsagePayload(conversationId, candidateId, promptTokens, completionTokens, totalTokens, durationMs)`（命名对齐 §7.2 规范，非草案的 `UsageRecordPayload`）
2. **ChatUsageTracker 改造**：
   - 移除 `UsageService` 依赖，注入 `MessageBus`
   - `recordUsage` 内部 `messageBus.send(MessageEnvelope.deduplicated("chat_usage_record", payload, dedupKey))`
   - `deduplicationKey = conversationId + ":" + candidateId + ":" + <毫秒时间桶>`（防同会话同模型短时重复，跨重试稳定）
   - 保留 try/catch 吞咽（send 失败仅记日志，符合非关键路径）
   - 参数名 `modelId` → `candidateId`
3. **UsageRecordConsumer**（新增，仿 `EtlDocumentConsumer`）：
   - `implements SmartLifecycle`，`start()` 里 `messageBus.subscribe("chat_usage_record", "usage-group", ConsumerConfig.DEFAULT, UsagePayload.class, handler)`（`ConsumerConfig.DEFAULT` 即 `ConsumerMode.PUSH`，对齐 §7.2；`@ConditionalOnProperty(app.messaging.enabled=true)` 门控）
   - handler 注入 `UsageService`，调 `usageService.recordUsage(...)`
4. **参数名迁移**：`UsageService` / `UsageServiceImpl` / 调用方 `modelId` → `candidateId`
5. **测试**：
   - `ChatUsageTracker` 单测：verify `messageBus.send` 被调用 + payload 字段正确（用 mock `MessageBus`）
   - `UsageRecordConsumer` 单测：handler 触发 `usageService.recordUsage`（参数正确）
   - `enabled=false` 时 NoOp 行为声明（文档化，非数据保证）

## Acceptance Criteria

- [x] `ChatUsageTracker` 注入 `MessageBus`，不再持有 `UsageService`
- [x] `recordUsage` 通过 `messageBus.send` 发布到 `chat_usage_record`（deduplicationKey 设置）
- [x] `UsageRecordConsumer` 订阅 `usage-group`，落库调 `UsageServiceImpl.recordUsage`
- [x] 同步（`execute`）+ 流式（`executeStream`）两路径 `recordUsage` 链路都经 bus（改造在 ChatUsageTracker 内部，所有调用方自动走 bus）
- [x] `modelId` 参数名全链路改为 `candidateId`（ChatUsageTracker / UsageService / UsageServiceImpl；调用方变量名本就是 candidateId，无需改）
- [x] Q1/Q2 决策落地 + 行为变化（enabled=false 丢失）在 javadoc/PR 声明
- [x] 单测覆盖 send + consume 两端（11 tests 全绿）

## Out of Scope

- Step 2 chat 消息保存迁移（`chat_message_save`）
- Step 3 legacy DLQ 排空
- bus 失败同步降级（usage 非关键，仅记日志；降级是 chat save 的事，见 §7.1）
- Topic/消费组物理创建（运维前置，见 §5.12；本任务假设已创建或测试用 mock）

## Technical Notes

- 前驱：messaging-bus Phase A（SPI + RocketMQ）+ Phase B（`EtlDocumentConsumer`）已落地
- 前驱：LLM SPI 统一（candidateId）
- 参考：`docs/design/messaging-bus.md` §7.2、§9 Phase C Step 1
- consumer 模式参考：`rag/etl/EtlDocumentConsumer.java`（SmartLifecycle + subscribe）
- 编辑符号前需 `gitnexus_impact`（CLAUDE.md 要求）；实施时索引已 fresh，无需重新 analyze

## 实施完成（2026-06-17）

**改动文件**：
- `chat/service/UsagePayload.java`（新增，§7.2 record 定义）
- `chat/service/ChatUsageTracker.java`（重写：注入 `MessageBus` 移除 `UsageService`；`send(deduplicated envelope)`；`extractOrNeg` helper 安全提取 Integer token；`log.error` 用 `ConversationIdUtil.mask` 脱敏）
- `chat/service/UsageRecordConsumer.java`（新增，`SmartLifecycle` + `@ConditionalOnProperty`，`ConsumerConfig.DEFAULT`，handler 转发 `UsageService.recordUsage`）
- `chat/service/UsageService.java` / `impl/UsageServiceImpl.java`（`recordUsage` 参数名 `modelId`→`candidateId` + javadoc）
- `chat/service/ChatUsageTrackerTest.java`（重写：mock `MessageBus`，`ArgumentCaptor` 验证 envelope payload/topic/deduplicationKey）
- `chat/service/UsageRecordConsumerTest.java`（新增：lifecycle 4 + handler 1）

**命名对齐（PRD 草案 → §7.2 规范，implement.jsonl 指认 messaging-bus.md 为"直接蓝图"）**：
- payload 类名 `UsageRecordPayload` → `UsagePayload`
- consumer config `PUSH_CONFIG` → `ConsumerConfig.DEFAULT`（DEFAULT 即 `ConsumerMode.PUSH`）

**行为变化（已 javadoc 声明，PR 须说明）**：
- `recordUsage(aiResponse)` 在 `usage`/`metadata` 为 null 时：原"跳过不记录" → 现"记录 -1 token + duration"（防御性增强，§7.2 `extractOrNeg` 语义）。调用方 `AbstractModeStrategy` 已在 usage 空时走 durationOnly 重载，故实际触发极少。
- `enabled=false`（NoOp bus）时 usage 不记录（D1 声明）。

**调用方说明**：`AbstractModeStrategy` / `ChatServiceImpl` 的局部变量名本就是 `candidateId`（LLM SPI 统一后），无需改动；`recordUsage` 按位置传参，不受参数名 rename 影响。

**验证**：
- `ChatUsageTrackerTest` 6 + `UsageRecordConsumerTest` 5 = 11 tests 全绿
- `ChatServiceImplTest` + `ChatServiceImplResolveCandidateIdTest` 8 tests 全绿（调用链未受影响）
- `gitnexus_impact`：ChatUsageTracker MEDIUM / UsageService MEDIUM / UsageServiceImpl LOW，无 HIGH/CRITICAL
- `gitnexus_detect_changes`：唯一受影响执行流 `ProcessResult → TokenUsage`（目标链路），无意外波及，risk=medium

**待办（运维/PR）**：Topic `SMART_RAG_chat_usage_record` + 消费组 `usage-group`（`maxDeliveryAttempts=16`）需在环境创建（§5.12）；PR 描述须声明 enabled=false 丢失 + usage-null 记录 -1 的行为变化。
