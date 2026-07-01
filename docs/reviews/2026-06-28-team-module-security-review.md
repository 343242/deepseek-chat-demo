# 团队模块安全与隔离审查报告 — 2026-06-28

> 审查范围：`src/main/java/com/smart/rag/team/`，并追踪所有 `teamId` 流向的 chat / rag / agent 调用链  
> 审查目标：权限边界、团队隔离、会话绑定、版本替换授权、管理权限一致性  
> 审查方式：源码静态审查 + 关键调用链核对  
> 总体结论：**REQUEST CHANGES**

---

## 0. 总体判断

| 维度 | 结论 |
|---|---|
| 团队 CRUD / 审批 / 成员管理 | 基础 RBAC 基本成立 |
| RAG / Agent 团队隔离 | **存在明显缺口** |
| 上传会话边界 | **存在上下文绑定缺口** |
| 版本替换授权 | **过宽** |
| 管理权限契约 | **前后不一致** |

---

## 1. 问题总览

| 级别 | 位置 | 问题 | 影响 |
|---|---|---|---|
| **CRITICAL** | `ChatController.java:39-49`、`ChatServiceImpl.java:149-165`、`RagAdvisorFactory.java:93-171`、`AgentModeStrategy.java:154-182` | `teamId` 由请求直接提供，但没有任何团队成员身份校验；RAG / Agent 链路只把它当过滤条件 | 任意认证用户可指定任意 `teamId` 读取该团队知识库 |
| **HIGH** | `TeamUploadStrategy.java:79-95`、`DocumentSupersedeService.java:74-105, 218-223`、`DocumentOwnershipChecker.java:25-29` | 团队文档替换只要求“同 team”，不要求 uploader / creator / admin | 任意团队成员可替换其他成员文档的版本关系 |
| **MEDIUM** | `TeamChunkUploadController.java:45-130`、`ChunkUploadServiceImpl.java:249-301, 347-420, 582-603` | 上传会话只把 `userId` 当 owner，`teamId` 不是强绑定边界；幂等回查在 session 清理后丢失 team scope | 同用户多团队场景下会话上下文可混用，且 team 上传的重复 complete 容易误判 |
| **LOW** | `TeamController.java:53-58`、`TeamServiceImpl.java:269-276`、`V9__add_team.sql:91-95` | `team:manage` 被授权给 ADMIN，但服务层仍只允许 CREATOR | 权限语义不一致，容易误配 / 误判 |
| **INFO** | `TeamStatusServiceImpl.java:27-36` | `isTeamActive()` 只看 `deleted`，`status` 语义未真正落地 | disabled / inactive 后续容易漂移 |

---

## 2. 详细发现

### 2.1 CRITICAL — `teamId` 不是受保护的租户边界

**证据**
- `ChatController.chat()` / `chatStreamPost()` 直接接收 `ChatRequest.teamId`
- `ChatServiceImpl.prepare()` 把 `teamId` 原样带入请求上下文
- `AgentModeStrategy` 把 `teamId` 交给 `ToolWorkspaceFactory`
- `RagAdvisorFactory.create()` / `retrieve()` 只按 `teamId` 构建过滤器，没有做 membership 校验

**问题**
`teamId` 在这里是“用户可控的隔离选择器”，不是“服务端确认过的团队身份”。  
也就是说，只要知道团队 ID，调用方就能把请求导向该团队的检索链路。  
项目已存在统一的成员校验组件 `TeamMembershipVerifier.verifyMember(teamId, userId)`（`team/service/TeamMembershipVerifier.java:48-58`），并在文档模块的 `DocumentOwnershipChecker` / `DocumentApplicationServiceImpl` 中使用——**但 chat / RAG / agent 检索链路完全没有调用它**（全局 grep `verifyMember` 在检索链零命中）。入口有了，却没接到最关键的读路径上。

**影响**
- 团队知识库可被跨团队枚举/读取
- `teamId` 被当成普通参数，而不是 ACL 边界
- 未来新增的 team-scoped tool / retriever / agent 也会默认继承这个缺口

**建议**
- 在 chat / agent 入口统一做团队成员校验
- 如果 `teamId` 存在，必须先验证 `userId` 是该团队活跃成员
- 最好把 `teamId` 变成“已授权上下文”，而不是请求直传字段

---

### 2.2 HIGH — 团队文档替换权限过宽

