# Align messaging-bus design (Phase C) with LLM SPI + Strategy pattern

## Goal

`docs/design/messaging-bus.md` 的 Phase C（聊天消息保存 + 用量记录迁移）设计基于旧版
ChatServiceImpl 扁平结构，与当前已落地的「LLM SPI 统一 + 策略模式 + 流式响应」现实脱节。
本任务不写代码，只更新设计文档，使 §7 业务集成方式与 §9 Phase C 迁移计划能在当前代码结构下
真实落地。

## What I already know

### 现状（chat 模块已演进的部分）

- 引入了 Mode Strategy 模式：`AbstractModeStrategy` / `SimpleModeStrategy` / `MultiTurnModeStrategy` / `ModeRouter`
- 执行路径分两路：
  - 同步：`AbstractModeStrategy.execute(ctx)` 返回 `StrategyExecuteResult`（含 Spring AI `ChatResponse` 含 usage metadata）
  - 流式：`AbstractModeStrategy.executeStream(ctx)` 返回 `Flux<String>`，在 `doFinally` 里做收尾
- `ChatServiceImpl.processResult()`（同步路径）仍然在 `:190` 调用 `ChatConversationHelper.saveMessagesAndNotify(...)`
- `MultiTurnModeStrategy.executeStream()` 在 `:96` 的 `Flux.doFinally` 里调用 `conversationHelper.saveMessagesAndNotify(...)`
- `ChatUsageTracker` 已经成为用量记录的中心化入口（`recordUsage(conversationId, modelId, ChatResponse, durationMs)`），内部 try/catch 吞咽异常
- `AbstractModeStrategy.recordUsage()`（L70-77）在 stream 完成（L59）和 MultiTurn（L87）里调用
- LLM SPI 已统一，model 标识符由 `modelId` 改为 `candidateId`（registry candidate ID，commit `a98fa9b`，BREAKING）

### 设计文档与现状的偏差

1. **§1.1 表格**：声称 chat 用 `ApplicationEventPublisher` 做进程内事件 —— grep 全仓 0 命中，chat 模块根本没用过
2. **§7.1 ChatMessagePublisher**：假设 publisher 只挂在 `processResult` —— 遗漏了流式路径 `MultiTurnModeStrategy.executeStream`
3. **§7.1 saveMessagesAndNotify**：方法名暗示含 notify 副作用（推送其他会话端 / SSE 通知），设计假设整个方法是"保存"，notify 异步化可能有体验回归
4. **§7.2 UsagePayload**：使用 `modelId` —— 需替换为 `candidateId` 对齐 LLM SPI
5. **§7.2 ChatUsageTracker 接入点**：设计假设 usage 散落各处需改造，实际已经全部收口到 `ChatUsageTracker.recordUsage()`，改造点比设计更少
6. **§9 Phase C 退出条件**：未包含流式路径验证

### 已就绪的消息总线基础设施（Phase A + B 已落地）

- `MessageBus` SPI + `MessageEnvelope`（注意：实现重命名为 `MessageEnvelope` 而非设计中的 `Message`）
- `RocketMQMessageBus` + `PushConsumerListener` + `SimpleConsumerReceiveLoop`
- `MessagingAutoConfiguration` 默认 `enabled=false`
- Phase B 已迁移：`EtlDispatchServiceImpl` → `EtlDocumentConsumer`，topic=`rag_index_document`

## Assumptions (temporary)

- 仍保留 `MessageBus` SPI 与 Phase A 落地的实现不变，仅修订 Phase C 相关章节
- `candidateId` 直接替换 `modelId` 出现的所有位置，不引入兼容层
- 设计文档更新本身不改任何 Java 代码

## Decisions

- **范围（已定）**：本次更新 §1.1、§1.3、§7.1、§7.2、§9 Phase C、§10 风险评估六节。
  其他章节保持不变。理由：用户希望设计文档与现状彻底对齐，避免 §1.1 仍写 chat 用 ApplicationEventPublisher
  而后面 §7 又改基于 ChatUsageTracker —— 内部自相矛盾。

