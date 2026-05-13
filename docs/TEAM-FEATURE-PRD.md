# PRD：团队协作功能

> 版本：v1.1 | 日期：2026-05-13 | 状态：审查修订版
>
> 变更记录：v1.0 → v1.1 根据两份审查报告（GLM + DeepSeek）修订，修复 7 个 P0 + 8 个 P1 问题。

## 1. 背景与目标

### 1.1 背景

当前 chat-demo 的 RAG 文档管理是纯个人模式——每个用户只能上传、查看、删除自己的文档，文档知识库互相隔离。实际场景中，用户有团队协作需求：多人共享同一份知识库，共同维护团队文档空间。

### 1.2 目标

- 支持用户创建团队，邀请其他用户加入
- 团队拥有独立的文档空间，成员可上传文件到团队空间
- 引入审批机制：普通成员上传需管理员/创建者审批，管理员/创建者上传自动通过
- 支持上传额度控制：创建者可限制成员单次上传大小，创建者自身额度由系统管理员控制

### 1.3 非目标（本期不做）

- 团队聊天 / 团队会话
- 团队内权限精细到文档级别（如"仅创建者可删除"）
- 团队邀请链接 / 二维码
- 团队公告 / 通知
- 跨团队文档共享

---

## 2. 术语定义

| 术语 | 说明 |
|------|------|
| **系统角色** | `sys_role` 中的 ADMIN / USER，控制系统级功能权限（用户管理、角色管理等） |
| **团队角色** | `team_member.role` 枚举 `TeamMemberRole`（CREATOR / ADMIN / MEMBER），控制团队内操作权限 |
| **个人空间** | 用户私有的文档空间，即现有 RAG 上传（`rag_document.team_id = NULL`） |
| **团队空间** | 团队共享的文档空间（`rag_document.team_id = <团队ID>`） |
| **上传额度** | 单次上传请求的文件大小上限（MB） |

---

## 3. 角色模型

### 3.1 团队角色

| 角色 | 产生方式 | 权限 |
|------|---------|------|
| **CREATOR（创建者）** | 创建团队时自动产生，每团队仅 1 人 | 全部团队管理权限 + 上传免审批 + 设定成员额度 + 设定管理员 + 解散团队 |
| **ADMIN（管理员）** | 由 CREATOR 从 MEMBER 提拔 | 上传免审批 + 审批成员上传 + 管理团队文档（删除） |
| **MEMBER（成员）** | 加入团队时默认角色 | 上传需审批 + 查看团队文档 |

### 3.2 与系统角色的关系

团队角色和系统角色**完全正交**，互不影响：

- 一个系统 ADMIN 在团队中可以是 MEMBER
- 一个系统 USER 在团队中可以是 CREATOR
- 系统权限（`@PreAuthorize`）仍然基于系统角色和权限码
- 团队权限在 Service 层通过团队角色校验

```
系统角色（RBAC）        团队角色（team_member.role）
  ADMIN ──────────── 可以是任意 ──→ CREATOR / ADMIN / MEMBER
  USER  ──────────── 可以是任意 ──→ CREATOR / ADMIN / MEMBER
```

---

## 4. 功能需求

### 4.1 团队管理

#### 4.1.1 创建团队

- **操作者：** 任何已认证用户
- **前置校验：** 用户已加入的团队数 < `app.team.max-teams-per-user`（默认 10）
- **输入：** 团队名称（必填，≤128 字符，全局唯一）、团队描述（可选，≤512 字符）
- **处理（TransactionTemplate）：**
  1. 校验团队名称唯一性（依赖 partial unique index 兜底）
  2. 创建 `team` 记录
  3. 自动创建 `team_member` 记录（userId=创建者，role=CREATOR，upload_limit_mb=团队默认额度）
- **输出：** 团队信息（id, name, desc, creatorId, createdAt）
- **审计日志：** `log.info("Team created: teamId={}, teamName={}, creatorId={}")`
- **异常：** 团队名称已重复 → `TEAM_NAME_DUPLICATE`；团队数超限 → `TEAM_LIMIT_EXCEEDED`

#### 4.1.2 更新团队信息

- **操作者：** CREATOR
- **输入：** 团队名称（可选）、团队描述（可选）
- **处理：** 校验操作者是 CREATOR → 更新字段
- **异常：** 非创建者 → `NOT_TEAM_CREATOR`；名称重复 → `TEAM_NAME_DUPLICATE`

#### 4.1.3 解散团队

- **操作者：** CREATOR
- **处理（TransactionTemplate）：**
  1. 逻辑删除 `team` 记录
  2. 逻辑删除所有 `team_member` 记录（status=0）
  3. 逻辑删除所有 `rag_document` 中属于该团队的文档
  4. 异步清理 MinIO 中团队文件 + PGvector 中团队向量数据（失败时标记 PENDING_CLEANUP，定时任务兜底）