**证据**
- `TeamUploadStrategy.upload()` / `uploadBatch()` 允许请求携带 `replaceDocumentId`
- `DocumentSupersedeService.onDocumentCreated()` 读取 `replaceDocumentId` 后，`isOwner()` 对团队文档只判断 `teamId.equals(doc.getTeamId())`
- `DocumentOwnershipChecker` 已经定义了更严格的团队文档规则：团队成员 +（CREATOR / ADMIN 或文档上传者）

**问题**
当前版本替换逻辑等价于“只要是同一团队成员，就能替换团队内任意文档”。  
这比 `DocumentOwnershipChecker` 里的策略宽得多，也没有在 `TeamUploadStrategy` 侧做前置拦截。

**影响**
- 任意团队成员可替换他人文档的版本关系
- 版本链、审计链、内容归属都可能被误导
- 如果替换动作后续还会触发 ETL / 向量更新，影响会进一步放大

**建议**
- 复用 `DocumentOwnershipChecker`，或新增专门的 `ReplaceDocumentPermissionChecker`
- 团队文档替换至少应满足：上传者本人 / 团队管理员 / 创建者
- 若“团队内任意成员都可替换”是产品意图，需显式写入文档并补充审计说明

---

### 2.3 MEDIUM — 上传会话缺少 team 级强绑定

**证据**
- `TeamChunkUploadController.verifyTeamAccess()` 只在 controller 层做一次 team membership 校验
- `ChunkUploadServiceImpl.createNewSession()` 只把 `userId` 作为 owner 保护，`teamId` 仅写入 Redis session
- `validateOwner()` 只比较 `userId`
- `complete()` 在 session 已清理时回查 `findExistingForQuickUpload(fileMd5, userId, null)`，会丢失 team scope

**问题**
当前上传会话的“真正身份”只有 `userId`，`teamId` 只是初始化时附带保存的上下文。  
这会造成两个问题：
1. 服务层无法主动发现 `teamId` 与会话不一致
2. team 上传在幂等回查路径上会丢失 team 维度，session 清理后更容易误判

**影响**
- 同一用户在多个团队间切换时，上传会话上下文容易混用
- `complete()` 的重复调用在团队场景下更容易走到错误分支
- 这是“上下文绑定不完整”的问题，虽非跨用户越权，但会削弱隔离保证

**建议**
- 把 `teamId` 作为会话的强约束字段
- `status / complete / abort` 也应校验 `teamId` 一致性
- 让服务层持有 team 上下文，而不是只依赖 controller 层门禁

---

### 2.4 LOW — `team:manage` 与服务层 creator-only 逻辑冲突

**证据**
- `TeamController.setCreatorQuota()` 使用 `@PreAuthorize("hasAuthority('team:manage')")`
- `V9__add_team.sql` 将 `team:manage` 绑定给 `ADMIN`
- `TeamServiceImpl.setCreatorQuota()` 仍然只允许 `CREATOR`

**问题**
这个点不是越权漏洞，而是权限契约不一致：  
Controller 暗示“ADMIN 可管理”，Service 又把它收回到“只有 CREATOR 才行”。

**影响**
- 权限配置很容易误配
- 运维/产品侧会误以为 ADMIN 已具备管理能力

**建议**
- 要么把 service 放宽到 ADMIN / CREATOR 都可用
- 要么把 `team:manage` 的绑定和接口注解改成真正的 creator-only 语义

---

### 2.5 INFO — `status` 语义未真正落地

**证据**
- `TeamStatusServiceImpl.isTeamActive()` / `TeamServiceImpl.getActiveTeam()` 都只看 `deleted`

**问题**
如果 `status` 字段本来要表达禁用/启用，那么当前实际上没有被执行。  
这会让“disabled team”变成一个名义上存在、实际不生效的状态。

**建议**
- 明确团队状态机：`deleted` 负责解散，`status` 负责启用/禁用，还是二者只保留一个
- 若保留 `status`，请把 `isTeamActive()`、上传、审批、聊天等入口统一接上

---

## 3. 正向项

- 团队成员 / 审批 / 管理路径里已有显式角色检查，基础 RBAC 不是空的
- `TeamMembershipVerifier` 和 `DocumentOwnershipChecker` 已经存在，可直接复用来收紧边界
- 未看到明显的 SQL 拼接注入或文件路径穿越模式

---

## 4. 建议修复顺序

1. 先补 `teamId` 的成员身份校验，锁住 chat / agent / RAG 的团队边界
2. 再收紧 `replaceDocumentId` 的授权规则
3. 然后把分片上传会话改成 team 强绑定
4. 最后统一 `team:manage` 与 creator-only 的语义

