# PRD 审查报告：团队协作功能

> 审查人：洋（主会话 GLM） | 日期：2026-05-13 | 依据：trellis spec + ecc skills + MEMORY.md 开发原则

---

## P0 — 必须修复（违反已确立的规范/原则）

### P0-1：状态字段用 VARCHAR/INT 而非枚举 — 违反 quality-guidelines

**问题：** `team_member.role` 用 `VARCHAR(32)`，`team_member.status`、`team.status` 用 `INT`。  
**违反规范：** quality-guidelines 明确规定"状态字段：枚举类校验，禁裸 Integer"、"实体用枚举不用 String"（MEMORY.md）。项目现有约定：`@EnumValue` + `@JsonValue` 映射枚举（参考 `ConversationStatus`、`MessageStatus`、`UserStatus`）。  
**建议：** 数据库层保持 VARCHAR/INT 存储，但 Java Entity 必须使用枚举类型 + `@EnumValue` 注解。`TeamMemberRole`（CREATOR/ADMIN/MEMBER）、`TeamStatus`（ENABLED/DISABLED）、`ApprovalStatus`（PENDING/APPROVED/REJECTED）三个枚举类 PRD 已规划但未明确说明 Entity 映射方式。

---

### P0-2：唯一约束缺少 partial index（WHERE deleted = 0）— 违反 database-guidelines

**问题：** `team.team_name UNIQUE` 和 `uk_team_user UNIQUE (team_id, user_id)` 都没有加 `WHERE deleted = 0` 条件。  
**违反规范：** database-guidelines 明确规定"唯一约束：使用 partial unique index: WHERE deleted = 0"。  
**影响：**  
- 团队软删除后，同名团队无法重新创建
- 成员被移除后（软删除），无法重新加入同一团队（唯一约束冲突）  
**建议：** 改为 `UNIQUE (team_name) WHERE deleted = 0` 和 `UNIQUE (team_id, user_id) WHERE status = 1`。

---

### P0-3：创建团队缺少事务边界定义 — 违反 quality-guidelines

**问题：** §4.1.1 创建团队需写入 `team` + `team_member` 两张表，但未明确指定使用 `TransactionTemplate`。  
**违反规范：** quality-guidelines 强制"编程式事务：TransactionTemplate，不用 @Transactional"；MEMORY.md "多表写入必须用事务"。  
**影响：** 如果 `team_member` 插入失败，会出现孤立团队记录。  
**建议：** PRD 应在每个涉及多表写入的场景明确标注 `TransactionTemplate` 包裹（创建团队、解散团队、加入团队、审批通过/拒绝、移除成员）。

---

### P0-4：上传逻辑分叉违反 OCP — 违反 quality-guidelines + MEMORY.md

**问题：** §7.2 说在 `DocumentApplicationServiceImpl.upload()` 中"增加判断分支"（if teamId != null 走团队流程 else 走个人流程）。  
**违反规范：** MEMORY.md 开发原则"开闭原则（OCP）必须遵守：加功能 = 加新类，不是改旧类"；quality-guidelines "OCP 强制：新功能 = 新增类，不是改旧类"。  
**建议：** 使用**策略模式**重构上传链路：
```
UploadStrategy (接口)
├── PersonalUploadStrategy   — 现有个人上传逻辑，零改动
└── TeamUploadStrategy       — 团队上传逻辑（额度校验 + 审批）
```
`DocumentApplicationServiceImpl` 通过 `UploadStrategyFactory` 路由，根据 teamId 是否为空选择策略。现有代码不增加 if/else 分支。

---

### P0-5：解散团队标记为"不可逆"但使用逻辑删除 — 矛盾

**问题：** §4.1.3 说"这是不可逆操作，确认后执行"，但使用 `deleted` 字段逻辑删除。  
**问题本质：** 如果只是逻辑删除，团队数据仍在 DB 中，不是真正的不可逆。但如果要物理删除（含级联），需要物理删除 team + team_member + rag_document + MinIO 文件 + PGvector 向量，风险极大。  
**建议：** 二选一：
- 方案 A：逻辑删除 + 状态标记为 DISSOLVED，数据保留可审计，不是"不可逆"
- 方案 B：确认后物理删除所有关联数据，是真正的"不可逆"，需要二次确认 API（如先调用确认接口获取影响范围，再执行删除）

