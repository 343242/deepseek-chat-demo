# Phase 1: 共享组件 + EtlCandidate 改造

> 父任务：05-13-team-collaboration
> 设计文档：`docs/TEAM-FEATURE-PRD.md` §8.1 + §8.2

## 目标

提取/创建团队功能所需的共享组件，**不改业务逻辑**，为后续 Phase 打基础。

## 交付物

### 1. DocumentOwnershipChecker（新增）

路径：`com.demo.chat.team.security.DocumentOwnershipChecker`

```java
@Component
public class DocumentOwnershipChecker {
    private final RagDocumentMapper ragDocumentMapper;
    private final TeamMembershipVerifier teamMembershipVerifier;

    /**
     * 统一文档操作权限校验（getById / delete / retry 通用）
     * - 个人文档（teamId=null）：userId == doc.userId
     * - 团队文档（teamId≠null）：团队成员 + (CREATOR/ADMIN 或 文档上传者)
     */
    public RagDocument checkOwnership(Long documentId, Long userId) { ... }
}
```

**注意：** 此阶段 `TeamMembershipVerifier` 尚未实现，`checkOwnership` 先实现个人文档分支（`teamId == null`），团队分支抛 `BusinessException(NOT_TEAM_MEMBER)` 占位。

### 2. TeamMembershipVerifier 骨架（新增）

路径：`com.demo.chat.team.service.TeamMembershipVerifier`

```java
@Component
public class TeamMembershipVerifier {
    // Phase 3 实现具体逻辑，本阶段只创建文件 + 注入依赖
    public TeamMember verifyMember(Long teamId, Long userId) {
        throw new BusinessException(ErrorCode.NOT_TEAM_MEMBER, "团队功能尚未实现");
    }
    public TeamMember verifyAdmin(Long teamId, Long userId) { ... }
    public TeamMember verifyCreator(Long teamId, Long userId) { ... }
}
```

### 3. EtlCandidate record 扩展

路径：`com.demo.chat.rag.etl.EtlCandidate`

record 末尾新增 `@Nullable Long teamId` 字段。

**所有构造点更新（7 处）：**

| 文件 | 方法 | 改动 |
|------|------|------|
| `DocumentApplicationServiceImpl` | `upload()` | 末尾加 `doc.getTeamId()` |
| `DocumentApplicationServiceImpl` | `uploadBatch()` | 末尾加 `doc.getTeamId()` |
| `EtlDispatchServiceImpl` | `executeSingle()` | 签名加 `@Nullable Long teamId`，末尾加 `teamId` |
| `EtlDispatchServiceImpl` | `dispatchAsync()` | 签名加 `@Nullable Long teamId`，末尾加 `teamId` |
| `ChunkUploadServiceImpl` | `performMerge()` | 从 `doc.getTeamId()` 读取，传给 dispatchAsync |
| `DocumentApplicationServiceImpl` | `retry()` | 从 `doc.getTeamId()` 读取，传给 dispatchAsync |

### 4. EtlStatus 枚举扩展

路径：`com.demo.chat.rag.etl.EtlStatus`

新增：
```java
PENDING_APPROVAL("PENDING_APPROVAL"),
REJECTED("REJECTED");
```

### 5. ErrorCode 55xxx 段（新增 15 个）

路径：`com.demo.chat.common.errorcode.ErrorCode`

在 RAG 50xxx 段后新增注释 `// ==================== 团队 55xxx ====================`，添加：
```java
TEAM_NOT_FOUND(55001, "团队不存在"),
TEAM_NAME_DUPLICATE(55002, "团队名称已存在"),
NOT_TEAM_MEMBER(55003, "不是团队成员"),
NOT_TEAM_ADMIN(55004, "不是团队管理员/创建者"),
NOT_TEAM_CREATOR(55005, "不是团队创建者"),
ALREADY_TEAM_MEMBER(55006, "已经是团队成员"),
CREATOR_CANNOT_LEAVE(55007, "创建者不能退出团队"),
CANNOT_CHANGE_CREATOR_ROLE(55008, "不能修改创建者角色"),
UPLOAD_QUOTA_EXCEEDED(55009, "上传文件超出团队额度"),
UPLOAD_LIMIT_OUT_OF_RANGE(55010, "上传额度设置超出范围"),
APPROVAL_NOT_FOUND(55011, "审批记录不存在"),
APPROVAL_ALREADY_PROCESSED(55012, "审批已处理"),
NO_PERMISSION_DELETE_TEAM_DOC(55013, "无权删除团队文档"),
TEAM_LIMIT_EXCEEDED(55014, "用户团队数超限"),
TEAM_MEMBER_LIMIT_EXCEEDED(55015, "团队成员数超限"),
```

### 6. EtlDispatchService 接口签名扩展

路径：`com.demo.chat.rag.service.EtlDispatchService`

`executeSingle()` 和 `dispatchAsync()` 签名末尾加 `@Nullable Long teamId` 参数。

### 7. 团队枚举类（3 个新增）

路径：`com.demo.chat.team.enums`

```java
// TeamMemberRole.java
public enum TeamMemberRole {
    MEMBER(10), ADMIN(20), CREATOR(30);
    @EnumValue private final int code;
}

// TeamStatus.java
public enum TeamStatus {
    DISABLED(0), ENABLED(1);
    @EnumValue private final int code;
}

// ApprovalStatus.java
public enum ApprovalStatus {
    PENDING(0), APPROVED(1), REJECTED(2);
    @EnumValue private final int code;
}
```

## 验收标准

- [ ] 所有现有测试通过（`./mvnw test`）
- [ ] `DocumentOwnershipChecker` 个人文档分支正常工作
- [ ] `EtlCandidate` 新增 teamId 字段后所有 7 处构造点编译通过
- [ ] `EtlStatus` 新增 2 个枚举值
- [ ] `ErrorCode` 新增 15 个 55xxx 枚举值
- [ ] 3 个团队枚举类编译通过
- [ ] `TeamMembershipVerifier` 骨架存在

## 不做的事

- 不改上传逻辑
- 不改 RAG 检索逻辑
- 不改 Controller
- 不写团队业务代码
