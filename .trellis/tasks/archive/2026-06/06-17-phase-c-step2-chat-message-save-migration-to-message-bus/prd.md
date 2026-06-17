# Phase C Step 2: Chat 消息保存迁移到消息总线（+ Phase 0: 移除 enabled/NoOp 开关）

## Goal
1. **Phase 0（前置清理）**：删除 `app.messaging.enabled` 配置开关与 `NoOpMessageBus`，消息总线改为 always-on（`RocketMQMessageBus` 无条件装配），让 app 真正连上已就绪的 Docker broker。
2. **Phase 1（Step 2 主体）**：将 chat 消息保存从同步 `saveMessagesAndNotify` 迁移到消息总线（`chat_message_save` topic），覆盖**同步路径**（`processResult`）+ **流式路径**（`executeStream.doFinally`）两处 publisher 接入点。

## 背景 / 代码现状（2026-06-17 核实）

### enabled 开关真相
- `application.yml` messaging 块（L65-81）**无 `enabled` 键**；`MessagingAutoConfiguration` 的 `@ConditionalOnProperty(havingValue="true")` **无** `matchIfMissing` → 属性缺失时真 bus 不加载；`NoOpMessagingConfiguration` 带 `matchIfMissing=true` → **NoOp 为运行时激活的 bus**。
- 全仓（application.yml / application-dev.yml / application-stable.yml / docker-compose.yml / bin/docker-compose.yml / 所有 .env*）**无任何地方设 enabled=true**。
- 设计文档 §5.12 L2051 checkbox 未勾选且与实际矛盾（stale）。

### broker / endpoints 真相
- `application.yml` L77：`rocketmq.endpoints: ${ROCKETMQ_ENDPOINTS:localhost:8081}` —— 默认指向 Docker broker 的 Proxy 端口（docker-compose `${ROCKETMQ_PROXY_PORT:-8081}:8081`）。**endpoints 已正确配置**。
- docker-compose 仅起基础设施（postgres/redis/minio/rmqnamesrv/rmqbroker/rmqdashboard），**无 app 服务**；app 本地运行，连 localhost:8081。
- **结论：Docker broker 已就绪、endpoints 已指向它，但 app 因 enabled 缺失全程跑在 NoOp 上 —— Phase A/B/C-Step1 的消息全部被静默丢弃。**

### 测试影响（删除 NoOp 的风险评估）
- 全仓 `@SpringBootTest` / `contextLoads` / `@DataJpaTest` / `@WebMvcTest` = **0**；单测全 mock `MessageBus`；IT 用 Testcontainers（真 broker）。
- `RocketMQMessageBus` 构造器 `provider.newProducerBuilder()...build()` 不强连 broker（lazy）；broker 未起时 app 仍可启动（send 时由 publisher fallback / 熔断兜底）。
- → 删除 NoOp 对测试套件与启动**零破坏**。

## Decisions（已定）

### Phase 0
- **P0-D1**：删除 `app.messaging.enabled` 属性；`MessagingAutoConfiguration` 去 `@ConditionalOnProperty` 变无条件 `@Configuration`；删除 `NoOpMessagingConfiguration` 内嵌类 + `NoOpMessageBus.java`。
- **P0-D2**：去掉三处 `@ConditionalOnProperty(enabled=true)` —— `UsageRecordConsumer`、`EtlDocumentConsumer`；去掉 `EtlDispatchServiceImpl` 的 `messagingEnabled` guard（始终走 bus）；清理 `ChatUsageTracker` / consumer javadoc 中 enabled/NoOp 描述。
- **P0-D3**：连带修订 Step 1 的 D1「usage 可丢」声明 —— bus always-on 后 usage 不再因 NoOp 丢失；保留「bus 运行期故障时 usage 经熔断/重试，非关键路径」表述。