- **注意：** 解散后团队数据逻辑保留（可审计），不可恢复。异步清理失败由定时任务补偿重试。
- **审计日志：** `log.info("Team dissolved: teamId={}, memberCount={}, docCount={}, operatorId={}")`

#### 4.1.4 查看团队列表

- **操作者：** 任何已认证用户
- **输出：** 当前用户加入的所有团队（id, name, desc, memberCount, myRole, createdAt），分页

#### 4.1.5 查看团队详情

- **操作者：** 团队成员
- **输出：** 团队完整信息 + 当前用户角色 + 成员数 + 文档数 + 默认上传额度

#### 4.1.6 按名称搜索团队

- **操作者：** 任何已认证用户
- **输入：** keyword（团队名称模糊匹配）
- **输出：** 匹配的团队基本信息列表（id, name, desc, memberCount, creatorName）
- **说明：** 为"加入团队"提供发现能力，用户获取 teamId 后通过 §4.2.1 加入

### 4.2 成员管理

#### 4.2.1 加入团队

- **操作者：** 任何已认证用户
- **输入：** teamId
- **前置校验：**
  - 团队存在（`deleted = 0`）
  - 用户未加入该团队（`status = 1` 的记录不存在）
  - 团队成员数 < `app.team.max-members-per-team`（默认 50）
  - 用户已加入的团队数 < `app.team.max-teams-per-user`（默认 10）
- **处理：** 创建 `team_member`（role=MEMBER, upload_limit_mb=团队默认额度）
- **审计日志：** `log.info("Team member joined: teamId={}, userId={}, role=MEMBER")`
- **异常：** 已加入 → `ALREADY_TEAM_MEMBER`；团队不存在 → `TEAM_NOT_FOUND`；成员数超限 → `TEAM_MEMBER_LIMIT_EXCEEDED`

> 注：本期采用直接加入模式，无邀请/审批流程。后续版本可扩展为邀请码或申请审批。

#### 4.2.2 退出团队

- **操作者：** MEMBER / ADMIN
- **处理：**
  1. 校验操作者不是 CREATOR（创建者不能退出，只能解散）
  2. 更新 `team_member.status = 0`
  3. 该成员上传的团队文档**保留在团队空间**，不随成员退出而删除
- **审计日志：** `log.info("Team member left: teamId={}, userId={}")`
- **异常：** 创建者无法退出 → `CREATOR_CANNOT_LEAVE`

#### 4.2.3 移除成员

- **操作者：** CREATOR
- **输入：** teamId, targetUserId
- **处理：** 校验目标成员存在且 status=1 → 更新 `team_member.status = 0`
- **审计日志：** `log.info("Team member removed: teamId={}, targetUserId={}, operatorId={}")`

#### 4.2.4 提拔/取消管理员

- **操作者：** CREATOR
- **输入：** teamId, targetUserId, targetRole（ADMIN 或 MEMBER）
- **处理：** 校验目标成员存在且 status=1 → 更新 `team_member.role`
- **审计日志：** `log.info("Team member role changed: teamId={}, userId={}, oldRole={}, newRole={}, operatorId={}")`
- **异常：** 不能修改创建者角色 → `CANNOT_CHANGE_CREATOR_ROLE`

#### 4.2.5 查看成员列表

- **操作者：** 团队成员
- **输出：** 成员列表（userId, username, nickname, role, uploadLimitMb, joinedAt），分页（`PagedResult<T>`）

#### 4.2.6 设定成员上传额度

- **操作者：** CREATOR
- **输入：** teamId, targetUserId, uploadLimitMb（单位 MB，正整数）
- **处理：** 校验目标成员存在且 status=1 → 更新 `team_member.upload_limit_mb`
- **约束：** 额度不得大于创建者的 `creator_upload_limit_mb`，不得小于 1MB
- **异常：** 额度超出范围 → `UPLOAD_LIMIT_OUT_OF_RANGE`

### 4.3 团队文档上传

#### 4.3.1 上传架构 — 策略模式（OCP 合规）

上传逻辑通过策略模式路由，**不在现有 `DocumentApplicationServiceImpl` 中增加 if/else 分支**：

```
UploadStrategy (接口)
│   upload(MultipartFile file, Long teamId, Long userId)
│
├── PersonalUploadStrategy    — 现有个人上传逻辑（零改动）
│   └── 调用现有 DocumentValidator + MinIO + ETL 流程
│
└── TeamUploadStrategy        — 团队上传逻辑（新增）
    └── 额度校验 + 审批/免审批 + MinIO + 条件 ETL
```