- **saveMessagesAndNotify 拆分策略（已定）**：整体下沉到 consumer。
  publisher 端 `messageBus.send(Message.deduplicated("chat_message_save", ...))` 替换原同步方法调用；
  consumer 端调 `ChatConversationHelper.saveMessagesAndNotify(...)`（事务、双消息写入、`onNewMessages` 全保留）。
  - 理由：`onNewMessages` 经核实是会话元数据更新（计数+标题），不是 SSE 推送，延迟几十毫秒可接受
  - DLQ 替换：`MessageDeadLetterQueue` legacy 实现退役，由 RocketMQ `%DLQ%{group}` + 应用层 `%APP_DLQ%{group}` 接管
  - 这条决策是 Phase C 落地的核心，连带让 §10 风险评估里的"现有 DLQ 迁移期间消息丢失"条目得以闭环

- **Phase C 退出条件纳入流式验证（已定）**：
  `MultiTurnModeStrategy.executeStream` 是当前多轮对话主路径，必须在退出条件里显式验证
  `doFinally` 内的 publisher 触发 + consumer 异步落库 + 客户端 SSE 流结束时机的一致性。
  - 同步路径（`processResult`）退出条件保留
  - 新增流式路径退出条件：SSE 流关闭 → 消息投递 → consumer 落库 → 计数更新 的端到端验证
  - 包含一项退化测试：bus 不可用时 `messageBus.send` 抛 `MessagingException` → catch → 同步 fallback `saveMessagesAndNotify`

## Decisions (扩展边界场景)

全部纳入设计文档：

- **DC-01 事务边界 + sendAfterCommit 语义**：§7.1 显式声明 publisher 调用点
  - 同步路径 `ChatServiceImpl.processResult` 当前不在事务上下文（LLM 响应完成后直接发）→ 直接 `messageBus.send`
  - 流式路径 `MultiTurnModeStrategy.executeStream.doFinally` 不在事务上下文 → 直接 `messageBus.send`
  - 未来若在事务上下文中调用，必须改用 `messageBus.sendAfterCommit(message)`（SPI §4.2 已定义）
  - 在 §7.1 添加 "事务边界" 注释块，引用 §4.2 的 DC-01 决策

- **Bus 失败同步降级路径明确化**：§7.1 添加完整降级链
  - `messageBus.send` 抛 `MessagingException` → catch → 同步调 `ChatConversationHelper.saveMessagesAndNotify`
  - 双写风险消除：deduplicationKey = `conversationId + ":" + md5(userContent)`，总线级 Redis SETNX 拦截重复
  - 业务层兜底：DB 唯一约束 `(conversation_id, message_index)` —— 这是已有约束，写进文档作为最后防线

- **Topic/消费组初始化运维清单**：§5.12 + §9 Phase C 都需更新
  - 新增脚本片段：`SMART_RAG_chat_message_save` / `SMART_RAG_chat_usage_record` 标准 Topic
  - 新增消费组：`save-group`（maxDeliveryAttempts=16）/ `usage-group`（maxDeliveryAttempts=16）
  - 添加 "Phase C 启动前确认 Topic 与消费组已创建" 检查项

- **明确 Phase C/D 依赖关系**：§9 Phase D 第一项退化为 "确认 Phase C 已完成"
  - Phase C 完成前，`MessageDeadLetterQueue` 与 `DeadLetterRetryScheduler` 不可删除
  - 添加到 §9 Phase C 退出条件："legacy `MessageDeadLetterQueue` 在 7 天滚动窗口内 0 新条目"
  - §9 Phase D 第一项明确写 "前置条件：Phase C 退出条件全部满足"

## Decisions (补充：Topic 实现与设计对齐)

经核实实际实现，追加 4 处脱节修复：

- **#1 Message → MessageEnvelope 全章节重命名**：实际 SPI 类名已重命名为
  `MessageEnvelope<T>`（避免与 `org.apache.rocketmq.client.apis.message.Message` 命名冲突，
  见 §4.3 注释）。设计文档 §4.1 定义、§4.2/§4.3/§4.4/§4.7、§5.3/§5.3.1/§5.4/§5.10、
  §7.1/§7.2/§7.3、§8 全部需同步重命名。工厂方法 `Message.of/ordered/deduplicated`
  改为 `MessageEnvelope.of/ordered/deduplicated`。
  - **作用域**：仅 SPI 业务示例代码中的 `Message<T>` / `Message.<T>xxx`；
    RocketMQ 内部 `org.apache.rocketmq.client.apis.message.Message` 保留原名（如 §5.3 buildRocketMQMessage 的返回类型）
  - **不重命名**：§4.3 `MessageHandler` 接口、§5.6 `MessageView`（RocketMQ 类）等

