# PRD：团队协作功能

> 版本：v1.2 | 日期：2026-05-13 | 状态：审查修订版
>
> 变更记录：
> - v1.0 → v1.1：根据规范审查（GLM + DeepSeek）修订 7P0 + 8P1
> - v1.1 → v1.2：根据架构深度审查（GLM + DeepSeek）补充 6 项嵌入方案，覆盖所有集成点

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
| **隔离维度** | 向量检索/BM25 的过滤字段。个人空间按 `userId` 隔离，团队空间按 `teamId` 隔离 |

---

## 3. 角色模型

### 3.1 团队角色

| 角色 | 产生方式 | 权限 |
|------|---------|------|
| **CREATOR（创建者）** | 创建团队时自动产生，每团队仅 1 人 | 全部团队管理权限 + 上传免审批 + 设定成员额度 + 设定管理员 + 解散团队 |
| **ADMIN（管理员）** | 由 CREATOR 从 MEMBER 提拔 | 上传免审批 + 审批成员上传 + 管理团队文档（删除/重试） |
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
  1. **SELECT ... FOR UPDATE** 锁定 `team` 行，防止并发操作
  2. 批量更新所有 status=PENDING 的 `team_upload_approval` 为 REJECTED（防止审批与解散并发）
  3. 逻辑删除 `team` 记录
  4. 逻辑删除所有 `team_member` 记录（status=0）
  5. 逻辑删除所有 `rag_document` 中属于该团队的文档
  6. 异步清理 MinIO 中团队文件 + PGvector 中团队向量数据（失败时标记，定时任务兜底）
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

#### 4.2.2 退出团队

- **操作者：** MEMBER / ADMIN
- **处理：**
  1. 校验操作者不是 CREATOR（创建者不能退出，只能解散）
  2. 更新 `team_member.status = 0`
  3. 该成员上传的团队文档**保留在团队空间**
  4. 该成员的 PENDING 审批记录保持不变（仍可被其他管理员/创建者审批）
- **审计日志：** `log.info("Team member left: teamId={}, userId={}")`

#### 4.2.3 移除成员

- **操作者：** CREATOR
- **输入：** teamId, targetUserId
- **处理：**
  1. 校验目标成员存在且 status=1 → 更新 `team_member.status = 0`
  2. 该成员的 PENDING 审批记录保持不变
- **审计日志：** `log.info("Team member removed: teamId={}, targetUserId={}, operatorId={}")`

#### 4.2.4 提拔/取消管理员

- **操作者：** CREATOR
- **输入：** teamId, targetUserId, targetRole（ADMIN 或 MEMBER）
- **处理：** 校验目标成员存在且 status=1 → 更新 `team_member.role`
- **审计日志：** `log.info("Team member role changed: teamId={}, userId={}, newRole={}, operatorId={}")`
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
│   upload(MultipartFile file, @Nullable Long teamId, Long userId)
│
├── PersonalUploadStrategy    — 现有个人上传逻辑（封装）
│   └── 调用现有 DocumentValidator + MinIO + ETL 流程
│
└── TeamUploadStrategy        — 团队上传逻辑（新增）
    └── 成员校验 + 额度校验 + 审批/免审批 + MinIO + 条件 ETL