- `UploadStrategyFactory` 根据 `teamId` 是否为 null 路由到对应策略
- `DocumentApplicationServiceImpl` 注入 `UploadStrategyFactory`，委托给策略，**不包含判断逻辑**
- 策略接口定义：

```java
public interface UploadStrategy {
    /** 处理上传并返回结果 */
    DocumentUploadResponse upload(MultipartFile file, Long teamId, Long userId);
    /** 批量上传 */
    List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, Long teamId, Long userId);
}
```

#### 4.3.2 上传流程（普通成员 — TeamUploadStrategy 内部）

```
成员 POST /api/documents/upload {file, teamId}
  │
  ├─ 1. 校验团队成员身份（TeamMembershipVerifier.verifyMember）
  ├─ 2. 校验上传额度：file.size ≤ member.upload_limit_mb
  ├─ 3. 文件校验（MIME 白名单 + 魔数，复用 DocumentValidator）
  ├─ 4. 文件存储到 MinIO
  ├─ 5. 创建 rag_document（team_id=teamId, status=PENDING_APPROVAL）  ← EtlStatus 新增值
  ├─ 6. 创建 team_upload_approval（status=PENDING）
  └─ 7. 返回 {documentId, status: PENDING_APPROVAL, message: "等待审批"}
```

- **不触发 ETL**，文件暂时只存在于 MinIO，未进入向量库

#### 4.3.3 上传流程（管理员 / 创建者 — TeamUploadStrategy 内部）

```
管理员/创建者 POST /api/documents/upload {file, teamId}
  │
  ├─ 1. 校验团队成员身份 + 角色为 ADMIN 或 CREATOR
  ├─ 2. 校验上传额度：
  │      ADMIN   → file.size ≤ member.upload_limit_mb
  │      CREATOR → file.size ≤ team.creator_upload_limit_mb
  ├─ 3. 文件校验（复用 DocumentValidator）
  ├─ 4. 文件存储到 MinIO
  ├─ 5. 创建 rag_document（team_id=teamId, status=UPLOADED）
  ├─ 6. 自动创建 team_upload_approval（status=APPROVED, reviewer_id=自己）
  └─ 7. 立即触发 ETL → 返回 {documentId, status: PROCESSING}
```

#### 4.3.4 分片上传支持

现有分片上传接口（`/api/documents/multipart`）同步支持 `teamId`：

- `init` 请求增加 `teamId` 字段
- `init` 阶段通过 `UploadStrategyFactory` 路由到对应策略，进行成员校验和额度校验（基于总文件大小）
- 合并完成后的逻辑与普通上传一致（审批 / 自动通过）

#### 4.3.5 团队创建者的上传额度

- 由系统管理员通过 API 设定：`PATCH /api/teams/{teamId}/creator-quota {maxUploadMb}`
- **权限要求：** `team:manage`（新增系统权限，仅 ADMIN 角色）
- 底层：更新 `team.creator_upload_limit_mb`
- 系统管理员也可设置团队的**默认成员额度**：`team.default_upload_limit_mb`

### 4.4 审批流程

#### 4.4.1 待审批列表

- **操作者：** CREATOR / ADMIN
- **输出：** 团队内所有 status=PENDING 的审批记录，分页（`PagedResult<T>`）
  - 包含：文档名、文件大小、上传者信息、上传时间

#### 4.4.2 审批操作

- **操作者：** CREATOR / ADMIN
- **输入：** approvalId, action（APPROVE / REJECT）, comment（可选）
- **处理（APPROVE，TransactionTemplate）：**
  1. 乐观锁更新：`UPDATE team_upload_approval SET status='APPROVED', reviewer_id=?, reviewed_at=NOW() WHERE id=? AND status='PENDING'`
  2. 影响行数为 0 → 抛 `APPROVAL_ALREADY_PROCESSED`（并发保护）
  3. 更新 `rag_document.status` → UPLOADED
  4. 触发 ETL（异步）
- **处理（REJECT，TransactionTemplate）：**
  1. 同样乐观锁更新
  2. 更新 `rag_document.status` → REJECTED
  3. 删除 MinIO 中的文件
  4. 保留 rag_document 记录（标记已拒绝），用于历史查询
- **审计日志：** `log.info("Approval {}: approvalId={}, teamId={}, reviewerId={}, uploaderId={}")`
- **异常：** 非管理员 → `NOT_TEAM_ADMIN`；审批不存在 → `APPROVAL_NOT_FOUND`；已处理 → `APPROVAL_ALREADY_PROCESSED`

#### 4.4.3 审批超时

