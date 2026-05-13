# PRD：团队协作功能

> 版本：v1.0 | 日期：2026-05-13 | 状态：草稿

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
| **团队角色** | `team_member.role` 中的 CREATOR / ADMIN / MEMBER，控制团队内操作权限 |
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
- **输入：** 团队名称（必填，≤128 字符，全局唯一）、团队描述（可选，≤512 字符）
- **处理：**
  1. 校验团队名称唯一性
  2. 创建 `team` 记录
  3. 自动创建 `team_member` 记录（userId=创建者，role=CREATOR）
- **输出：** 团队信息（id, name, desc, creatorId, createdAt）
- **异常：** 团队名称已重复 → `TEAM_NAME_DUPLICATE`

#### 4.1.2 更新团队信息

- **操作者：** CREATOR
- **输入：** 团队名称（可选）、团队描述（可选）
- **处理：** 校验操作者是 CREATOR → 更新字段
- **异常：** 非创建者 → `NOT_TEAM_CREATOR`；名称重复 → `TEAM_NAME_DUPLICATE`

#### 4.1.3 解散团队

- **操作者：** CREATOR
- **处理：**
  1. 逻辑删除 `team` 记录
  2. 逻辑删除所有 `team_member` 记录
  3. 逻辑删除所有 `rag_document` 中属于该团队的文档
  4. 异步清理 MinIO 中团队文件 + PGvector 中团队向量数据
- **注意：** 这是不可逆操作，确认后执行

#### 4.1.4 查看团队列表

- **操作者：** 任何已认证用户
- **输出：** 当前用户加入的所有团队（id, name, desc, memberCount, myRole, createdAt）

#### 4.1.5 查看团队详情

- **操作者：** 团队成员
- **输出：** 团队完整信息 + 当前用户角色 + 成员数 + 文档数 + 默认上传额度

### 4.2 成员管理

#### 4.2.1 加入团队

- **操作者：** 任何已认证用户
- **输入：** teamId
- **处理：**
  1. 校验团队存在且启用
  2. 校验用户未加入该团队
  3. 创建 `team_member`（role=MEMBER, upload_limit=团队默认额度）
- **异常：** 已加入 → `ALREADY_TEAM_MEMBER`；团队不存在 → `TEAM_NOT_FOUND`

> 注：本期采用直接加入模式，无邀请/审批流程。后续版本可扩展为邀请码或申请审批。

#### 4.2.2 退出团队

- **操作者：** MEMBER / ADMIN
- **处理：**
  1. 校验操作者不是 CREATOR（创建者不能退出，只能解散）
  2. 逻辑删除 `team_member` 记录
  3. 该成员上传的团队文档**保留在团队空间**，不随成员退出而删除
- **异常：** 创建者无法退出 → `CREATOR_CANNOT_LEAVE`

#### 4.2.3 移除成员

- **操作者：** CREATOR
- **输入：** teamId, targetUserId
- **处理：**
  1. 校验目标成员存在且未被移除
  2. 逻辑删除 `team_member` 记录
  3. 成员文档保留在团队空间

#### 4.2.4 提拔/取消管理员

- **操作者：** CREATOR
- **输入：** teamId, targetUserId, targetRole（ADMIN 或 MEMBER）
- **处理：** 校验目标成员存在 → 更新 `team_member.role`
- **异常：** 不能修改创建者角色 → `CANNOT_CHANGE_CREATOR_ROLE`

#### 4.2.5 查看成员列表

- **操作者：** 团队成员
- **输出：** 成员列表（userId, username, nickname, role, uploadLimitMb, joinedAt）

#### 4.2.6 设定成员上传额度

- **操作者：** CREATOR
- **输入：** teamId, targetUserId, uploadLimitMb（单位 MB，正整数）
- **处理：** 校验目标成员存在 → 更新 `team_member.upload_limit_mb`
- **约束：** 额度不得大于创建者的 `creator_upload_limit_mb`，不得小于 1MB
- **异常：** 额度超出范围 → `UPLOAD_LIMIT_OUT_OF_RANGE`

