# DDD 领域驱动重构方案

> 目标：把当前按技术流程拆分的模块，重构为以领域边界为中心的分层结构。
>
> 范围：`com.smart.rag` 当前主工程；`infrastructure`、通用工具和第三方适配保留在基础设施层。
>
> 结论先行：`chat` 不是核心领域，应下沉为应用编排；`conversation`、`knowledge(RAG)`、`team`、`agent`、`identity` 才是应该承载领域规则的主边界。

---

## 1. 重构目标

### 1.1 现在的问题

当前代码已经按 `chat / conversation / rag / team / agent / evaluation` 分模块，但它们仍是“按功能堆叠”，不是“按领域建模”：

- `chat` 同时承载会话编排、模型参数、提示词、用量统计、策略路由
- `conversation` 已接近领域模型，但 `Conversation` 与 `Message` 的边界还不够清晰
- `rag` 里混杂了文档生命周期、ETL、分块、检索、存储、权限
- `agent` 同时包含会话、事件、追踪、工具、护栏、意图识别
- `team` 已有天然领域边界，但成员、审批、配额还可以再收敛

### 1.2 重构原则

1. **以聚合根约束不变式**，而不是以 service 方法堆逻辑。
2. **应用层只编排，不承载领域规则**。
3. **基础设施只做存储 / 外部系统适配**。
4. **所有跨聚合协作走应用服务或领域事件**。
5. **大集合（消息、事件、文档版本）不要塞进单一巨型聚合**。

---

## 2. 领域划分与聚合根

| 有界上下文 | 领域职责 | 建议聚合根 | 当前对应模块 |
|---|---|---|---|
| Identity & Access | 用户、角色、权限、授权 | `UserAccount` / `Role` / `Permission` | `user` + `security` |
| Conversation | 会话生命周期、消息树、标题、置顶、归档 | `Conversation` / `ConversationThread` | `conversation` |
| Knowledge / RAG | 文档上传、版本、状态、归属、索引 | `KnowledgeDocument` | `rag` |
| Team Collaboration | 团队、成员、配额、审批 | `Team` / `TeamMembership` / `TeamUploadApproval` | `team` |
| Agent Orchestration | Agent 会话、事件、恢复 | `AgentSession` | `agent` |
| Model Configuration | system prompt、模型参数 | `ModelProfile` | `chat` 的配置部分 |
| Usage Accounting | token / latency 记录与汇总 | `UsageLedger` | `chat` 的用量部分 |
| Evaluation | 数据集、运行、结果 | `EvaluationDataset` / `EvaluationRun` | `evaluation` |

### 2.1 核心判断

- **`chat` 不做聚合根承载**：它是应用编排层，负责调用 conversation / knowledge / agent / usage / modelconfig。
- **`TokenUsage` 不适合做强事务聚合根**：更像追加型账本或读模型。
- **`AgentTrace` 不适合做聚合根**：它是请求级汇总对象，真正可持久化的是 `AgentSession` + `AgentSessionEvent`。
- **`SystemPrompt` 与 `ModelParams` 适合合并为 `ModelProfile`**：它们都描述“某个模型如何被调用”。

---

## 3. 目标包结构树

```text
src/main/java/com/smart/rag/
├── interfaces/
│   └── http/
│       ├── auth/
│       ├── chat/
│       ├── conversation/
│       ├── knowledge/
│       ├── team/
│       ├── agent/
│       └── evaluation/
│
├── application/
│   ├── chat/
│   │   ├── service/
│   │   ├── strategy/
│   │   ├── dto/
│   │   └── assembler/
│   ├── identity/
│   │   ├── service/
│   │   ├── dto/
│   │   └── assembler/
│   ├── conversation/
│   │   ├── service/
│   │   ├── dto/
│   │   └── assembler/
│   ├── knowledge/
│   │   ├── service/
│   │   ├── etl/
│   │   ├── retrieval/
│   │   └── dto/
│   ├── team/
│   │   ├── service/
│   │   ├── dto/
│   │   └── assembler/
│   ├── agent/
│   │   ├── service/
│   │   ├── intent/
│   │   ├── tool/
│   │   └── dto/
│   ├── modelconfig/
│   │   ├── service/
│   │   └── dto/
│   ├── usage/
│   │   ├── service/
│   │   └── dto/
│   └── evaluation/
│       ├── service/
│       ├── dataset/
│       └── dto/
│
├── domain/
│   ├── shared/
│   │   ├── model/        # UserId / TeamId / ConversationId / DocumentId / ModelId
│   │   ├── event/
│   │   └── service/
│   ├── identity/
│   │   ├── model/
│   │   ├── repository/
│   │   ├── service/
│   │   └── event/
│   ├── conversation/
│   │   ├── model/
│   │   ├── repository/
│   │   ├── service/
│   │   └── event/
│   ├── knowledge/
│   │   ├── model/
│   │   ├── repository/
│   │   ├── service/
│   │   └── event/
│   ├── team/
│   │   ├── model/
│   │   ├── repository/
│   │   ├── service/
│   │   └── event/
│   ├── agent/
│   │   ├── model/
│   │   ├── repository/
│   │   ├── service/
│   │   └── event/
│   ├── modelconfig/
│   │   ├── model/
│   │   ├── repository/
│   │   └── service/
│   ├── usage/
│   │   ├── model/
│   │   ├── repository/
│   │   └── service/
│   └── evaluation/
│       ├── model/
│       ├── repository/
│       └── service/
│
└── infrastructure/
    ├── persistence/
    │   ├── mybatis/
    │   ├── query/
    │   └── mapper/
    ├── llm/
    ├── storage/
    │   └── minio/
    ├── security/
    ├── messaging/
    └── observability/
```