---

### P0-6：EtlStatus 缺少新增状态值 — 跨层数据流断裂

**问题：** 上传流程中 rag_document 的 status 使用了 `PENDING_APPROVAL` 和 `REJECTED`，但现有 `EtlStatus` 枚举只有 `UPLOADED / PARSING / CHUNKING / EMBEDDING / COMPLETED / FAILED / VECTOR_FAILED / PROCESSING`。  
**影响：** PRD 未列出修改 `EtlStatus.java` 这个改动点，§7.2 影响分析遗漏。  
**建议：** 在 §7.2 补充 `EtlStatus` 新增 `PENDING_APPROVAL` 和 `REJECTED` 两个枚举值。同时注意：现有的 `DocumentValidator`、ETL 状态机、`retry()` 方法都需要适配新状态。

---

### P0-7：团队文档 RAG 检索的 ChatRequest 变更未列入影响分析

**问题：** §4.5.3 说"RAG 聊天时，支持指定 teamId 参数"，这意味着 `ChatRequest` DTO 需要加 `teamId` 字段，`ChatService` / `ChatServiceImpl` / `ChatAdvisorChainFactory` / `RagAdvisorFactory` 都需要改动。但 §7.2 影响分析只提到 `RagAdvisorFactory`，遗漏了 `ChatRequest`、`ChatController`、`ChatServiceImpl`、`ChatAdvisorChainFactory`。  
**建议：** §7.2 补充完整的变更链路。

---

## P1 — 强烈建议（设计缺陷或遗漏）

### P1-1：权限校验逻辑重复 — 违反 code-reuse-thinking-guide

**问题：** `verifyTeamMember()` / `verifyTeamAdmin()` / `verifyTeamCreator()` 会在 `TeamServiceImpl`、`TeamMemberServiceImpl`、`TeamApprovalServiceImpl`、`TeamUploadQuotaServiceImpl` 中重复。  
**违反规范：** code-reuse-thinking-guide "Same code appears 3+ times → Abstract"。  
**建议：** 提取为 `TeamPermissionService`（或 `TeamMembershipVerifier`），所有 Service 注入此组件。符合 SRP + DRY。

---

### P1-2：审批并发冲突未处理

**问题：** 两个 ADMIN 可能同时对同一审批记录执行审批。`team_upload_approval` 的 `status` 从 PENDING → APPROVED/REJECTED 无并发保护。  
**违反规范：** MEMORY.md "check-then-act 要防并发：唯一约束兜底 + catch DuplicateKeyException 重查"（同类问题）。  
**建议：** 使用乐观锁：`UPDATE team_upload_approval SET status='APPROVED', ... WHERE id=? AND status='PENDING'`，影响行数为 0 时抛 `APPROVAL_ALREADY_PROCESSED`。

---

### P1-3：缺少团队规模限制

**问题：** 未限制单个团队的最大成员数，也未限制单个用户可加入的团队数。  
**影响：** 滥用风险——一个团队可以加入无限成员，一个用户可以创建/加入无限团队。  
**建议：** 配置化限制：`app.team.max-members-per-team`（默认 50）、`app.team.max-teams-per-user`（默认 10）。

---

### P1-4：成员列表和审批列表缺少分页设计

**问题：** §4.2.5 成员列表和 §4.4.1 待审批列表未指定分页参数。团队可能有很多成员和审批记录。  
**违反规范：** 项目已有 `PageRequest` + `PagedResult` 分页封装，所有列表接口应统一使用。  
**建议：** 明确分页参数（page/size），返回 `PagedResult<T>`。

---

### P1-5：用户如何获取 teamId 加入团队？— 功能链断裂

**问题：** §4.2.1 加入团队只需传 `teamId`，但未说明用户如何知道团队 ID。当前无团队搜索、无邀请链接、无团队发现机制。  
**建议：** 至少需要一个"获取团队信息"接口（按团队名搜索或按 teamId 查询基本信息），否则用户无法加入任何团队。或者本期增加简单的邀请码机制。

---

### P1-6：删除团队文档的权限判断缺少统一校验组件

**问题：** §4.5.2 说"CREATOR / ADMIN / 文档上传者本人"可删除，但现有 `DocumentApplicationServiceImpl.findAndVerifyOwner()` 只校验 `userId == doc.userId`。团队文档的删除权限校验逻辑更复杂（需要查团队成员角色 + 判断是否是文档上传者）。  
**建议：** 提取为 `DocumentDeletePermissionChecker`，统一处理个人/团队文档的删除权限判断。