### 4.3 团队文档上传

#### 4.3.1 上传流程（普通成员）

```
成员 POST /api/documents/upload {file, teamId}
  │
  ├─ 1. 校验团队成员身份
  ├─ 2. 校验上传额度：file.size ≤ member.upload_limit_mb
  ├─ 3. 文件校验（MIME 白名单 + 魔数，复用现有 DocumentValidator）
  ├─ 4. 文件存储到 MinIO
  ├─ 5. 创建 rag_document（team_id=teamId, status=PENDING_APPROVAL）
  ├─ 6. 创建 team_upload_approval（status=PENDING）
  └─ 7. 返回 {documentId, status: PENDING_APPROVAL, message: "等待审批"}
```

- **不触发 ETL**，文件暂时只存在于 MinIO，未进入向量库
- 文件大小计入成员额度校验

#### 4.3.2 上传流程（管理员 / 创建者）

```
管理员/创建者 POST /api/documents/upload {file, teamId}
  │
  ├─ 1. 校验团队成员身份 + 角色为 ADMIN 或 CREATOR
  ├─ 2. 校验上传额度：
  │      ADMIN   → file.size ≤ member.upload_limit_mb
  │      CREATOR → file.size ≤ team.creator_upload_limit_mb
  ├─ 3. 文件校验（复用现有逻辑）
  ├─ 4. 文件存储到 MinIO
  ├─ 5. 创建 rag_document（team_id=teamId, status=UPLOADED）
  ├─ 6. 自动创建 team_upload_approval（status=APPROVED, reviewer_id=自己）
  └─ 7. 立即触发 ETL → 返回 {documentId, status: PROCESSING}
```

#### 4.3.3 分片上传支持

现有分片上传接口（`/api/documents/multipart`）需同步支持 `teamId`：

- `init` 请求增加 `teamId` 字段
- `init` 阶段即进行团队成员校验和额度校验（基于总文件大小）
- 合并完成后的逻辑与普通上传一致（审批 / 自动通过）

#### 4.3.4 团队创建者的上传额度

- 由系统管理员通过 API 设定：`PATCH /api/teams/{teamId}/creator-quota {maxUploadMb}`
- **权限要求：** `team:manage`（新增系统权限，仅 ADMIN 角色）
- 底层：更新 `team.creator_upload_limit_mb`
- 系统管理员也可设置团队的**默认成员额度**：`team.default_upload_limit_mb`

### 4.4 审批流程

#### 4.4.1 待审批列表

- **操作者：** CREATOR / ADMIN
- **输出：** 团队内所有 status=PENDING 的审批记录（分页）
  - 包含：文档名、文件大小、上传者信息、上传时间

#### 4.4.2 审批操作

- **操作者：** CREATOR / ADMIN
- **输入：** approvalId, action（APPROVE / REJECT）, comment（可选）
- **处理（APPROVE）：**
  1. 更新 `team_upload_approval`（status=APPROVED, reviewer_id, reviewed_at, comment）
  2. 更新 `rag_document.status` → UPLOADED
  3. 触发 ETL（异步）
- **处理（REJECT）：**
  1. 更新 `team_upload_approval`（status=REJECTED, reviewer_id, reviewed_at, comment）
  2. 更新 `rag_document.status` → REJECTED
  3. 删除 MinIO 中的文件
  4. 保留 rag_document 记录（标记已拒绝），用于历史查询
- **异常：** 非管理员 → `NOT_TEAM_ADMIN`；审批不存在 → `APPROVAL_NOT_FOUND`；已处理 → `APPROVAL_ALREADY_PROCESSED`

#### 4.4.3 审批超时

- 定时任务（每天 1 次）：扫描 `created_at < NOW() - INTERVAL '7 days'` 且 status=PENDING 的记录
- 自动拒绝 + 删除 MinIO 文件
- 配置化：`app.team.approval-timeout-days`，默认 7