### 3.1 命名约定

- 聚合根：单数名词，如 `Conversation`、`Team`、`AgentSession`
- 仓储接口：`xxxRepository`
- 应用服务：`xxxApplicationService`
- 领域服务：只放“跨实体但不属于单个聚合”的规则
- 基础设施实现：`MybatisxxxRepository`、`MinioxxxStorage`、`RedisxxxGateway`

---

## 4. 聚合根方法清单

### 4.1 Identity & Access

#### `UserAccount`

方法清单：
- `register(...)`
- `updateProfile(...)`
- `changePassword(...)`
- `enable()`
- `disable()`
- `assignRole(roleId)`
- `revokeRole(roleId)`
- `hasRole(roleCode)`
- `isActive()`

建议不变式：
- 用户名唯一
- 禁用用户不能登录
- 密码修改必须校验旧密码

#### `Role`

方法清单：
- `create(...)`
- `rename(...)`
- `updateDescription(...)`
- `enable()`
- `disable()`
- `grantPermission(permissionId)`
- `revokePermission(permissionId)`
- `hasPermission(permissionCode)`

建议不变式：
- 角色名唯一
- 系统角色不可被误删

#### `Permission`

方法清单：
- `create(...)`
- `rename(...)`
- `updateResource(...)`
- `enable()`
- `disable()`

建议不变式：
- 权限名唯一
- `resourceType + resourceKey` 的语义必须稳定

---

### 4.2 Conversation

#### `Conversation`

方法清单：
- `open(userId, modelId, conversationId)`
- `rename(title, source)`
- `pin()`
- `unpin()`
- `archive()`
- `restore()`
- `delete()`
- `attachModel(modelId)`
- `incrementMessageCount(delta)`
- `touchLastMessageAt(time)`
- `markTitleByFirstUserMessage(content)`
- `ensureOwner(userId)`

建议不变式：
- 会话只属于一个用户
- 删除态会话不可继续写入
- 首条用户消息可触发自动标题

#### `ConversationThread`

方法清单：
- `appendUserMessage(...)`
- `appendAssistantMessage(...)`
- `appendBranchMessage(...)`
- `markMessageFailed(messageId, reason)`
- `rebuildTree()`
- `validateParentChain()`
- `removeByConversationId()`

建议不变式：
- 同一条消息只能挂在同一会话下
- parent 必须存在且属于同一会话
- 分支重生成不能破坏树结构

---

### 4.3 Knowledge / RAG

#### `KnowledgeDocument`

方法清单：
- `create(...)`
- `bindStorage(storageKey, bucket)`
- `startParsing()`
- `startChunking()`
- `startIndexing()`
- `markCompleted(chunkCount)`
- `markFailed(errorMessage)`
- `retryEtl()`
- `supersedeWith(newRevisionId)`
- `transferOwnership(scope)`
- `changeVisibility(scope)`

建议不变式：
- 同一逻辑文档的版本链要可追踪
- 处理中、已完成、失败、被替代状态不可互相跳转
- 归属（个人/团队）必须明确

> 说明：`Chunk`、`Vector`、`BM25`、`Parser`、`Loader` 更适合放在应用服务或基础设施层，不要并入 `KnowledgeDocument` 聚合。

---

### 4.4 Team Collaboration

#### `Team`

方法清单：
- `create(...)`
- `rename(...)`
- `changeDescription(...)`
- `setDefaultUploadLimit(limitMb)`
- `setCreatorUploadLimit(limitMb)`
- `activate()`
- `suspend()`
- `close()`

建议不变式：
- 团队名称唯一
- 团队停用后不能新增成员或新增文档

#### `TeamMembership`