```

**关键设计约束：**

1. `PersonalUploadStrategy` 封装现有 `DocumentApplicationServiceImpl.upload()` / `uploadBatch()` 的**全部逻辑**，包括 `persistDocument()` 等私有方法
2. 封装完成后，`DocumentApplicationServiceImpl.upload()` 改为纯委托：
   ```java
   public DocumentUploadResponse upload(MultipartFile file) {
       return uploadStrategyFactory.route(null).upload(file, null, SecurityUtils.getCurrentUserId());
   }
   ```
3. **迁移前必须先写集成测试**覆盖：单文件上传、批量上传、秒传、续传、所有 MIME 类型
4. `UploadStrategyFactory` 根据 `teamId` 是否为 null 路由到对应策略

策略接口定义：

```java
public interface UploadStrategy {
    DocumentUploadResponse upload(MultipartFile file, @Nullable Long teamId, Long userId);
    List<DocumentUploadResponse> uploadBatch(List<MultipartFile> files, @Nullable Long teamId, Long userId);
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
  ├─ 5. 创建 rag_document（team_id=teamId, status=PENDING_APPROVAL）
  ├─ 6. 创建 team_upload_approval（status=PENDING）
  └─ 7. 返回 {documentId, status: PENDING_APPROVAL, message: "等待审批"}
```

- **不触发 ETL**，文件暂时只存在于 MinIO

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
  └─ 7. 立即触发 ETL（携带 teamId）→ 返回 {documentId, status: PROCESSING}
```

#### 4.3.4 分片上传 teamId 穿透方案

现有 `ChunkUploadServiceImpl` 的分片上传链路需要多处改造：

**Redis Session 扩展：**
- `ChunkUploadInitRequest` 新增 `@Nullable Long teamId` 字段
- `init()` 阶段将 `teamId` 存入 Redis session hash
- 同时存入 `status`：团队普通成员 = `PENDING_APPROVAL`，团队管理员/创建者 = `PROCESSING`，个人 = `PROCESSING`

**`init()` 阶段改造：**
- 有 teamId 时：通过 `TeamMembershipVerifier` 校验成员身份 + 查询额度
- 调用 `validateFileSize()` 时传入团队额度（而非硬编码 50MB）

**`performMerge()` 阶段改造：**
- 从 Redis session 读取 `teamId` 和 `status`
- `persistDocument()` 根据 session 中的 status 设置 `rag_document.status`：
  - 团队普通成员 → `PENDING_APPROVAL`，同时创建审批记录，**不触发 ETL**
  - 其他 → `PROCESSING`，触发 ETL（携带 teamId）
- MinIO 合并后的 objectKey 已持久化到 `rag_document`，审批通过后可直接读取

**`complete()` 阶段二次校验：**
- 合并完成后再次校验成员身份（防止上传过程中被移除）
- 校验失败：清理已合并的 MinIO 文件 + 删除 rag_document 记录 + 返回 `NOT_TEAM_MEMBER`

**秒传 teamId 隔离：**
- `findExistingForQuickUpload()` 查询条件增加 teamId 过滤：
  - 个人上传（teamId=null）只匹配 `team_id IS NULL` 的文档
  - 团队上传（teamId≠null）只匹配 `team_id = teamId` 的文档

#### 4.3.5 团队创建者的上传额度

- 由系统管理员通过 API 设定：`PATCH /api/teams/{teamId}/creator-quota {maxUploadMb}`
- **权限要求：** `team:manage`（新增系统权限，仅 ADMIN 角色）
- 底层：更新 `team.creator_upload_limit_mb`
- 系统管理员也可设置团队的**默认成员额度**：`team.default_upload_limit_mb`

### 4.4 审批流程

#### 4.4.1 待审批列表

- **操作者：** CREATOR / ADMIN
- **输出：** 团队内所有 status=PENDING 的审批记录，分页（`PagedResult<T>`）

#### 4.4.2 审批操作

- **操作者：** CREATOR / ADMIN
- **输入：** approvalId, action（APPROVE / REJECT）, comment（可选）
- **处理（APPROVE，TransactionTemplate）：**
  1. 乐观锁更新：`UPDATE ... SET status='APPROVED' WHERE id=? AND status='PENDING'`
  2. 影响行数为 0 → 抛 `APPROVAL_ALREADY_PROCESSED`（并发保护）
  3. 校验团队未被解散（`team.deleted = 0`）
  4. 更新 `rag_document.status` → UPLOADED
  5. 触发 ETL（从 `rag_document` 读取 bucket/storageKey/teamId）
- **处理（REJECT，TransactionTemplate）：**
  1. 同样乐观锁更新
  2. 更新 `rag_document.status` → REJECTED
  3. 删除 MinIO 中的文件
- **审计日志：** `log.info("Approval {}: approvalId={}, teamId={}, reviewerId={}")`

#### 4.4.3 审批超时

- 定时任务（每天 1 次）：扫描超时的 PENDING 记录
- 自动拒绝 + 删除 MinIO 文件
- 配置化：`app.team.approval-timeout-days`，默认 7

#### 4.4.4 我的审批记录

- **操作者：** 团队成员（查看自己提交的审批）
- **输出：** 当前用户在指定团队的所有审批记录，分页

### 4.5 团队文档管理

#### 4.5.1 查看团队文档列表

- **操作者：** 团队成员
- **输出：** 团队空间内所有文档（status 为 UPLOADED / PROCESSING / COMPLETED 的），分页

#### 4.5.2 删除团队文档

- **操作者：** CREATOR / ADMIN / 文档上传者本人
- **权限校验：** 由 `DocumentOwnershipChecker` 统一处理（替代现有 `findAndVerifyOwner()`）：
  - teamId=null（个人文档）：`userId == doc.userId`
  - teamId≠null（团队文档）：团队成员 + (角色为 CREATOR/ADMIN 或 userId == doc.userId)

#### 4.5.3 重试团队文档

- **操作者：** CREATOR / ADMIN / 文档上传者本人
- **权限校验：** 复用 `DocumentOwnershipChecker`（同删除权限）
- **处理：** 清理旧向量 → 从 `rag_document` 读取 teamId → 重新触发 ETL
- **异常：** 文档状态不是 FAILED / VECTOR_FAILED → `BAD_REQUEST`

#### 4.5.4 团队文档的 RAG 检索

- 团队文档完成 ETL 后，向量数据中携带 `teamId` metadata
- RAG 聊天时，支持指定 `teamId` 参数：
  - `teamId = null` → 按 `userId` 隔离检索个人文档（现有逻辑不变）
  - `teamId = xxx` → 按 `teamId` 隔离检索团队文档
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
| creator_upload_limit_mb | BIGINT | DEFAULT 200 | 创建者上传额度（MB） |
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
| upload_limit_mb | BIGINT NOT NULL | 单次上传额度（MB），加入时写入团队默认值 |
| status | SMALLINT | NOT NULL DEFAULT 1 | 1=正常 0=已移除。**不使用 `@TableLogic`** |
| joined_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |

唯一约束（partial index）：`CREATE UNIQUE INDEX uk_team_user_active ON team_member (team_id, user_id) WHERE status = 1`
索引：`idx_team_member_user(user_id, status)`

> **设计说明：** `team_member` 不使用 `@TableLogic`，所有 Mapper 查询必须显式包含 `WHERE status = 1`。

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

| 新增枚举 | 值 | 说明 |
|---------|-----|------|
| `PENDING_APPROVAL` | `"PENDING_APPROVAL"` | 等待审批（ETL 前状态，不参与 ETL 状态机流转） |
| `REJECTED` | `"REJECTED"` | 审批拒绝 |

**状态机守卫：** ETL 重试逻辑（如有定时任务扫描 FAILED 文档）必须在查询条件中排除 `PENDING_APPROVAL` / `REJECTED`。这两个状态是"ETL 前状态"，不参与 `UPLOADED → PARSING → ... → COMPLETED` 的状态转换链。

### 5.4 Flyway 迁移

- **V7__add_team.sql** — 建表 + 修改字段 + 索引 + 初始权限数据 + **清空现有向量数据**
- **清空向量数据：** `TRUNCATE TABLE vector_store;` — 项目仍在开发阶段，不保留旧向量
- V7 迁移脚本必须幂等（`IF NOT EXISTS`、`NOT EXISTS` 子查询）

---

## 6. API 设计

### 6.1 团队管理 — `/api/teams`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/teams` | isAuthenticated | 创建团队 |
| GET | `/api/teams` | isAuthenticated | 我加入的团队列表（分页） |
| GET | `/api/teams/search` | isAuthenticated | 按名称搜索团队 |
| GET | `/api/teams/{teamId}` | 团队成员 | 团队详情 |
| PATCH | `/api/teams/{teamId}` | CREATOR | 更新团队信息 |
| DELETE | `/api/teams/{teamId}` | CREATOR | 解散团队 |
| PATCH | `/api/teams/{teamId}/creator-quota` | `team:manage` | 设置创建者额度 |
| PATCH | `/api/teams/{teamId}/default-quota` | `team:manage` | 设置默认成员额度 |

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
| PATCH | `/api/teams/{teamId}/approvals/{approvalId}` | CREATOR / ADMIN | 审批操作 |

### 6.4 文档接口变更

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/documents/upload` | 新增可选参数 `teamId`，通过 `UploadStrategyFactory` 路由 |
| POST | `/api/documents/upload/batch` | 新增可选参数 `teamId`，同上 |
| GET | `/api/documents` | 返回个人文档 + 团队文档（标注 teamId），查询逻辑改造见 §8.4 |
| GET | `/api/teams/{teamId}/documents` | 团队文档列表（新增，分页） |
| DELETE | `/api/documents/{id}` | 通过 `DocumentOwnershipChecker` 统一权限校验 |
| POST | `/api/documents/{id}/retry` | 通过 `DocumentOwnershipChecker` 统一权限校验 |

分片上传接口变更：

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/documents/multipart` | init 请求增加 `teamId` 字段 |

### 6.5 ChatRequest 变更（RAG 团队检索）

`ChatRequest` record 新增 `Long teamId` 可选字段：

```java
public record ChatRequest(
    @NotBlank String model,
    @NotBlank @Size(max = 10000) String message,
    @Size(max = 100) @Pattern(regexp = "^[a-zA-Z0-9_-]*$") String conversationId,
    Boolean ragEnabled,
    @Pattern(regexp = "^(SIMPLE|MULTI_TURN)$") String mode,
    Boolean enableThinking,
    Long teamId  // 新增：团队 ID，用于 RAG 团队文档检索
) {
    // withModel() 同步更新：
    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, message, conversationId, ragEnabled, mode, enableThinking, teamId);
    }
}
```

**Jackson 兼容：** Java record 新增可选字段（`Long` 引用类型，默认 null）在 Jackson 2.16+ 下可正常反序列化——旧客户端 JSON 无此字段时自动填充 null。但需验证当前项目使用的 Jackson 版本，如不兼容则加 `@JsonInclude(JsonInclude.Include.NON_NULL)` + `@JsonProperty(access = Access.WRITE_ONLY)` 防止序列化泄露。

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/chat` | `ChatRequest` 新增 `teamId` 字段（JSON body） |
| POST | `/api/chat/stream` | `ChatRequest` 新增 `teamId` 字段 |
| GET | `/api/chat/stream` | 新增可选 query param `teamId` |

`teamId` 传递链路：
```
ChatRequest.teamId
  → ChatServiceImpl.chat()/chatStream()
    → ChatAdvisorChainFactory.buildChain(conversationId, request, modeStrategy)
      → request.teamId() 传入 ragAdvisorFactory
        → RagAdvisorFactory.create(userId, teamId)
          → 按 teamId 构建 FilterExpression（见 §8.3）
```

---

## 7. 对现有模块的影响分析

### 7.1 无需改动的模块

| 模块 | 原因 |
|------|------|
| RBAC（sys_role / sys_permission / sys_user_role / sys_role_permission） | 团队角色和系统角色正交 |
| JWT / SecurityConfig / JwtAuthenticationFilter | 系统权限不变 |
| Conversation 模块 | 会话不涉及团队 |
| Provider / ModelRouter | 模型路由不涉及团队 |
| Tool Calling / Sandbox | 不涉及团队 |
| TokenCacheService / CaptchaService | 不涉及团队 |
| AuthService | 不涉及团队 |

### 7.2 需要改动的模块（完整逐文件清单）

#### 团队模块（全新代码）

| 文件 | 说明 |
|------|------|
| `team/entity/Team.java` | 团队实体 |
| `team/entity/TeamMember.java` | 成员实体 |
| `team/entity/TeamUploadApproval.java` | 审批实体 |
| `team/enums/TeamMemberRole.java` | CREATOR(30)/ADMIN(20)/MEMBER(10) |
| `team/enums/TeamStatus.java` | ENABLED(1)/DISABLED(0) |
| `team/enums/ApprovalStatus.java` | PENDING(0)/APPROVED(1)/REJECTED(2) |
| `team/mapper/*.java + XML` | 3 个 Mapper |
| `team/service/*.java` | 4 个 Service 接口 |
| `team/service/impl/*.java` | 4 个实现 |
| `team/service/TeamMembershipVerifier.java` | 统一团队身份校验组件 |
| `team/dto/*.java` | ~11 个 DTO |
| `team/controller/*.java` | 3 个 Controller |
| `team/upload/UploadStrategy.java` | 策略接口 |
| `team/upload/PersonalUploadStrategy.java` | 个人上传（封装现有逻辑） |
| `team/upload/TeamUploadStrategy.java` | 团队上传 |
| `team/upload/UploadStrategyFactory.java` | 策略工厂 |
| `team/security/DocumentOwnershipChecker.java` | 统一文档权限校验（替代 findAndVerifyOwner） |
| `team/job/ApprovalTimeoutJob.java` | 审批超时定时任务 |

#### 现有代码改动（逐文件）

| 文件 | 改动内容 | 改动类型 |
|------|---------|---------|
| **EtlCandidate.java** | record 新增 `@Nullable Long teamId` 字段（末尾参数） | record 扩展 |
| **EtlStatus.java** | 新增 `PENDING_APPROVAL` / `REJECTED` 枚举值 | 枚举扩展 |
| **ErrorCode.java** | 新增 55xxx 段团队错误码 | 枚举扩展 |
| **RagDocument.java** | 加 `@Nullable Long teamId` 字段 | 实体扩展 |
| **DocumentApplicationServiceImpl.java** | `upload()`/`uploadBatch()` 改为委托 `UploadStrategyFactory`；`delete()` 改用 `DocumentOwnershipChecker`；`retry()` 改用 `DocumentOwnershipChecker`；`listAll()` 改用自定义 SQL；`getById()` 改用 `DocumentOwnershipChecker`；所有 `new EtlCandidate(...)` 末尾加 `doc.getTeamId()` | **重构** |
| **DocumentController.java** | 上传接口增加可选 `teamId` 参数 | 参数扩展 |
| **ChunkUploadServiceImpl.java** | `init()` 接受 teamId + 团队校验 + 额度校验；`validateFileSize()` 参数化限额；`persistDocument()` 条件状态；`performMerge()` 条件 ETL；`complete()` 二次校验成员；`findExistingForQuickUpload()` 加 teamId 隔离 | **重构** |
| **ChunkUploadInitRequest.java** | 新增 `@Nullable Long teamId` 字段 | DTO 扩展 |
| **ChatRequest.java** | 新增 `Long teamId` 字段；更新 `withModel()` | record 扩展 |
| **ChatController.java** | GET 流式接口增加可选 `teamId` query param | 参数扩展 |
| **ChatServiceImpl.java** | `chat()`/`chatStream()` 传递 `request.teamId()` 给 `ChatAdvisorChainFactory` | 参数透传 |
| **ChatAdvisorChainFactory.java** | `buildChain()` 从 `request.teamId()` 取值传给 `ragAdvisorFactory.create(userId, teamId)` | 参数透传 |
| **RagAdvisorFactory.java** | `create(Long userId)` → `create(Long userId, @Nullable Long teamId)`；废弃 `advisorCache`（每次构建新实例）；`createUserIsolatedRetriever()` 签名增加 teamId；构建复合 filter | **重构**（见 §8.3） |
| **HybridDocumentRetriever.java** | 构造函数 `Long userId` 改为 `String isolationField, String isolationValue`；BM25 SQL 动态拼接过滤字段 | **重构**（见 §8.3） |
| **EtlDispatchServiceImpl.java** | `executeSingle()`/`dispatchAsync()` 签名增加 `@Nullable Long teamId`；`new EtlCandidate(...)` 末尾加 teamId | 签名扩展 |
| **StandardStrategy.java** | metadata 注入增加 `if (teamId != null) chunk.getMetadata().put("teamId", ...)` | 条件扩展 |
| **FastTrackStrategy.java** | `writeBm25Row()` 签名增加 teamId，metadata 写入 teamId；`asyncVectorize()` metadata 增加 teamId；`new EtlCandidate(...)` 末尾加 teamId | 签名扩展 |
| **DocumentProperties.java** | 新增团队审批超时等配置 | 新增字段 |
| **DocumentDTO.java** | 新增 `Long teamId` 字段 | DTO 扩展 |
| **V7__add_team.sql** | 建表 + 改字段 + 索引 + 权限数据 + TRUNCATE vector_store | 迁移脚本 |

---

## 8. 关键嵌入方案

### 8.1 `findAndVerifyOwner()` 拆分方案

**现有问题：** `DocumentApplicationServiceImpl` 中的 `findAndVerifyOwner(Long id)` 方法只检查 `userId == doc.userId`，不适用于团队文档（管理员/创建者也应有权限）。

**方案：** 提取为 `DocumentOwnershipChecker` 统一组件：

```java
@Component
public class DocumentOwnershipChecker {
    
    private final RagDocumentMapper ragDocumentMapper;
    private final TeamMembershipVerifier teamMembershipVerifier;

    /**
     * 校验文档操作权限（getById / delete / retry 通用）
     * 
     * @return 文档实体（校验通过）
     * @throws BUSINESS_EXCEPTION 权限不足
     */
    public RagDocument checkOwnership(Long documentId, Long userId) {
        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) throw DOCUMENT_NOT_FOUND;
        