#### 4.4.4 我的审批记录

- **操作者：** 团队成员（查看自己提交的审批）
- **输出：** 当前用户在指定团队的所有审批记录（PENDING / APPROVED / REJECTED）

### 4.5 团队文档管理

#### 4.5.1 查看团队文档列表

- **操作者：** 团队成员
- **输出：** 团队空间内所有文档（status 为 APPROVED 或 PROCESSING 或 COMPLETED 的）
- **不含：** status=PENDING_APPROVAL / REJECTED 的文档（这些在审批列表中查看）

#### 4.5.2 删除团队文档

- **操作者：** CREATOR / ADMIN / 文档上传者本人
- **处理：** 复用现有 `DocumentLifecycleService.cascadeDelete()`，清理 MinIO + PGvector + rag_document
- **异常：** 非上述角色 → `NO_PERMISSION_DELETE_TEAM_DOC`

#### 4.5.3 团队文档的 RAG 检索

- 团队文档完成 ETL 后，向量数据中携带 `teamId` metadata
- RAG 聊天时，支持指定 `teamId` 参数：
  - `teamId = null` → 检索个人文档（现有逻辑不变）
  - `teamId = xxx` → 检索团队文档（`FilterExpressionBuilder.eq("teamId", teamId)`）
- **权限校验：** 检索前验证请求者是团队成员

---

## 5. 数据库设计

### 5.1 新增表

#### team（团队表）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| id | BIGINT | PK, GENERATED ALWAYS AS IDENTITY | 团队 ID |
| team_name | VARCHAR(128) | NOT NULL, UNIQUE | 团队名称 |
| team_desc | VARCHAR(512) | | 团队描述 |
| creator_id | BIGINT | NOT NULL, FK → sys_user(id) | 创建者 |
| default_upload_limit_mb | BIGINT | DEFAULT 50 | 默认成员上传额度（MB） |
| creator_upload_limit_mb | BIGINT | DEFAULT 200 | 创建者上传额度（MB），系统管理员设定 |
| status | INT | NOT NULL DEFAULT 1 | 1=启用 0=禁用 |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| deleted | INT | NOT NULL DEFAULT 0 | 逻辑删除 |

索引：`idx_team_creator(creator_id)`

#### team_member（团队成员表）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| id | BIGINT | PK, GENERATED ALWAYS AS IDENTITY | |
| team_id | BIGINT | NOT NULL, FK → team(id) | 所属团队 |
| user_id | BIGINT | NOT NULL, FK → sys_user(id) | 用户 |
| role | VARCHAR(32) | NOT NULL DEFAULT 'MEMBER' | CREATOR / ADMIN / MEMBER |
| upload_limit_mb | BIGINT | | 单次上传额度（MB），NULL 使用团队默认值 |
| status | INT | NOT NULL DEFAULT 1 | 1=正常 0=已移除 |
| joined_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |

约束：`uk_team_user UNIQUE (team_id, user_id)`
索引：`idx_team_member_user(user_id)`

#### team_upload_approval（团队上传审批表）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| id | BIGINT | PK, GENERATED ALWAYS AS IDENTITY | |
| team_id | BIGINT | NOT NULL, FK → team(id) | 所属团队 |
| document_id | BIGINT | NOT NULL, FK → rag_document(id) | 关联文档 |
| uploader_id | BIGINT | NOT NULL, FK → sys_user(id) | 上传者 |
| status | VARCHAR(32) | NOT NULL DEFAULT 'PENDING' | PENDING / APPROVED / REJECTED |
| reviewer_id | BIGINT | FK → sys_user(id) | 审批人 |
| review_comment | VARCHAR(512) | | 审批备注 |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| reviewed_at | TIMESTAMPTZ | | |

索引：`idx_approval_team_status(team_id, status)`、`idx_approval_uploader(uploader_id)`

### 5.2 修改表

#### rag_document（加字段）

| 列 | 类型 | 说明 |
|----|------|------|
| team_id | BIGINT, 新增 | 所属团队，NULL=个人文档 |