方法清单：
- `join(teamId, userId, role)`
- `promote(role)`
- `demote(role)`
- `changeUploadLimit(limitMb)`
- `remove()`
- `reactivate()`
- `isOwner()`
- `isAdmin()`
- `isActive()`

建议不变式：
- 同一个用户在同一个团队内只能有一个有效成员态
- 成员角色变化必须符合团队规则

#### `TeamUploadApproval`

方法清单：
- `submit(teamId, documentId, uploaderId)`
- `approve(reviewerId, comment)`
- `reject(reviewerId, comment)`
- `cancel()`
- `expire()`

建议不变式：
- 审批只能在待审态流转
- 已审批记录不可二次审批

---

### 4.5 Agent Orchestration

#### `AgentSession`

方法清单：
- `start(userId, query, sessionId)`
- `classifyIntent(intent, confidence)`
- `appendRetrievalStrategy(strategy)`
- `appendToolCall(toolName, success, durationMs)`
- `appendIntermediateAnswer(...)`
- `appendSelfReflection(...)`
- `appendGuardrailEvent(...)`
- `markCompleted()`
- `markDegraded(reason)`
- `markFailed(reason)`
- `buildResumeSnapshot(maxBytes)`

建议不变式：
- 同一会话的事件必须保持顺序
- 护栏事件、意图事件必须优先保留
- 会话恢复只读，不反向修改历史

> 说明：`AgentTrace` 更像请求级汇总对象，不作为聚合根。

---

### 4.6 Model Configuration

#### `ModelProfile`

建议把 `SystemPrompt` + `ModelParams` 收敛为一个支持性聚合。

方法清单：
- `upsertSystemPrompt(text)`
- `updateSystemPrompt(text)`
- `upsertModelParams(...)`
- `mergeModelParams(...)`
- `resetPrompt()`
- `resetParams()`
- `enable()`
- `disable()`

建议不变式：
- 同一 `modelId` 只有一份有效配置
- system prompt 和 generation params 必须对齐同一个模型标识

---

### 4.7 Usage Accounting

#### `UsageLedger`

方法清单：
- `record(conversationId, modelId, promptTokens, completionTokens, durationMs)`
- `recordFallback(conversationId, modelId, reason)`
- `rollupByConversation(conversationId)`
- `rollupByModel(modelId)`
- `rollupByUser(userId)`

建议不变式：
- 记录是追加型，不做原地修改
- 汇总结果应来自只读查询或物化视图

---

### 4.8 Evaluation

#### `EvaluationDataset`

方法清单：
- `create(name, description)`
- `addItem(...)`
- `removeItem(itemId)`
- `updateItemStatus(itemId, status)`
- `exportSnapshot()`

#### `EvaluationRun`

方法清单：
- `start(datasetId, runner)`
- `markRunning()`
- `recordStageSnapshot(...)`
- `markFinished(metrics)`
- `markFailed(reason)`
- `cancel()`

建议不变式：
- 运行与数据集分离
- 运行结果应可复现

---

## 5. 旧类迁移映射表