### Phase 1（Step 2 主体）
- **P1-D1**：新增 `ChatMessagePublisher.publishMessageSave(...)`，发 `chat_message_save`（`MessageEnvelope.deduplicated`，key=`conversationId + ":" + md5(userMessage)`）；`send` 抛 `MessagingException` 时 catch 同步降级调 `saveMessagesAndNotify`（保留事务 / 双消息写入 / onNewMessages 全语义）。
- **P1-D2**：**不需要** enabled guard（Phase 0 后 bus always-on）—— publisher 只保留 `MessagingException → 同步降级` 一条兜底。
- **P1-D3**：`ChatMessagePayload` 含 `long totalTokens`（方案 A）；publisher 从 `aiResponse.getMetadata().getUsage()` null-safe 提取后传入；consumer 端 aiResponse=null / durationMs=0（totalTokens 来自 payload）。
- **P1-D4**：两接入点 —— `ChatServiceImpl.processResult()` L190-192、`MultiTurnModeStrategy.executeStream()` L96-98 `doFinally`。
- **P1-D5**：`ChatMessageSaveConsumer`（PUSH，group=`save-group`），内部调 `saveMessagesAndNotify`；落库失败重抛触发 broker 重试（参照 Step 1 `UsageRecordConsumer` 模式）。
- **P1-D6（Q2）**：dedup key `conversationId:md5(userMessage)` 对「同会话 15min 内连发相同消息」有误删边角风险 —— **PoC 接受**（DB 唯一约束 `(conversation_id, message_index)` 兜底），文档声明。

## Implementation Plan（先文档后代码）

**Phase 0**
1. 更新 `docs/design/messaging-bus.md`：移除 enabled/NoOp 相关（配置表、§5.12 checklist L2051、§7.x 中 enabled 条件、文件树 `NoOpMessageBus.java`、§9 Phase A 描述）；bus 标 always-on；修订 Step 1 D1。
2. 代码：`MessagingAutoConfiguration` 无条件化 + 删 `NoOpMessageBus` / `NoOpMessagingConfiguration`；去三处 `@ConditionalOnProperty`；去 `EtlDispatchServiceImpl.messagingEnabled`。
3. 测试：调整涉及 MessagingAutoConfiguration 条件加载的测试（若有 ApplicationContextRunner 断言）。

**Phase 1**
4. `ChatMessagePayload` record（含 totalTokens）。
5. `ChatMessagePublisher`（send + MessagingException 降级 + totalTokens 提取）。
6. `ChatMessageSaveConsumer`（参照 `UsageRecordConsumer`）。
7. 改两接入点（`processResult` + `executeStream.doFinally`）。
8. 单测：publisher（send 成功 / fallback 降级 / totalTokens 提取 null-safe）+ consumer（落库 / 重抛重试）。

## Acceptance Criteria
- [ ] `NoOpMessageBus` 删除；`MessagingAutoConfiguration` 无条件；全仓 `grep -rn 'app.messaging.enabled\|NoOpMessageBus'` = 0。
- [ ] app 启动后连上 Docker broker（localhost:8081），RocketMQ Dashboard（:8082）可见 `SMART_RAG_chat_usage_record` / `SMART_RAG_chat_message_save` topic 流量（usage 链路在 Phase 0 后首次真正跑通）。
- [ ] `processResult` + `executeStream` 两路径经 bus 落库；**同步路径 totalTokens 不丢**（payload 携带）。
- [ ] bus 运行期故障（stop broker）→ publisher catch 降级同步落库，无消息丢失；dedup key 防双写。
- [ ] 单测全绿（publisher send/fallback/totalTokens + consumer）。

## Out of Scope
- Phase C Step 3（legacy Redis `MessageDeadLetterQueue` 排空）、Phase D（删 `MessageDeadLetterQueue`）。
- Producer `.start()` fail-fast 行为改造（当前 lazy，保持）。
- 把 chat save 改成 `sendAfterCommit`（DC-01：两接入点非事务，用 `send()`，不变）。

## Technical Notes
- 前驱：Phase A/B + C-Step1 已落地，但运行时被 NoOp 屏蔽 —— Phase 0 修复后才真正生效。
- 编辑符号前需 `gitnexus_impact`（CLAUDE.md）；当前 index stale（停在 a5d40ef），Phase 0 前先 `npx gitnexus analyze`。
- 参考：`docs/design/messaging-bus.md` §7.1（publisher/consumer 接入点）、§9 Phase C Step 2、§4 `MessageEnvelope.deduplicated`。
- consumer 模式参考：`UsageRecordConsumer`（Step 1，SmartLifecycle + subscribe + 重抛重试）。