索引：`idx_rag_document_team(team_id)`

### 5.3 Flyway 迁移

- **V7__add_team.sql** — 建表 + 修改字段 + 索引 + 初始权限数据
- 向 `sys_permission` 插入 `team:view`、`team:manage` 权限
- 将 `team:manage` 绑定到 ADMIN 角色

---

## 6. API 设计

### 6.1 团队管理 — `/api/teams`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/teams` | isAuthenticated | 创建团队 |
| GET | `/api/teams` | isAuthenticated | 我加入的团队列表 |
| GET | `/api/teams/{teamId}` | 团队成员 | 团队详情 |
| PATCH | `/api/teams/{teamId}` | CREATOR | 更新团队信息 |
| DELETE | `/api/teams/{teamId}` | CREATOR | 解散团队 |
| PATCH | `/api/teams/{teamId}/creator-quota` | `team:manage`（系统ADMIN） | 设置创建者额度 |
| PATCH | `/api/teams/{teamId}/default-quota` | `team:manage`（系统ADMIN） | 设置默认成员额度 |

### 6.2 成员管理 — `/api/teams/{teamId}/members`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/teams/{teamId}/members` | 团队成员 | 成员列表 |
| POST | `/api/teams/{teamId}/members` | isAuthenticated | 加入团队 |
| DELETE | `/api/teams/{teamId}/members/{userId}` | CREATOR / 本人 | 移除成员 / 退出团队 |
| PATCH | `/api/teams/{teamId}/members/{userId}/role` | CREATOR | 变更成员角色 |
| PATCH | `/api/teams/{teamId}/members/{userId}/upload-limit` | CREATOR | 设定成员上传额度 |

### 6.3 审批管理 — `/api/teams/{teamId}/approvals`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/teams/{teamId}/approvals` | CREATOR / ADMIN | 待审批列表 |
| GET | `/api/teams/{teamId}/approvals/mine` | 团队成员 | 我的审批记录 |
| PATCH | `/api/teams/{teamId}/approvals/{approvalId}` | CREATOR / ADMIN | 审批操作（通过/拒绝） |

### 6.4 文档接口变更

现有 `/api/documents` 接口增加 `teamId` 支持：

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/documents/upload` | 新增可选参数 `teamId` |
| POST | `/api/documents/upload/batch` | 新增可选参数 `teamId` |
| GET | `/api/documents` | 返回个人文档 + 团队文档（标注 teamId） |
| GET | `/api/teams/{teamId}/documents` | 团队文档列表（新增） |
| DELETE | `/api/documents/{id}` | 团队文档需校验团队角色 |

分片上传接口同步变更：

| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/documents/multipart` | init 请求增加 `teamId` 字段 |

---

## 7. 对现有模块的影响分析

### 7.1 无需改动的模块

| 模块 | 原因 |
|------|------|
| RBAC（sys_role / sys_permission / sys_user_role / sys_role_permission） | 团队角色和系统角色正交 |
| JWT / SecurityConfig / JwtAuthenticationFilter | 系统权限不变，团队权限在业务层校验 |
| Chat 模块（ChatService / ChatController / Advisor） | 聊天功能不涉及团队 |
| Conversation 模块 | 会话不涉及团队 |
| Provider / ModelRouter | 模型路由不涉及团队 |
| Tool Calling / Sandbox | 不涉及团队 |
| TokenCacheService / CaptchaService | 不涉及团队 |
| AuthService | 不涉及团队（登录/注册不受影响） |

### 7.2 需要改动的模块