- **#2 §7.3 RAG 索引 payload 类型修正**：
  - 设计 `new IndexTask(documentId, teamId)` → 实际 `new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId, teamId)`
  - consumer 调用 `etlService.processDocument(...)` → 实际 `etlDispatchService.dispatch(List.of(candidate))` + 触发 `EtlCompletedEvent`
  - 完整字段列出，让设计示例可直接复制运行

- **#3 Topic 名前缀约定文档化**：
  - 在 §5.12 添加 "Topic 命名约定" 说明：业务代码使用裸名（如 `rag_index_document`），
    `RocketMQMessageBus` 在 `send` / `subscribe` 内部自动拼接 `topicPrefix + topic`，
    运维脚本必须使用带前缀的全名（如 `SMART_RAG_rag_index_document`）
  - `app.messaging.ordered-topics` 配置同样使用裸名（与 `MessageEnvelope.topic()` 字段一致）
  - §6.1 / §5.7 引用此约定

- **#4+#5 enabled / max-delivery-attempts 配置语义说明**：
  - `app.messaging.enabled` 默认值 `false`（matchIfMissing=true 的 NoOp 路径），
    生产环境通过 `application-{profile}.yml` 或环境变量 `MESSAGING_ENABLED=true` 开启。
    §6.1 添加说明
  - `app.messaging.rocketmq.max-delivery-attempts`（yaml L80）**仅作文档参考**，
    PushConsumer 实际重试次数由 Broker 端消费组元数据 `maxDeliveryAttempts` 决定（通过 mqadmin 配置）。
    §4.6 / §5.12 添加说明，标注此字段"应用层不消费"

## Open Questions

（无）

## Implementation Plan (small PRs)

由于本任务只改设计文档，不分多 PR，按章节顺序一次性提交：

1. **§1.1 现状表**：删除 "进程内事件 = ApplicationEventPublisher" 行；新增 "Mode Strategy 流式响应（Flux）" 行，标注流式路径不走 ApplicationEvent
2. **§1.3 已有基础设施**：新增 LLM SPI 已统一（candidateId）、Mode Strategy 已落地、ChatUsageTracker 已成为用量记录中心化入口三条
3. **§7.1 聊天消息异步保存**：
   - 同步路径示例：`processResult` 内调 `messageBus.send(...)`
   - 流式路径示例：`MultiTurnModeStrategy.executeStream` 的 `doFinally` 内调 `messageBus.send(...)`
   - Consumer 示例：调 `ChatConversationHelper.saveMessagesAndNotify`（事务边界、双消息写入、`onNewMessages` 全保留）
   - 添加 "事务边界" 段落引用 DC-01
   - 添加 "Bus 失败同步降级" 段落，deduplicationKey 防双写说明
4. **§7.2 Token 用量异步记录**：
   - publisher 接入点改为 `ChatUsageTracker.recordUsage` 内部（替换 `usageService.recordUsage` 一行）
   - `modelId` 全部替换为 `candidateId`
   - consumer 示例：调 `UsageServiceImpl.recordUsage`
   - 保留 "非关键路径，失败仅记日志" 的现状（ChatUsageTracker 已 try/catch 吞咽）
5. **§9 Phase C 迁移步骤**：
   - 重写迁移步骤：先迁 usage（影响面小）→ 再迁 chat_message_save（流式 + 同步双路径）
   - 退出条件新增：① SSE 流式路径端到端验证 ② bus 不可用时同步降级路径验证 ③ legacy `MessageDeadLetterQueue` 7 天 0 新条目
6. **§9 Phase D 迁移步骤**：第一项前置条件改为 "Phase C 退出条件全部满足"
7. **§5.12 运维前置条件**：补充 `chat_message_save` / `chat_usage_record` Topic 与 `save-group` / `usage-group` 消费组创建脚本
8. **§10 风险评估**：新增 "流式 SSE 异步化导致客户端 UI 与消息落库时机错位" 条目（等级 低，缓解：UI 不依赖落库即可展示对话）；更新 "现有 DLQ 迁移期间消息丢失" 条目（现在关联 Phase C/D 依赖关系）

## Acceptance Criteria (final)