- 定时任务（每天 1 次）：扫描 `created_at < NOW() - INTERVAL '7 days'` 且 status=PENDING 的记录
- 自动拒绝 + 删除 MinIO 文件
- 配置化：`app.team.approval-timeout-days`，默认 7

#### 4.4.4 我的审批记录

- **操作者：** 团队成员（查看自己提交的审批）
- **输出：** 当前用户在指定团队的所有审批记录（PENDING / APPROVED / REJECTED），分页

### 4.5 团队文档管理

#### 4.5.1 查看团队文档列表

- **操作者：** 团队成员
- **输出：** 团队空间内所有文档（status 为 UPLOADED / PROCESSING / COMPLETED 的），分页
- **不含：** status=PENDING_APPROVAL / REJECTED 的文档（这些在审批列表中查看）

#### 4.5.2 删除团队文档

- **操作者：** CREATOR / ADMIN / 文档上传者本人
- **权限校验：** 由 `DocumentDeletePermissionChecker` 统一处理个人/团队文档的删除权限判断
  - 个人文档：`userId == doc.userId`（现有逻辑）
  - 团队文档：团队成员 + (角色为 CREATOR/ADMIN 或 userId == doc.userId)
- **处理：** 复用现有 `DocumentLifecycleService.cascadeDelete()`，清理 MinIO + PGvector + rag_document
- **异常：** 非上述角色 → `NO_PERMISSION_DELETE_TEAM_DOC`

#### 4.5.3 团队文档的 RAG 检索

- 团队文档完成 ETL 后，向量数据中携带 `teamId` metadata
- RAG 聊天时，支持指定 `teamId` 参数：
  - `teamId = null` → 检索个人文档（现有逻辑不变）
  - `teamId = xxx` → 检索团队文档（`FilterExpressionBuilder.eq("teamId", teamId)`）
- **权限校验：** 检索前通过 `TeamMembershipVerifier` 验证请求者是团队成员

---

## 5. 数据库设计

### 5.1 新增表

#### team（团队表）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| id | BIGINT | PK, GENERATED ALWAYS AS IDENTITY | 团队 ID |
| team_name | VARCHAR(128) | NOT NULL | 团队名称 |
| team_desc | VARCHAR(512) | | 团队描述 |
| creator_id | BIGINT | NOT NULL, FK → sys_user(id) | 创建者 |
| default_upload_limit_mb | BIGINT | DEFAULT 50 | 默认成员上传额度（MB） |
| creator_upload_limit_mb | BIGINT | DEFAULT 200 | 创建者上传额度（MB），系统管理员设定 |
| status | SMALLINT | NOT NULL DEFAULT 1 | `@EnumValue` 映射 `TeamStatus`：1=ENABLED, 0=DISABLED |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| deleted | SMALLINT | NOT NULL DEFAULT 0 | 逻辑删除，`@TableLogic` |

唯一约束（partial index）：`CREATE UNIQUE INDEX uk_team_name_active ON team (team_name) WHERE deleted = 0`
索引：`idx_team_creator(creator_id)`

#### team_member（团队成员表）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| id | BIGINT | PK, GENERATED ALWAYS AS IDENTITY | |
| team_id | BIGINT | NOT NULL, FK → team(id) | 所属团队 |
| user_id | BIGINT | NOT NULL, FK → sys_user(id) | 用户 |
| role | SMALLINT | NOT NULL DEFAULT 10 | `@EnumValue` 映射 `TeamMemberRole`：CREATOR(30) / ADMIN(20) / MEMBER(10) |
| upload_limit_mb | BIGINT NOT NULL | 单次上传额度（MB），加入时写入团队默认值，不再使用 NULL 语义 |
| status | SMALLINT | NOT NULL DEFAULT 1 | 1=正常 0=已移除。**不使用 `@TableLogic`**（同一用户可重复加入），查询时显式 `WHERE status = 1` |
| joined_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |

唯一约束（partial index）：`CREATE UNIQUE INDEX uk_team_user_active ON team_member (team_id, user_id) WHERE status = 1`
索引：`idx_team_member_user(user_id, status)`

> **设计说明：** `team_member` 不使用 `@TableLogic`（`deleted`），因为同一用户被移除后可能重新加入。使用 `status` 字段 + partial unique index `WHERE status = 1` 保证活跃成员唯一性。所有 Mapper 查询必须显式包含 `WHERE status = 1`。

#### team_upload_approval（团队上传审批表）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| id | BIGINT | PK, GENERATED ALWAYS AS IDENTITY | |
| team_id | BIGINT | NOT NULL, FK → team(id) | 所属团队 |
| document_id | BIGINT | NOT NULL, FK → rag_document(id) | 关联文档 |
| uploader_id | BIGINT | NOT NULL, FK → sys_user(id) | 上传者 |
| status | SMALLINT | NOT NULL DEFAULT 0 | `@EnumValue` 映射 `ApprovalStatus`：PENDING(0) / APPROVED(1) / REJECTED(2) |
| reviewer_id | BIGINT | FK → sys_user(id) | 审批人 |
| review_comment | VARCHAR(512) | | 审批备注 |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| reviewed_at | TIMESTAMPTZ | | |