        if (doc.getTeamId() == null) {
            // 个人文档：只有文档所有者
            if (!userId.equals(doc.getUserId())) throw DOCUMENT_OWNERSHIP_DENIED;
        } else {
            // 团队文档：成员 + (CREATOR/ADMIN 或 文档上传者)
            TeamMember member = teamMembershipVerifier.verifyMember(doc.getTeamId(), userId);
            boolean isManager = member.getRole() == TeamMemberRole.CREATOR 
                             || member.getRole() == TeamMemberRole.ADMIN;
            boolean isUploader = userId.equals(doc.getUserId());
            if (!isManager && !isUploader) throw NO_PERMISSION_DELETE_TEAM_DOC;
        }
        return doc;
    }
}
```

**替换点：** `DocumentApplicationServiceImpl` 中的 `findAndVerifyOwner()` 全部替换为 `documentOwnershipChecker.checkOwnership()`。

### 8.2 ETL 管道改造清单

#### EtlCandidate record 扩展

```java
public record EtlCandidate(
    Long documentId,
    String bucket,
    String objectKey,
    String fileName,
    String mimeType,
    long fileSize,
    Long userId,
    @Nullable Long teamId  // 新增
) {}
```

**所有构造点更新（共 7 处）：**

| 文件 | 方法 | 改动 |
|------|------|------|
| `DocumentApplicationServiceImpl` | `upload()` | 末尾加 `doc.getTeamId()` |
| `DocumentApplicationServiceImpl` | `uploadBatch()` | 末尾加 `doc.getTeamId()` |
| `EtlDispatchServiceImpl` | `executeSingle()` | 签名加 `@Nullable Long teamId`，末尾加 `teamId` |
| `EtlDispatchServiceImpl` | `dispatchAsync()` | 签名加 `@Nullable Long teamId`，末尾加 `teamId` |
| `ChunkUploadServiceImpl` | `performMerge()` | 从 rag_document 或 session 读取 teamId，传给 dispatchAsync |

#### Metadata 写入扩展（3 处）

| 文件 | 位置 | 改动 |
|------|------|------|
| `StandardStrategy.execute()` | chunk metadata 循环 | `if (c.teamId() != null) chunk.getMetadata().put("teamId", String.valueOf(c.teamId()))` |
| `FastTrackStrategy.execute()` | writeBm25Row 调用前 | 签名加 teamId，metadata 包含 teamId |
| `FastTrackStrategy.asyncVectorize()` | chunk metadata 循环 | 同 StandardStrategy |

#### ETL 重试守卫

如有 ETL 重试定时任务，查询条件必须排除 PENDING_APPROVAL / REJECTED：
```sql
WHERE status IN ('FAILED', 'VECTOR_FAILED')
  AND team_id IS NULL OR team_id IS NOT NULL  -- 保留全部
  -- PENDING_APPROVAL / REJECTED 不在此列表中，不会被误触