| 模块 | 改动内容 | 影响范围 |
|------|---------|---------|
| **DocumentApplicationServiceImpl** | 上传逻辑分叉：teamId 有值走团队流程（额度校验 + 审批），无值走原有个人流程 | `upload()`、`uploadBatch()` 方法内增加判断分支 |
| **DocumentController** | 上传接口增加 `teamId` 参数 | 参数层面，不影响现有调用（teamId 可选，默认 null） |
| **ChunkUploadController / ChunkUploadServiceImpl** | init 请求增加 `teamId`，分叉逻辑同上 | `init()` 方法增加参数和分支 |
| **RagDocument** | 加 `teamId` 字段 | 实体层，向后兼容（现有数据 teamId=NULL） |
| **RagAdvisorFactory** | RAG 检索支持按 `teamId` 过滤 | 新增分支，不影响现有个人检索 |
| **DocumentProperties** | 新增团队审批超时配置 | 仅新增字段 |
| **ErrorCode** | 新增团队相关错误码 | 仅新增枚举值 |
| **V3__seed 权限数据** | 新增 `team:view`、`team:manage` 权限 | 在 V7 迁移中新增 |

---

## 8. 新增模块结构

```
src/main/java/com/demo/chat/team/
├── entity/
│   ├── Team.java
│   ├── TeamMember.java
│   └── TeamUploadApproval.java
├── enums/
│   ├── TeamMemberRole.java         # CREATOR / ADMIN / MEMBER
│   ├── TeamStatus.java             # ENABLED / DISABLED
│   └── ApprovalStatus.java         # PENDING / APPROVED / REJECTED
├── mapper/
│   ├── TeamMapper.java + XML
│   ├── TeamMemberMapper.java + XML
│   └── TeamUploadApprovalMapper.java + XML
├── service/
│   ├── TeamService.java            # 团队 CRUD
│   ├── TeamMemberService.java      # 成员管理
│   ├── TeamApprovalService.java    # 审批流程
│   └── TeamUploadQuotaService.java # 上传额度校验
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
└── job/
    └── ApprovalTimeoutJob.java     # 审批超时自动拒绝
```

---

## 9. 团队权限校验方式

团队权限**不使用 Spring Security `@PreAuthorize`**，在 Service 层校验。原因：

- 团队权限是**关系型权限**（"我是不是这个团队的成员"、"我在团队里是什么角色"），不是固定的权限码
- 同一个用户在不同团队中角色不同，无法用静态权限表达
- 需要在方法参数中传入 `teamId`，`@PreAuthorize` 的 SpEL 表达力不足

校验方式：

```java
// Service 层通用校验方法
private TeamMember verifyTeamMember(Long teamId, Long userId) {
    Team team = teamMapper.selectById(teamId);
    if (team == null || team.getDeleted() == 1) throw TEAM_NOT_FOUND;
    TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
    if (member == null || member.getStatus() != 1) throw NOT_TEAM_MEMBER;
    return member;
}

private void verifyTeamAdmin(Long teamId, Long userId) {
    TeamMember member = verifyTeamMember(teamId, userId);
    if (member.getRole() != CREATOR && member.getRole() != ADMIN) {
        throw NOT_TEAM_ADMIN;
    }
}

private void verifyTeamCreator(Long teamId, Long userId) {
    TeamMember member = verifyTeamMember(teamId, userId);
    if (member.getRole() != CREATOR) {
        throw NOT_TEAM_CREATOR;
    }
}
```

---

## 10. 错误码规划

| 错误码 | 枚举值 | HTTP 状态码 | 说明 |
|--------|--------|------------|------|
| `TEAM_NOT_FOUND` | 50001 | 404 | 团队不存在 |
| `TEAM_NAME_DUPLICATE` | 50002 | 400 | 团队名称已存在 |
| `NOT_TEAM_MEMBER` | 50003 | 403 | 不是团队成员 |
| `NOT_TEAM_ADMIN` | 50004 | 403 | 不是团队管理员/创建者 |
| `NOT_TEAM_CREATOR` | 50005 | 403 | 不是团队创建者 |
| `ALREADY_TEAM_MEMBER` | 50006 | 400 | 已经是团队成员 |
| `CREATOR_CANNOT_LEAVE` | 50007 | 400 | 创建者不能退出团队 |
| `CANNOT_CHANGE_CREATOR_ROLE` | 50008 | 400 | 不能修改创建者角色 |
| `UPLOAD_QUOTA_EXCEEDED` | 50009 | 400 | 上传文件超出额度 |
| `UPLOAD_LIMIT_OUT_OF_RANGE` | 50010 | 400 | 上传额度设置超出范围 |
| `APPROVAL_NOT_FOUND` | 50011 | 404 | 审批记录不存在 |
| `APPROVAL_ALREADY_PROCESSED` | 50012 | 400 | 审批已处理 |
| `NO_PERMISSION_DELETE_TEAM_DOC` | 50013 | 403 | 无权删除团队文档 |