索引：`idx_approval_team_status(team_id, status)`、`idx_approval_uploader(uploader_id)`

### 5.2 修改表

#### rag_document（加字段）

| 列 | 类型 | 说明 |
|----|------|------|
| team_id | BIGINT, 新增, DEFAULT NULL | 所属团队，NULL=个人文档 |

索引：`idx_rag_document_team(team_id)`

### 5.3 EtlStatus 枚举扩展

现有 `EtlStatus` 枚举新增两个值：

| 新增枚举 | 值 | 说明 |
|---------|-----|------|
| `PENDING_APPROVAL` | `"PENDING_APPROVAL"` | 等待审批（团队文档专用） |
| `REJECTED` | `"REJECTED"` | 审批拒绝 |

### 5.4 Flyway 迁移

- **V7__add_team.sql** — 建表 + 修改字段 + 索引 + 初始权限数据
- 向 `sys_permission` 插入 `team:view`、`team:manage` 权限
- 将 `team:manage` 绑定到 ADMIN 角色
- 迁移脚本必须幂等（`IF NOT EXISTS`、`NOT EXISTS` 子查询）

---

## 6. API 设计

### 6.1 团队管理 — `/api/teams`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/teams` | isAuthenticated | 创建团队 |
| GET | `/api/teams` | isAuthenticated | 我加入的团队列表（分页） |
| GET | `/api/teams/search` | isAuthenticated | 按名称搜索团队（keyword 参数） |
| GET | `/api/teams/{teamId}` | 团队成员 | 团队详情 |
| PATCH | `/api/teams/{teamId}` | CREATOR | 更新团队信息 |
| DELETE | `/api/teams/{teamId}` | CREATOR | 解散团队 |
| PATCH | `/api/teams/{teamId}/creator-quota` | `team:manage`（系统ADMIN） | 设置创建者额度 |
| PATCH | `/api/teams/{teamId}/default-quota` | `team:manage`（系统ADMIN） | 设置默认成员额度 |

### 6.2 成员管理 — `/api/teams/{teamId}/members`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/teams/{teamId}/members` | 团队成员 | 成员列表（分页） |
| POST | `/api/teams/{teamId}/members` | isAuthenticated | 加入团队 |
| DELETE | `/api/teams/{teamId}/members/{userId}` | CREATOR / 本人 | 移除成员 / 退出团队 |
| PATCH | `/api/teams/{teamId}/members/{userId}/role` | CREATOR | 变更成员角色 |
| PATCH | `/api/teams/{teamId}/members/{userId}/upload-limit` | CREATOR | 设定成员上传额度 |

### 6.3 审批管理 — `/api/teams/{teamId}/approvals`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/teams/{teamId}/approvals` | CREATOR / ADMIN | 待审批列表（分页） |
| GET | `/api/teams/{teamId}/approvals/mine` | 团队成员 | 我的审批记录（分页） |
| PATCH | `/api/teams/{teamId}/approvals/{approvalId}` | CREATOR / ADMIN | 审批操作（通过/拒绝） |

### 6.4 文档接口变更

现有 `/api/documents` 接口增加 `teamId` 支持：

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/documents/upload` | 新增可选参数 `teamId`，通过 `UploadStrategyFactory` 路由 |
| POST | `/api/documents/upload/batch` | 新增可选参数 `teamId`，同上 |
| GET | `/api/documents` | 返回个人文档 + 团队文档（标注 teamId） |
| GET | `/api/teams/{teamId}/documents` | 团队文档列表（新增，分页） |
| DELETE | `/api/documents/{id}` | 团队文档通过 `DocumentDeletePermissionChecker` 校验 |

分片上传接口同步变更：

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/documents/multipart` | init 请求增加 `teamId` 字段 |