```

### 8.3 RagAdvisorFactory 改造方案

**问题：** 现有 `advisorCache(userId → Advisor)` 无法表达 teamId 维度。

**方案：废弃缓存，每次构建新实例。**

理由：
- Advisor 是轻量级对象，持有 VectorStore 引用（共享 Bean）+ filter expression（纯数据）
- 构建开销 ~0.1ms，远低于一次向量检索（10-50ms）
- 缓存的复杂性（复合 key、容量限制、失效策略）不值得为这点开销承担

**RagAdvisorFactory 改造：**

```java
@Component
public class RagAdvisorFactory {
    // 删除 advisorCache 字段

    /**
     * 为指定用户/团队创建 RAG Advisor
     * 每次请求创建新实例（轻量级，不缓存）
     */
    public RetrievalAugmentationAdvisor create(Long userId, @Nullable Long teamId) {
        return buildAdvisor(userId, teamId);
    }

    private RetrievalAugmentationAdvisor buildAdvisor(Long userId, @Nullable Long teamId) {
        DocumentRetriever retriever = createRetriever(userId, teamId);
        // ... 后处理器链不变
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .documentPostProcessors(getPostProcessors())
                .queryTransformers(queryTransformers)
                .build();
    }

    private DocumentRetriever createRetriever(Long userId, @Nullable Long teamId) {
        // 隔离维度参数化
        String isolationField = teamId != null ? "teamId" : "userId";
        String isolationValue = String.valueOf(teamId != null ? teamId : userId);

        if (properties.isHybridRetrievalEnabled()) {
            return new HybridDocumentRetriever(vectorStore, jdbcTemplate, properties,
                    queryNormalizer, isolationField, isolationValue, objectMapper);
        }

        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        var filter = filterBuilder.eq(isolationField, isolationValue).build();
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(properties.getSimilarityThreshold())
                .topK(properties.getVectorTopK())
                .filterExpression(filter)
                .build();
    }
}
```

**HybridDocumentRetriever 改造：**

```java
public class HybridDocumentRetriever implements DocumentRetriever {
    private final String isolationField;   // "userId" 或 "teamId"
    private final String isolationValue;   // String.valueOf(userId) 或 String.valueOf(teamId)