- [x] §1.1 不再出现 ApplicationEventPublisher 作为 chat 模块的异步机制
- [x] §1.3 提到 LLM SPI 统一 + candidateId + Mode Strategy + ChatUsageTracker
- [x] §7.1 同时提供同步（`processResult`）和流式（`MultiTurnModeStrategy.executeStream`）两个 publisher 接入点
- [x] §7.1 包含事务边界（DC-01 / sendAfterCommit）+ Bus 失败降级两段说明
- [x] §7.2 publisher 接入点明确为 `ChatUsageTracker.recordUsage`，`modelId` 全部替换为 `candidateId`
- [x] §9 Phase C 退出条件含：流式路径验证、降级路径验证、legacy DLQ 7 天 0 新条目
- [x] §9 Phase D 第一项明确前置条件为 Phase C 退出条件全部满足
- [x] §5.12 包含 chat_message_save / chat_usage_record / save-group / usage-group 初始化脚本片段
- [x] §10 新增 SSE 异步化风险条目，DLQ 迁移条目更新依赖关系
- [x] 设计文档全章节术语一致（无残留 `modelId` 在 Phase C 章节）

## Definition of Done

- 上述 9 条 Acceptance Criteria 全部勾选
- 设计文档本次修订章节内部一致，无遗留旧术语
- 修订不引入与 §1–6 已落地部分矛盾的内容
- PRD 中 Decisions 段落作为本次修订的 ADR-lite 留档

## Requirements (evolving)

- 修订 §7.1：增加流式接入点（`MultiTurnModeStrategy.executeStream` 的 `doFinally`），明确 publisher 与同步降级路径
- 修订 §7.2：publisher 接入点改为 `ChatUsageTracker.recordUsage()` 内部，`modelId` → `candidateId`
- 修订 §9 Phase C：更新迁移步骤与退出条件

## Acceptance Criteria (evolving)

- [ ] 设计文档中所有引用 `modelId` 的位置在 Phase C 章节统一为 `candidateId`
- [ ] §7.1 提供同步 + 流式两个 publisher 接入点的代码示例
- [ ] §7.2 ChatUsageTracker 改造方案明确指出替换行号或方法
- [ ] §9 Phase C 退出条件包含流式路径的端到端验证条目

## Definition of Done

- 设计文档本次修订章节内部一致，无遗留旧术语
- 修订不引入与 §1–6（已落地部分）矛盾的内容
- 更新顶部修订日期或 changelog（如有）

## Out of Scope (explicit)

- 不实现任何代码（Phase C 实现是后续任务）
- 不修改 §1–6（背景 / 设计目标 / 核心抽象 / RocketMQ 实现 / Spring 集成）—— 已落地部分不再变更
  - **例外（trellis-check 阶段追加）**：§5.2.1 异常码段位由 `400001–499999` 收敛为 `400001–400011`，`MessagingErrorCode` 枚举由 5 码补全为 11 码（400001–400011），对齐 `.trellis/spec/backend/error-handling.md` 与 `MessagingErrorCode.java` 实现。理由：文档段位过宽（原声称 4xxxxx 段专用于消息总线）且枚举落后于代码，属 trellis-check 定位的文档-实现脱节，顺手修正。
- 不修改 §8（改动文件清单）—— 等实现任务再同步
- 不评估 Phase D 相关章节（trace 实现完善、旧 DLQ 清理）

## Technical Notes

- 任务前驱：LLM SPI 统一（commit `a98fa9b` / `65c5fcf` / `e0533a6`）
- 任务前驱：消息总线 Phase A（SPI + RocketMQ 实现）+ Phase B（RAG 索引迁移）已落地
- 参考设计文档：`docs/design/messaging-bus.md`、`docs/design/chat-mode-strategy-step2-execute-sinking.md`
- 参考代码：
  - `chat/service/ChatConversationHelper.java:98` — `saveMessagesAndNotify`
  - `chat/service/ChatUsageTracker.java` — `recordUsage`（含 try/catch 吞咽）
  - `chat/mode/AbstractModeStrategy.java:34,50,70` — `execute` / `executeStream` / `recordUsage`
  - `chat/mode/MultiTurnModeStrategy.java:68,87,96` — `executeStream` + usage + save
  - `chat/service/impl/ChatServiceImpl.java:178,190` — `processResult` + save