### 6.5 ChatRequest 变更（RAG 团队检索）

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/chat` | `ChatRequest` 新增可选字段 `teamId` |
| POST | `/api/chat/stream` | `ChatRequest` 新增可选字段 `teamId` |
| GET | `/api/chat/stream` | 新增可选参数 `teamId` |

`teamId` 传递链路：`ChatRequest` → `ChatServiceImpl` → `ChatAdvisorChainFactory.buildChain()` → `RagAdvisorFactory.create(userId, teamId)` → `FilterExpressionBuilder.eq("teamId", teamId)`

---

## 7. 对现有模块的影响分析

### 7.1 无需改动的模块

| 模块 | 原因 |
|------|------|
| RBAC（sys_role / sys_permission / sys_user_role / sys_role_permission） | 团队角色和系统角色正交 |
| JWT / SecurityConfig / JwtAuthenticationFilter | 系统权限不变，团队权限在业务层校验 |
| Conversation 模块 | 会话不涉及团队 |
| Provider / ModelRouter | 模型路由不涉及团队 |
| Tool Calling / Sandbox | 不涉及团队 |
| TokenCacheService / CaptchaService | 不涉及团队 |
| AuthService | 不涉及团队（登录/注册不受影响） |

### 7.2 需要改动的模块（完整变更清单）

| 模块 | 改动内容 | OCP 合规 | 影响范围 |
|------|---------|---------|---------|
| **新增 `team/` 模块** | Entity / Enum / Mapper / Service / Controller 全新代码 | ✅ 零改旧代码 | 全新 |
| **EtlStatus** | 新增 `PENDING_APPROVAL`、`REJECTED` 枚举值 | ⚠️ 枚举扩展 | 现有 ETL 状态机需适配新值（`retry()` 方法需排除 PENDING_APPROVAL） |
| **ErrorCode** | 新增团队错误码（55xxx 段） | ⚠️ 枚举扩展 | 仅新增 |
| **RagDocument** | 加 `teamId` 字段 | ⚠️ 实体扩展 | 向后兼容（NULL） |
| **DocumentApplicationServiceImpl** | 注入 `UploadStrategyFactory`，委托给策略 | ✅ 不加 if/else | 原有个人上传逻辑封装到 `PersonalUploadStrategy`，零改动 |
| **DocumentController** | 上传接口增加可选 `teamId` 参数 | ⚠️ 参数扩展 | 向后兼容 |
| **ChunkUploadController / ChunkUploadServiceImpl** | init 请求增加 `teamId`，通过策略路由 | ⚠️ 参数扩展 | 向后兼容 |
| **ChatRequest** | 新增可选字段 `teamId` | ⚠️ DTO 扩展 | 向后兼容 |
| **ChatController** | GET 流式接口增加可选 `teamId` 参数 | ⚠️ 参数扩展 | 向后兼容 |
| **ChatServiceImpl** | 传递 `teamId` 到 `ChatAdvisorChainFactory` | ⚠️ 参数传递 | 向后兼容 |
| **ChatAdvisorChainFactory** | `buildChain()` 方法增加 `teamId` 参数，传递到 `RagAdvisorFactory` | ⚠️ 方法签名扩展 | 向后兼容 |
| **RagAdvisorFactory** | `create()` 方法增加 `teamId` 参数，构建 `teamId` 过滤条件 | ⚠️ 方法签名扩展 | 向后兼容 |
| **EtlCandidate** | 新增 `teamId` 字段 | ⚠️ record 扩展 | 向后兼容 |
| **VectorStoreLoader** | 写入时携带 `teamId` metadata | ⚠️ 扩展 | 向后兼容 |
| **DocumentProperties** | 新增团队审批超时等配置 | ⚠️ 新增字段 | 仅新增 |
| **DocumentLifecycleService** | 删除团队文档时增加 `DocumentDeletePermissionChecker` 判断 | ⚠️ 扩展 | 向后兼容 |

---

## 8. 新增模块结构

```
src/main/java/com/demo/chat/team/
├── entity/
│   ├── Team.java
│   ├── TeamMember.java
│   └── TeamUploadApproval.java
├── enums/
│   ├── TeamMemberRole.java         # CREATOR(30) / ADMIN(20) / MEMBER(10) — @EnumValue + @JsonValue
│   ├── TeamStatus.java             # ENABLED(1) / DISABLED(0) — @EnumValue + @JsonValue
│   └── ApprovalStatus.java         # PENDING(0) / APPROVED(1) / REJECTED(2) — @EnumValue + @JsonValue
├── mapper/
│   ├── TeamMapper.java + XML
│   ├── TeamMemberMapper.java + XML
│   └── TeamUploadApprovalMapper.java + XML
├── service/
│   ├── TeamService.java            # 团队 CRUD
│   ├── TeamMemberService.java      # 成员管理
│   ├── TeamApprovalService.java    # 审批流程
│   ├── TeamUploadQuotaService.java # 上传额度校验
│   └── TeamMembershipVerifier.java # 团队身份校验（统一组件，消除重复）
├── service/impl/
│   ├── TeamServiceImpl.java
│   ├── TeamMemberServiceImpl.java
│   ├── TeamApprovalServiceImpl.java
│   └── TeamUploadQuotaServiceImpl.java
├── dto/
│   ├── TeamCreateRequest.java
│   ├── TeamUpdateRequest.java
│   ├── TeamVO.java
│   ├── TeamDetailVO.java
│   ├── TeamMemberVO.java
│   ├── MemberRoleUpdateRequest.java
│   ├── MemberUploadLimitRequest.java
│   ├── CreatorQuotaRequest.java
│   ├── ApprovalVO.java
│   ├── ApprovalReviewRequest.java
│   └── MyApprovalVO.java
├── controller/
│   ├── TeamController.java         # /api/teams
│   ├── TeamMemberController.java   # /api/teams/{teamId}/members
│   └── TeamApprovalController.java # /api/teams/{teamId}/approvals
├── upload/                          # 上传策略（OCP 合规）
│   ├── UploadStrategy.java         # 策略接口
│   ├── PersonalUploadStrategy.java # 个人上传（封装现有逻辑）
│   ├── TeamUploadStrategy.java     # 团队上传（额度校验 + 审批）
│   └── UploadStrategyFactory.java  # 策略工厂（根据 teamId 路由）
├── security/
│   └── DocumentDeletePermissionChecker.java  # 统一删除权限校验
└── job/
    └── ApprovalTimeoutJob.java     # 审批超时自动拒绝