---

## 11. 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.team.approval-timeout-days` | `7` | 审批超时天数，超时自动拒绝 |
| `app.team.default-creator-upload-limit-mb` | `200` | 新建团队时创建者的默认上传额度 |
| `app.team.default-member-upload-limit-mb` | `50` | 新成员加入时的默认上传额度 |

---

## 12. 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| 审批积压导致 MinIO 存储膨胀 | 存储成本 | 7 天超时自动拒绝 + 删除文件 |
| 团队成员变动后权限不一致 | 安全 | 团队权限实时查询 DB，不缓存 |
| 团队文档 RAG 检索跨团队泄露 | 数据安全 | 检索时强制 `FilterExpressionBuilder.eq("teamId", teamId)` + 成员身份校验 |
| 一人多团队上传时 teamId 传错 | 数据错乱 | 上传时校验 teamId 对应的成员身份 |
| 现有个人文档功能回归 | 功能损坏 | teamId 可选（默认 null），个人上传走原有代码路径，增加分支不修改原有逻辑 |

---

## 13. 新增同类功能的步骤（OCP 验证）

按照项目的设计原则，验证团队模块是否满足开闭原则：

| 步骤 | 操作 | 是否改动旧代码 |
|------|------|--------------|
| 1. 新增 Flyway V7 迁移 | 建表 + 改字段 + 新增权限数据 | ❌ 不改旧迁移 |
| 2. 新增 `team/` 模块全部代码 | Entity / Mapper / Service / Controller | ❌ 全新文件 |
| 3. `RagDocument` 加 `teamId` 字段 | 实体新增字段 | ⚠️ 改动现有实体（向后兼容） |
| 4. `DocumentApplicationServiceImpl` 上传分叉 | 增加判断分支 | ⚠️ 改动现有 Service（不修改原有逻辑，只增加分支） |
| 5. `DocumentController` 加 `teamId` 参数 | 可选参数 | ⚠️ 改动现有 Controller（向后兼容） |
| 6. `ErrorCode` 加枚举值 | 新增枚举值 | ⚠️ 改动现有枚举（向后兼容） |
| 7. `RagAdvisorFactory` 支持团队检索 | 增加分支 | ⚠️ 改动现有代码（向后兼容） |

**结论：** 新增团队模块本身完全符合 OCP。对现有代码的改动均为**向后兼容的扩展**（新增可选字段、新增分支），不修改原有逻辑。

---

## 14. 工作量估算

| 部分 | 预估 |
|------|------|
| Flyway V7 迁移 + 权限数据 | 1h |
| team 模块 Entity + Enum | 1.5h |
| team 模块 Mapper + XML | 2h |
| TeamService + TeamMemberService + Impl | 3h |
| TeamApprovalService + TeamUploadQuotaService + Impl | 3h |
| 审批超时定时任务 | 1h |
| DTO（~11 个 record） | 1.5h |
| Controller（3 个） | 2h |
| DocumentApplicationServiceImpl 上传分叉改造 | 3h |
| 分片上传支持 teamId | 2h |
| RAG 检索支持团队文档 | 2h |
| ErrorCode 扩展 | 0.5h |
| 配置项 + DocumentProperties 扩展 | 0.5h |
| 单元测试 | 4h |
| 文档更新（README / 架构文档 / 数据库文档） | 1.5h |
| **合计** | **~29h** |
