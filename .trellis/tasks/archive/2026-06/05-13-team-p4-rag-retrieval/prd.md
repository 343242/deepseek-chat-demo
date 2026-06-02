# Phase 4: RAG 检索改造

> 父任务：05-13-team-collaboration
> 设计文档：`docs/TEAM-FEATURE-PRD.md` §6.5 + §8.3

## 目标

改造 RAG 检索链路，支持按 teamId 隔离检索团队文档。

## 前置条件

- Phase 3 完成（团队模块可用，文档可上传到团队空间）

## 交付物

### 1. ETL metadata 写入扩展（3 处）

| 文件 | 改动 |
|------|------|
| `StandardStrategy.execute()` | `if (c.teamId() != null) chunk.getMetadata().put("teamId", ...)` |
| `FastTrackStrategy.writeBm25Row()` | 签名加 teamId，metadata 包含 teamId |
| `FastTrackStrategy.asyncVectorize()` | chunk metadata 循环加 teamId |

### 2. RagAdvisorFactory 改造

- 删除 `advisorCache`（ConcurrentHashMap）
- `create(Long userId)` → `create(Long userId, @Nullable Long teamId)`
- `createRetriever(userId, teamId)` — 隔离维度参数化：
  - teamId=null → `filterBuilder.eq("userId", userId)`
  - teamId≠null → `filterBuilder.eq("teamId", teamId)`

### 3. HybridDocumentRetriever 改造

- 构造函数 `Long userId` → `String isolationField, String isolationValue`
- BM25 SQL 动态拼接：`AND metadata->>? = ?`
- 向量检索 filter 动态字段

### 4. ChatRequest record 扩展

- 新增 `Long teamId` 可选字段
- 更新 `withModel()` 方法

### 5. ChatController 变更

- GET 流式接口增加可选 `teamId` query param

### 6. ChatServiceImpl 变更

- `chat()` / `chatStream()` 传递 `request.teamId()` 给 `ChatAdvisorChainFactory`

### 7. ChatAdvisorChainFactory 变更

- `buildChain()` 从 `request.teamId()` 取值
- teamId 有值时先调 `TeamMembershipVerifier.verifyMember(teamId, userId)`
- 传入 `ragAdvisorFactory.create(userId, teamId)`

### 8. DocumentProperties 扩展

新增配置项：
```yaml
app:
  team:
    approval-timeout-days: 7
    default-creator-upload-limit-mb: 200
    default-member-upload-limit-mb: 50
    max-members-per-team: 50
    max-teams-per-user: 10
```

## 验收标准

- [ ] 向量数据包含 teamId metadata
- [ ] 个人 RAG 检索只返回个人文档
- [ ] 团队 RAG 检索只返回团队文档
- [ ] 非团队成员检索团队文档 → 403
- [ ] ChatRequest.withModel() 保留 teamId
- [ ] 混合检索（hybrid）BM25 + 向量都按隔离维度过滤
- [ ] 所有 JUnit 测试通过
- [ ] 端到端测试：上传团队文档 → ETL → RAG 检索 → 只返回该团队文档