```

---

## 9. 团队权限校验方式

团队权限**不使用 Spring Security `@PreAuthorize`**，在 Service 层校验。原因：

- 团队权限是**关系型权限**（"我是不是这个团队的成员"、"我在团队里是什么角色"），不是固定的权限码
- 同一个用户在不同团队中角色不同，无法用静态权限表达
- 需要在方法参数中传入 `teamId`，`@PreAuthorize` 的 SpEL 表达力不足

### 统一校验组件：TeamMembershipVerifier

所有团队权限校验委托给 `TeamMembershipVerifier`（SRP + DRY，避免在多个 Service 中重复校验逻辑）：

```java
@Component
public class TeamMembershipVerifier {

    /**
     * 校验团队成员身份，返回成员记录
     * 包含：团队存在 + deleted=0 + 成员存在 + status=1
     */
    public TeamMember verifyMember(Long teamId, Long userId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null || team.getDeleted() == 1) throw TEAM_NOT_FOUND;
        TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        if (member == null || member.getStatus() != 1) throw NOT_TEAM_MEMBER;
        return member;
    }

    /** 校验管理员或创建者 */
    public TeamMember verifyAdmin(Long teamId, Long userId) {
        TeamMember member = verifyMember(teamId, userId);
        if (member.getRole() != TeamMemberRole.CREATOR
                && member.getRole() != TeamMemberRole.ADMIN) {
            throw NOT_TEAM_ADMIN;
        }
        return member;
    }

    /** 校验创建者 */
    public TeamMember verifyCreator(Long teamId, Long userId) {
        TeamMember member = verifyMember(teamId, userId);
        if (member.getRole() != TeamMemberRole.CREATOR) {
            throw NOT_TEAM_CREATOR;
        }
        return member;
    }
}
```

---

## 10. 错误码规划

错误码使用 **55xxx 段**，避免与现有 RAG 错误码（50xxx）冲突。

| 枚举值 | 编码 | HTTP 状态码 | 说明 |
|--------|------|------------|------|
| `TEAM_NOT_FOUND` | 55001 | 404 | 团队不存在 |
| `TEAM_NAME_DUPLICATE` | 55002 | 400 | 团队名称已存在 |
| `NOT_TEAM_MEMBER` | 55003 | 403 | 不是团队成员 |
| `NOT_TEAM_ADMIN` | 55004 | 403 | 不是团队管理员/创建者 |
| `NOT_TEAM_CREATOR` | 55005 | 403 | 不是团队创建者 |
| `ALREADY_TEAM_MEMBER` | 55006 | 400 | 已经是团队成员 |
| `CREATOR_CANNOT_LEAVE` | 55007 | 400 | 创建者不能退出团队 |
| `CANNOT_CHANGE_CREATOR_ROLE` | 55008 | 400 | 不能修改创建者角色 |
| `UPLOAD_QUOTA_EXCEEDED` | 55009 | 400 | 上传文件超出额度 |
| `UPLOAD_LIMIT_OUT_OF_RANGE` | 55010 | 400 | 上传额度设置超出范围 |
| `APPROVAL_NOT_FOUND` | 55011 | 404 | 审批记录不存在 |
| `APPROVAL_ALREADY_PROCESSED` | 55012 | 400 | 审批已处理 |
| `NO_PERMISSION_DELETE_TEAM_DOC` | 55013 | 403 | 无权删除团队文档 |
| `TEAM_LIMIT_EXCEEDED` | 55014 | 400 | 用户团队数超限 |
| `TEAM_MEMBER_LIMIT_EXCEEDED` | 55015 | 400 | 团队成员数超限 |

`ErrorCode.java` 注释更新：`团队 55xxx` 段说明。

---

## 11. 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.team.approval-timeout-days` | `7` | 审批超时天数，超时自动拒绝 |
| `app.team.default-creator-upload-limit-mb` | `200` | 新建团队时创建者的默认上传额度 |
| `app.team.default-member-upload-limit-mb` | `50` | 新成员加入时的默认上传额度 |
| `app.team.max-members-per-team` | `50` | 单个团队最大成员数 |
| `app.team.max-teams-per-user` | `10` | 单个用户最大加入团队数 |