    // 构造函数改为 (..., String isolationField, String isolationValue, ...)

    // BM25 SQL 动态拼接：
    // WHERE content_tsv @@ plainto_tsquery(?, ?)
    //   AND metadata->>? = ?
    // 参数：ftsConfig, sanitized, isolationField, isolationValue, ...
}
```

**ChatAdvisorChainFactory 改造：**

```java
if (request.isRagEnabled()) {
    Long userId = SecurityUtils.getCurrentUserId();
    Long teamId = request.teamId();
    // teamId 有值时，先校验团队成员身份
    if (teamId != null) {
        teamMembershipVerifier.verifyMember(teamId, userId);
    }
    RetrievalAugmentationAdvisor ragAdvisor = ragAdvisorFactory.create(userId, teamId);
    chain.add(ragAdvisor);
}
```

### 8.4 `listAll()` 查询改造

**现有逻辑：** `WHERE user_id = ? AND deleted = 0 ORDER BY create_time DESC`

**目标：** 返回个人文档 + 加入团队的文档，排除 PENDING_APPROVAL / REJECTED

**方案：** 在 `RagDocumentMapper.xml` 中新增自定义 SQL：

```sql
SELECT d.* FROM rag_document d
WHERE d.deleted = 0
  AND d.status NOT IN ('PENDING_APPROVAL', 'REJECTED')
  AND (
    (d.user_id = #{userId} AND d.team_id IS NULL)
    OR d.team_id IN (
      SELECT tm.team_id FROM team_member tm
      WHERE tm.user_id = #{userId} AND tm.status = 1
    )
  )
ORDER BY d.create_time DESC
```

分页使用 MyBatis-Plus Page 插件。

`DocumentDTO` 新增 `Long teamId` 字段，前端可区分个人/团队文档。

---

## 9. 新增模块结构

```
src/main/java/com/demo/chat/team/
├── entity/
│   ├── Team.java
│   ├── TeamMember.java
│   └── TeamUploadApproval.java
├── enums/
│   ├── TeamMemberRole.java
│   ├── TeamStatus.java
│   └── ApprovalStatus.java
├── mapper/
│   ├── TeamMapper.java + XML
│   ├── TeamMemberMapper.java + XML
│   └── TeamUploadApprovalMapper.java + XML
├── service/
│   ├── TeamService.java
│   ├── TeamMemberService.java
│   ├── TeamApprovalService.java
│   ├── TeamUploadQuotaService.java
│   └── TeamMembershipVerifier.java
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
│   ├── TeamController.java
│   ├── TeamMemberController.java
│   └── TeamApprovalController.java
├── upload/
│   ├── UploadStrategy.java
│   ├── PersonalUploadStrategy.java
│   ├── TeamUploadStrategy.java
│   └── UploadStrategyFactory.java
├── security/
│   └── DocumentOwnershipChecker.java
└── job/
    └── ApprovalTimeoutJob.java
```

---

## 10. 团队权限校验方式

团队权限**不使用 Spring Security `@PreAuthorize`**，在 Service 层校验。

### 统一校验组件：TeamMembershipVerifier

```java
@Component
public class TeamMembershipVerifier {
    public TeamMember verifyMember(Long teamId, Long userId) { ... }
    public TeamMember verifyAdmin(Long teamId, Long userId) { ... }
    public TeamMember verifyCreator(Long teamId, Long userId) { ... }
}
```

---

## 11. 错误码规划

错误码使用 **55xxx 段**。

| 枚举值 | 编码 | HTTP | 说明 |
|--------|------|------|------|
| `TEAM_NOT_FOUND` | 55001 | 404 | 团队不存在 |
| `TEAM_NAME_DUPLICATE` | 55002 | 400 | 团队名称已存在 |
| `NOT_TEAM_MEMBER` | 55003 | 403 | 不是团队成员 |
| `NOT_TEAM_ADMIN` | 55004 | 403 | 不是团队管理员/创建者 |
| `NOT_TEAM_CREATOR` | 55005 | 403 | 不是团队创建者 |
| `ALREADY_TEAM_MEMBER` | 55006 | 400 | 已经是团队成员 |
| `CREATOR_CANNOT_LEAVE` | 55007 | 400 | 创建者不能退出 |
| `CANNOT_CHANGE_CREATOR_ROLE` | 55008 | 400 | 不能修改创建者角色 |
| `UPLOAD_QUOTA_EXCEEDED` | 55009 | 400 | 上传超出额度 |
| `UPLOAD_LIMIT_OUT_OF_RANGE` | 55010 | 400 | 额度设置超出范围 |
| `APPROVAL_NOT_FOUND` | 55011 | 404 | 审批不存在 |
| `APPROVAL_ALREADY_PROCESSED` | 55012 | 400 | 审批已处理 |
| `NO_PERMISSION_DELETE_TEAM_DOC` | 55013 | 403 | 无权删除团队文档 |
| `TEAM_LIMIT_EXCEEDED` | 55014 | 400 | 团队数超限 |
| `TEAM_MEMBER_LIMIT_EXCEEDED` | 55015 | 400 | 成员数超限 |

---

## 12. 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.team.approval-timeout-days` | `7` | 审批超时天数 |
| `app.team.default-creator-upload-limit-mb` | `200` | 新建团队创建者默认额度 |
| `app.team.default-member-upload-limit-mb` | `50` | 新成员默认额度 |
| `app.team.max-members-per-team` | `50` | 单团队最大成员数 |
| `app.team.max-teams-per-user` | `10` | 单用户最大团队数 |

---

## 13. 风险与应对

| 风险 | 应对措施 |
|------|---------|
| 审批积压 MinIO 膨胀 | 7 天超时自动拒绝 + 删除文件 |
| 团队成员变动后权限不一致 | 实时查询 DB，不缓存 |
| 跨团队文档泄露 | 强制 `FilterExpressionBuilder.eq("teamId", teamId)` + `TeamMembershipVerifier` |
| teamId 传错 | 上传/检索时校验成员身份 |
| 个人上传回归 | 策略隔离 + 集成测试覆盖 |
| 审批并发 | 乐观锁 `WHERE status='PENDING'` |
| 解散与审批并发 | `SELECT FOR UPDATE` 锁定 team 行 + 批量 REJECT PENDING 审批 |
| team_member 查询遗漏 status | Mapper XML 强制 `WHERE status = 1`；代码审查重点 |

---

## 14. 新增同类功能的步骤（OCP 验证）

| 步骤 | 操作 | 改旧代码？ |
|------|------|-----------|
| 1 | Flyway V7 迁移（建表 + 清空向量） | ❌ |
| 2 | 新增 `team/` 模块全部代码 | ❌ |
| 3 | 新增 `UploadStrategy` 策略体系 | ❌ |
| 4 | `DocumentApplicationServiceImpl` 委托给策略 | ⚠️ 注入点 |
| 5 | `EtlCandidate` record 扩展 teamId | ⚠️ record 扩展 |
| 6 | ETL metadata 写入加 teamId | ⚠️ 条件扩展 |
| 7 | `ErrorCode` 加 55xxx 段 | ⚠️ 枚举扩展 |
| 8 | `ChatRequest` 加 teamId | ⚠️ record 扩展 |
| 9 | RAG 检索链路 teamId 透传 | ⚠️ 参数透传 |
| 10 | `DocumentOwnershipChecker` 替换 `findAndVerifyOwner` | ⚠️ 替换调用点 |

---

## 15. 实施阶段建议

| 阶段 | 内容 | 前置条件 |
|------|------|---------|
| **Phase 1: 提取共享组件** | `DocumentOwnershipChecker`、`TeamMembershipVerifier`、`EtlCandidate` 扩展 + 7 处构造点更新 | 无 |
| **Phase 2: 策略模式 + 回归** | `UploadStrategy` + `PersonalUploadStrategy`（封装现有逻辑）+ 集成测试验证个人上传回归 | Phase 1 |
| **Phase 3: 团队功能** | team 模块全部代码 + `TeamUploadStrategy` + 审批流程 + 分片上传改造 | Phase 2 |
| **Phase 4: RAG 检索改造** | `RagAdvisorFactory` 改造 + `HybridDocumentRetriever` 参数化 + BM25 SQL 改造 + 清空旧向量 + `listAll()` 改造 | Phase 3 |

---

## 16. 工作量估算

| 部分 | 预估 |
|------|------|
| Phase 1：共享组件 + EtlCandidate 改造 | 4h |
| Phase 2：策略模式 + 回归测试 | 6h |
| Phase 3：团队模块全部功能 | 18h |
| Phase 4：RAG 检索改造 | 6h |
| **合计** | **~34h** |