| 当前类 / 包 | 目标位置 | 迁移层 | 备注 |
|---|---|---|---|
| `com.smart.rag.user.entity.SysUser` | `domain.identity.model.UserAccount` | Domain | 用户主体 |
| `com.smart.rag.user.entity.SysRole` | `domain.identity.model.Role` | Domain | 角色主体 |
| `com.smart.rag.user.entity.SysPermission` | `domain.identity.model.Permission` | Domain | 权限主体 |
| `com.smart.rag.user.entity.SysUserRole` | `domain.identity.model.UserRoleAssignment` | Domain | 关联关系，建议值对象化 |
| `com.smart.rag.user.entity.SysRolePermission` | `domain.identity.model.RolePermissionGrant` | Domain | 关联关系，建议值对象化 |
| `com.smart.rag.user.service.*` | `application.identity.*` | Application | 认证 / 用户 / 角色用例 |
| `com.smart.rag.user.controller.*` | `interfaces.http.identity.*` | Interface | REST 入参出参层 |
| `com.smart.rag.conversation.entity.Conversation` | `domain.conversation.model.Conversation` | Domain | 会话聚合根 |
| `com.smart.rag.conversation.entity.Message` | `domain.conversation.model.ConversationMessage` | Domain | 消息线程实体 |
| `com.smart.rag.conversation.service.*` | `application.conversation.*` | Application | 会话用例编排 |
| `com.smart.rag.conversation.controller.*` | `interfaces.http.conversation.*` | Interface | 会话接口 |
| `com.smart.rag.chat.entity.SystemPrompt` | `domain.modelconfig.model.SystemPrompt` | Domain | 可并入 `ModelProfile` |
| `com.smart.rag.chat.entity.ModelParams` | `domain.modelconfig.model.ModelParameters` | Domain | 可并入 `ModelProfile` |
| `com.smart.rag.chat.entity.TokenUsage` | `domain.usage.model.UsageRecord` | Domain | 追加型账本 |
| `com.smart.rag.chat.service.impl.ChatServiceImpl` | `application.chat.ChatApplicationService` | Application | 纯编排，不承载领域规则 |
| `com.smart.rag.chat.mode.*` | `application.chat.strategy.*` | Application | 策略/路由 |
| `com.smart.rag.chat.tool.*` | `application.chat.tool.*` 或 `infrastructure.llm.tool.*` | App/Infra | 取决于是否直接依赖 LLM |
| `com.smart.rag.rag.entity.RagDocument` | `domain.knowledge.model.KnowledgeDocument` | Domain | 文档聚合根 |
| `com.smart.rag.rag.service.impl.DocumentApplicationServiceImpl` | `application.knowledge.DocumentApplicationService` | Application | 文档用例编排 |
| `com.smart.rag.rag.service.impl.DocumentLifecycleService` | `application.knowledge.DocumentLifecycleService` | Application | 生命周期协作 |
| `com.smart.rag.rag.etl.*` | `application.knowledge.etl.*` | Application | ETL 流程 |
| `com.smart.rag.rag.parser.*` | `infrastructure.knowledge.parser.*` | Infrastructure | 文本解析实现 |
| `com.smart.rag.rag.retrieval.*` | `infrastructure.knowledge.retrieval.*` | Infrastructure | 检索实现 |
| `com.smart.rag.rag.chunk.*` | `application.knowledge.chunk.*` / `infrastructure.knowledge.chunk.*` | App/Infra | 分块策略视是否依赖业务语义而定 |
| `com.smart.rag.team.entity.Team` | `domain.team.model.Team` | Domain | 团队聚合根 |
| `com.smart.rag.team.entity.TeamMember` | `domain.team.model.TeamMembership` | Domain | 团队成员聚合 |
| `com.smart.rag.team.entity.TeamUploadApproval` | `domain.team.model.TeamUploadApproval` | Domain | 审批聚合 |
| `com.smart.rag.team.service.*` | `application.team.*` | Application | 团队用例编排 |
| `com.smart.rag.team.controller.*` | `interfaces.http.team.*` | Interface | 团队接口 |
| `com.smart.rag.agent.event.AgentSessionEvent` | `domain.agent.model.AgentSessionEvent` | Domain | 事件型实体 |
| `com.smart.rag.agent.event.AgentEventStore` | `infrastructure.persistence.agent.AgentEventStore` | Infrastructure | 事件持久化 |
| `com.smart.rag.agent.trace.AgentTrace` | `application.agent.AgentTraceSummary` | Application | 请求级汇总 |
| `com.smart.rag.agent.intent.*` | `application.agent.intent.*` | Application | 意图识别用例 |
| `com.smart.rag.agent.tool.*` | `application.agent.tool.*` / `infrastructure.agent.tool.*` | App/Infra | 工具是否纯逻辑决定落点 |
| `com.smart.rag.evaluation.*` | `domain.evaluation.*` + `application.evaluation.*` | Domain/App | 评测上下文 |
| `com.smart.rag.common.util.ConversationIdUtil` | `domain.shared.model.ConversationId` | Shared Kernel | 建议值对象化 |
| `com.smart.rag.common.util.UuidGeneratorUtil` | `infrastructure.shared.id.IdGenerator` | Infrastructure | 生成器实现 |

---

## 6. 迁移建议顺序

1. **先抽共享值对象**：`UserId` / `TeamId` / `ConversationId` / `DocumentId` / `ModelId`
2. **先落核心聚合**：`UserAccount`、`Conversation`、`KnowledgeDocument`、`Team`、`AgentSession`
3. **把 chat 改成应用层编排**：保留接口，移除领域规则
4. **把检索 / 解析 / 存储 / 事件持久化全部下沉到 infrastructure**
5. **最后再做配置、用量、评测等支持域收敛**

---

## 7. 本轮结论

- **核心域**：Conversation、Knowledge、Team、Agent、Identity
- **支持域**：ModelProfile、UsageLedger、Evaluation
- **应用层**：Chat 只是编排，不是核心领域
- **基础设施**：Parser / Retriever / LLM / Storage / Event Store 统一放回 infrastructure

如果后续继续推进，下一步应该补一份：

1. **领域对象 UML / 关系图**
2. **迁移阶段拆分（先包结构、后实体、再仓储）**
3. **数据库表重整建议**