---

## 12. 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| 审批积压导致 MinIO 存储膨胀 | 存储成本 | 7 天超时自动拒绝 + 删除文件 |
| 团队成员变动后权限不一致 | 安全 | 团队权限实时查询 DB，不缓存 |
| 团队文档 RAG 检索跨团队泄露 | 数据安全 | 检索时强制 `FilterExpressionBuilder.eq("teamId", teamId)` + `TeamMembershipVerifier` 校验 |
| 一人多团队上传时 teamId 传错 | 数据错乱 | 上传时校验 teamId 对应的成员身份 |
| 现有个人文档功能回归 | 功能损坏 | 策略模式隔离 + teamId 可选 + 个人上传逻辑零改动 |
| 审批并发冲突 | 数据不一致 | 乐观锁 `WHERE status='PENDING'` 保证原子性 |
| 解散团队异步清理失败 | 孤立文件 | PENDING_CLEANUP 状态 + 定时任务补偿 |
| `team_member` 查询遗漏 status 过滤 | 数据泄露 | Mapper XML 中所有查询强制 `WHERE status = 1`；代码审查重点检查 |

---

## 13. 新增同类功能的步骤（OCP 验证）

| 步骤 | 操作 | 是否改动旧代码 |
|------|------|--------------|
| 1. 新增 Flyway V7 迁移 | 建表 + 改字段 + 新增权限数据 | ❌ 不改旧迁移 |
| 2. 新增 `team/` 模块全部代码 | Entity / Enum / Mapper / Service / Controller | ❌ 全新文件 |
| 3. 新增 `UploadStrategy` 策略体系 | 接口 + PersonalUploadStrategy + TeamUploadStrategy + Factory | ❌ 全新文件 |
| 4. `DocumentApplicationServiceImpl` 注入 Factory | 委托给策略，**不增加 if/else** | ⚠️ 注入点变更（不修改原有逻辑） |
| 5. `RagDocument` 加 `teamId` 字段 | 实体新增字段 | ⚠️ 向后兼容 |
| 6. `EtlStatus` 加枚举值 | 新增 PENDING_APPROVAL / REJECTED | ⚠️ 枚举扩展 |
| 7. `ErrorCode` 加 55xxx 段 | 新增枚举值 | ⚠️ 枚举扩展 |
| 8. `ChatRequest` 加 `teamId` | 新增可选字段 | ⚠️ DTO 扩展 |
| 9. `ChatServiceImpl` → `RagAdvisorFactory` 传递 teamId | 参数传递链 | ⚠️ 参数透传 |
| 10. `EtlCandidate` 加 `teamId` | record 新增字段 | ⚠️ 向后兼容 |

**结论：** 所有对现有代码的改动均为**向后兼容的扩展**（新增可选字段、新增参数、委托给策略），不修改原有逻辑。上传链路通过策略模式实现 OCP 合规。

---

## 14. 工作量估算

| 部分 | 预估 |
|------|------|
| Flyway V7 迁移 + 权限数据 | 1h |
| team 模块 Entity + Enum | 1.5h |
| team 模块 Mapper + XML | 2h |
| TeamMembershipVerifier（统一校验） | 1h |
| TeamService + TeamMemberService + Impl | 3h |
| TeamApprovalService + TeamUploadQuotaService + Impl | 3h |
| 审批超时定时任务 | 1h |
| UploadStrategy 策略体系 | 3h |
| DocumentDeletePermissionChecker | 1h |
| DTO（~11 个 record） | 1.5h |
| Controller（3 个） | 2h |
| ChatRequest → RAG 检索 teamId 传递链路 | 2h |
| 分片上传支持 teamId | 2h |
| EtlStatus + EtlCandidate 适配 | 1h |
| ErrorCode 55xxx 段 | 0.5h |
| 配置项 + DocumentProperties 扩展 | 0.5h |
| 单元测试 | 5h |
| 文档更新（README / 架构文档 / 数据库文档） | 1.5h |
| **合计** | **~34h** |
