# Phase 3: 团队模块全部功能

> 父任务：05-13-team-collaboration
> 设计文档：`docs/TEAM-FEATURE-PRD.md` §4~6 + §8.1 + §9

## 目标

实现完整的团队 CRUD、成员管理、审批流程、团队上传、分片上传改造。

## 前置条件

- Phase 1 完成（枚举、ErrorCode、共享组件）
- Phase 2 完成（策略模式框架、回归测试通过）

## 交付物

### 1. Flyway V7 迁移

路径：`src/main/resources/db/migration/V7__add_team.sql`

- 建 `team`、`team_member`、`team_upload_approval` 表
- `rag_document` 加 `team_id` 字段
- 索引 + partial unique index
- `sys_permission` 新增 `team:view`、`team:manage`
- `sys_role_permission` 绑定 ADMIN
- **`TRUNCATE TABLE vector_store;`**
- 幂等（`IF NOT EXISTS`）

### 2. Entity（3 个）

| 类 | 表 |
|----|-----|
| `Team.java` | `team` |
| `TeamMember.java` | `team_member` |
| `TeamUploadApproval.java` | `team_upload_approval` |

注解：`@TableName`、`@TableId(type = IdType.AUTO)`、`@TableLogic`（仅 Team）、`@EnumValue`

### 3. Mapper（3 个）+ XML

继承 `BaseMapper<T>`，自定义查询用 XML。

### 4. TeamMembershipVerifier 实现

补全 `verifyMember()`、`verifyAdmin()`、`verifyCreator()` 逻辑：
- 查 team（deleted=0）
- 查 team_member（status=1）
- 按角色校验

### 5. Service（4 个）

| Service | 职责 |
|---------|------|
| `TeamService` | 团队 CRUD |
| `TeamMemberService` | 成员管理（加入/退出/移除/角色变更/额度设定） |
| `TeamApprovalService` | 审批流程（审批/超时/查询） |
| `TeamUploadQuotaService` | 上传额度校验 |

### 6. TeamUploadStrategy 实现

路径：`com.demo.chat.team.upload.TeamUploadStrategy`

实现 §4.3.2 / §4.3.3 的团队上传逻辑：
- 成员上传（额度校验 + PENDING_APPROVAL + 不触发 ETL）
- 管理员/创建者上传（额度校验 + APPROVED + 触发 ETL）

### 7. UploadStrategyFactory 更新

注入 `TeamUploadStrategy`，移除占位异常。

### 8. DTO（~11 个）

`TeamCreateRequest`、`TeamUpdateRequest`、`TeamVO`、`TeamDetailVO`、`TeamMemberVO`、`MemberRoleUpdateRequest`、`MemberUploadLimitRequest`、`CreatorQuotaRequest`、`ApprovalVO`、`ApprovalReviewRequest`、`MyApprovalVO`

### 9. Controller（3 个）

| Controller | 路径 |
|-------------|------|
| `TeamController` | `/api/teams` |
| `TeamMemberController` | `/api/teams/{teamId}/members` |
| `TeamApprovalController` | `/api/teams/{teamId}/approvals` |

### 10. 分片上传改造

`ChunkUploadServiceImpl` + `ChunkUploadInitRequest` 改造：
- `init()` 接受 teamId + 团队校验 + 额度校验
- `validateFileSize()` 参数化限额
- Redis session 存储 teamId + status
- `persistDocument()` 条件设置 PENDING_APPROVAL / PROCESSING
- `performMerge()` 条件 ETL
- `complete()` 二次校验成员
- `findExistingForQuickUpload()` 加 teamId 隔离

### 11. ApprovalTimeoutJob

定时任务：扫描超时 PENDING 审批 → 自动拒绝 + 删除 MinIO 文件。

### 12. DocumentOwnershipChecker 补全

补全团队文档分支（注入 `TeamMembershipVerifier` 已在 Phase 1 完成）。

替换 `DocumentApplicationServiceImpl` 中的 `findAndVerifyOwner()` 为 `documentOwnershipChecker.checkOwnership()`。

### 13. DocumentDTO 扩展

新增 `Long teamId` 字段。

### 14. listAll() 查询改造

`RagDocumentMapper.xml` 新增自定义 SQL，返回个人 + 团队文档。

### 15. DocumentController 变更

上传接口增加可选 `teamId` 参数。

### 16. 新增团队文档列表接口

`GET /api/teams/{teamId}/documents`

## 验收标准

- [ ] V7 迁移脚本存在且幂等
- [ ] 3 个 Entity + Mapper 编译通过
- [ ] 4 个 Service + Controller 编译通过
- [ ] 团队创建/更新/解散/搜索 API 正常
- [ ] 成员加入/退出/移除/角色变更 API 正常
- [ ] 团队成员上传 → PENDING_APPROVAL → 审批通过 → ETL 触发
- [ ] 管理员/创建者上传 → 直接 PROCESSING → ETL 触发
- [ ] 分片上传支持 teamId
- [ ] 审批超时定时任务正常
- [ ] 所有现有 JUnit 测试通过