---

### P1-7：团队文档的 ETL metadata 传递链路未设计

**问题：** §4.5.3 提到向量数据中携带 `teamId` metadata，但未说明 `teamId` 如何从上传请求传递到 ETL Pipeline 再到 `vector_store` 的 metadata 中。  
**涉及组件：** `EtlCandidate`（需加 teamId）、`VectorStoreLoader`（写入时需携带 metadata）、`RagAdvisorFactory`（检索时按 teamId 过滤）。  
**违反规范：** cross-layer-thinking-guide 要求"映射完整的跨层数据流"。  
**建议：** 在 PRD 中补充 teamId 在 ETL 全链路的传递路径图。

---

### P1-8：配置项命名不一致

**问题：** §11 配置前缀 `app.team.*`，但 `app.team.default-creator-upload-limit-mb` 命名风格与项目现有的 `app.document.*`、`app.rag.*`、`app.etl.*` 不一致（后者用 kebab-case 配置名，如 `query-rewrite-enabled`）。  
**建议：** 统一为 kebab-case：`app.team.default-creator-upload-limit-mb` → 没问题，但 `approval-timeout-days` 已经是 kebab-case，OK。注意 `@ConfigurationProperties` 的 prefix 应为 `app.team`。

---

## P2 — 建议优化

### P2-1：upload_limit_mb NULL 语义增加每次查询复杂度

**问题：** `team_member.upload_limit_mb` 允许 NULL（表示使用团队默认值），每次额度校验都需要额外查询 team 表获取默认值。  
**建议：** 在成员加入时直接将 `default_upload_limit_mb` 写入 `upload_limit_mb`，后续 CREATOR 修改团队默认值时不影响已有成员（或明确说明是否要级联更新）。消除 NULL 语义，简化查询。

---

### P2-2：缺少团队操作审计日志

**问题：** 团队的创建、解散、成员变动、角色变更等操作缺乏审计记录。  
**建议：** 至少在 INFO 级别记录关键操作（符合 logging-guidelines），格式：`log.info("Team member role changed: teamId={}, userId={}, oldRole={}, newRole={}, operator={}")`。

---

### P2-3：解散团队的异步清理缺少失败处理

**问题：** §4.1.3 说"异步清理 MinIO + PGvector"，但未说明异步失败怎么办。  
**建议：** 增加重试机制或标记为 PENDING_CLEANUP 状态，定时任务兜底清理。

---

### P2-4：团队禁用（status=0）的行为未定义

**问题：** `team.status` 支持 0=禁用，但 PRD 中没有定义禁用后的行为：成员能否查看文档？能否上传？审批是否暂停？  
**建议：** 明确禁用状态下的行为矩阵，或在第一期去掉禁用状态（只有启用和解散）。

---

### P2-5：ChatRequest 新增 teamId 字段对 SSE 流式接口的影响

**问题：** 现有 `ChatController.chatStreamGet()` 用 `@RequestParam` 接收参数，`ChatRequest` 是一个 record。如果 `ChatRequest` 加 `teamId`，GET 流式接口也需要加 `@RequestParam(defaultValue="false") String teamId` 或者改为 POST。  
**建议：** 考虑是否直接用 POST 流式接口来避免 GET 参数膨胀。

---

## 审查汇总

| 级别 | 数量 | 关键问题 |
|------|------|---------|
| **P0** | 7 | 枚举映射、partial unique index、事务边界、OCP 违反（上传分叉）、EtlStatus 遗漏、ChatRequest 变更链路遗漏 |
| **P1** | 8 | 权限校验重复、审批并发、团队规模限制、分页、加入链路断裂、删除权限、ETL metadata 传递 |
| **P2** | 5 | NULL 语义、审计日志、异步清理失败、禁用状态未定义、GET 流式接口参数 |

**结论：** PRD 整体方向正确（团队角色与系统 RBAC 正交分离、审批机制、额度控制），但存在 7 个 P0 级问题需要修复后才能进入开发。最关键的是 **P0-4 上传分叉违反 OCP**——建议用策略模式重构，避免在现有 Service 中加 if/else 分支。
