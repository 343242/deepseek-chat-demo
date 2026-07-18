# 抽取独立 document 模块 — 设计方案

> 目标：新建顶层 `com.smart.rag.document` 模块，统管文档全生命周期（入库 + ETL + 归属），消除 `rag ↔ team` 双向循环依赖，并修复团队审批拒绝时原始文件成为孤儿垃圾的既有缺陷。
>
> 定位：本方案是 [ddd-domain-refactor.md](./ddd-domain-refactor.md) 中「Knowledge / RAG 有界上下文」的**可执行落地子集**。那份文档给出的是全局四层（interfaces / application / domain / infrastructure）的宏观蓝图，并明确「下一步应补迁移阶段拆分」。本文聚焦其中的文档域，给出精确到类、方法签名、SQL、提交顺序的落地方案，暂不触及 DDD 四层这一更大改动。
>
> 审批边界（已定）：team 先做额度 + 审批决策 → 审批通过后才调 document 入库；待审批文件暂存 MinIO `pending` 区，不入 `rag_document` 正式表。

---

## 1. 问题分析

### 1.1 当前 rag ↔ team 双向循环

经代码核实（基于 8 轮解耦重构后的状态），`rag` 与 `team` 仍存在双向 import：

| 方向 | 处数 | 典型证据 |
|---|---|---|
| `rag → team` | 9 | `rag/service/impl/DocumentApplicationServiceImpl.java:19-22` import `team.entity.TeamMember` / `team.enums.TeamMemberRole` / `team.service.TeamMembershipVerifier` / `team.upload.UploadStrategyFactory` |
| `team → rag` | 22 | `team/upload/TeamUploadStrategy.java:10-14` 直接 import `rag.entity.RagDocument` / `rag.mapper.RagDocumentMapper` / `rag.service.FileStorageService` / `rag.service.EtlDispatchService` 等 |

### 1.2 根因：领域归属不清，而非技术耦合

对抗性结论：常规的循环依赖解法（接口下沉 common、事件总线、SPI）在此**不根治**问题，因为根因不是技术耦合，而是 **`RagDocument` 这个领域实体的归属被劈成了两半**。

证据链：

1. **`RagDocument` 天然含 `teamId` 字段**（`rag/entity/RagDocument.java:46`）——文档实体本身就有团队维度，不是 team 强加的。「团队文档」在领域模型层就是文档的一部分。
2. **team 模块直接握 `RagDocumentMapper` 写库**（`team/upload/TeamUploadStrategy.java:225 ragDocumentMapper.insert(doc)`、`team/service/impl/TeamApprovalServiceImpl` 同样）——team 没有调 rag 的服务，而是把 rag 当数据层直接 CRUD。这是典型的上下游颠倒：team（业务上层）绕过领域 owner 直接操作其持久层。
3. **入库动作分裂在 3 处**（`PersonalUploadStrategy` / `TeamUploadStrategy` / `ChunkUploadServiceImpl.performMerge`），各自直接 `ragDocumentMapper.insert`，没有统一入口。
4. **归属判定一半在 SQL、一半在 Service 层 if**：`listAll` 用 SQL WHERE（干净）；`listByTeam`/`getById`/`delete` 把权限判定塞进 Service 层 `isOwnerOrManager` + 跨调 `teamMembershipVerifier`。
5. **权限规则重复 3 处**（`DocumentApplicationServiceImpl.isOwnerOrManager` / `DocumentSupersedeService.isOwner` / `team.security.DocumentOwnershipChecker`），且后者是**零调用死代码**。

### 1.3 既有缺陷：审批拒绝留孤儿文件

`TeamApprovalServiceImpl.review` 拒绝 / 超时时仅将 `rag_document.status` 改为 `REJECTED`（`:153`、`:236`），**MinIO 原始文件与 vector_store 不做任何清理**（REJECTED 不触发 ETL，故无向量，但原始文件留在正式 bucket 成垃圾）。`ApprovalTimeoutJob` 超时拒绝路径同样如此。

本次「审批前暂存 pending 区」的边界决策可一并根治此缺陷。

### 1.4 违反的设计原则

| 原则 | 体现 |
|---|---|
| **SRP** | `rag` 同时承担文档处理流水线（ETL）与文档归属管理；`team` 越权实现文档入库 |
| **DIP** | `rag`（领域 owner）反向依赖 `team`（消费方）的 `TeamMembershipVerifier`；team 直接握 rag 的 mapper |
| **DRY** | 权限规则三处重复 |
| **LoD** | team 直接 `ragDocumentMapper.insert/update`，深度操作 rag 内部持久层 |

---

## 2. 目标模块边界

### 2.1 三模块职责重划

```
document 模块（新建，文档全生命周期 owner）
├── ingest/     入库层：DocumentIngestService（统一入口，收编 Personal/Team/Chunk 三处 insert）
├── etl/        ETL 流水线：从 rag 迁入（归属无关的纯处理）
├── access/     归属与权限：DocumentAccessService（权限下沉 SQL JOIN）
├── lifecycle/  版本替代 + 级联删除：DocumentSupersedeService / DocumentLifecycleService
├── storage/    MinIO 文件存储：FileStorageService / BucketResolver（新增 pending→正式 流转）
├── parser/     文档解析：DocumentParser 全家族（10 个解析器）
├── chunk/      切片策略：ChunkStrategy 全家族
└── entity/mapper/dto/event  RagDocument / RagDocumentMapper / EtlStatus / DocumentUploadResponse / 各事件

rag 模块（瘦身，纯检索增强）
└── retrieval/  HybridSearchService / MmrPostProcessor / RerankPostProcessor / RetrievedDocument / QueryNormalizer / RagAdvisorFactory
   （仅读 document 写入的 vector_store；ETL/入库/归属类全部迁出）

team 模块（不碰文档存储）
├── TeamService / TeamMemberService（团队/成员/角色 CRUD）
├── TeamApprovalService（审批决策 → 调 DocumentIngestService 完成入库）
├── TeamQuotaService（额度 → 调 document 的"查询已用空间"接口）
└── 删除：TeamUploadStrategy / UploadStrategyFactory（职责归 document）/ TeamBucketCleaner / DocumentOwnershipChecker（死代码）
```

### 2.2 聚合根视角（与 ddd-domain-refactor.md 对齐）

ddd 文档第 4.3 节定义了 `KnowledgeDocument` 聚合根（方法含 `create/bindStorage/startParsing/.../transferOwnership`）。本次 document 模块的 `DocumentIngestService` + `DocumentAccessService` 即该聚合根的应用服务落地；`RagDocument` 实体保持现有字段（不动表结构），在其之上补应用层编排。**不引入 DDD 四层包结构**，保持现有 `document/{ingest,etl,access,...}` 扁平分包，降低本次改动面。

---

## 3. 数据流：当前 vs 目标

### 3.1 当前数据流（问题可视化）

```
前端 HTTP (/api/documents/upload?teamId=…)
  │ teamId==null                              │ teamId!=null
  ▼                                           ▼
[rag] PersonalUploadStrategy            [team] TeamUploadStrategy
  ─ MinIO 存文件                          ─ 额度校验(查rag_document!) ← team 读 rag 表
  ─ ragDocumentMapper.insert              ─ 审批判定
  ─ EtlDispatchService                    ─ ragDocumentMapper.insert ◄═ team 直接写 rag 表!
  ─ DocumentCreatedEvent                  ─ TeamUploadApproval.insert
                                          ─ (自动通过才) EtlDispatchService
        └──────► ETL 流水线 (纯 rag) ◄──────┘
                   Extract → Transform → Load(vector_store)
                   EtlStatusManager → rag_document.status

[rag] DocumentApplicationServiceImpl (CRUD)
  ─ listAll: SQL WHERE (干净)
  ─ listByTeam/getById/delete: Service 层 isOwnerOrManager + 调 team.TeamMembershipVerifier ◄═ rag→team
  ─ 权限规则重复 3 处 (含 team 死代码 DocumentOwnershipChecker)

         rag_document 表 (问题根源: 同时承载处理状态 + 归属 + 版本 + 存储)
```

### 3.2 目标数据流（document 独立后）

```
前端 HTTP (/api/documents/upload?teamId=…)
  │
  ▼
[document] DocumentIngestService (统一入库入口)
  ├─ 个人 / 自动通过: ingestDirect()
  │    ─ MinIO 正式区存文件
  │    ─ rag_document.insert (统一)
  │    ─ EtlDispatchService.dispatchAsync
  │
  └─ 待审批: stageForApproval()       ◄── 普通成员上传走此分支
       ─ MinIO pending 区暂存 (pending/{token})
       ─ 返回 pendingToken (不入 rag_document)
       │
       │  [team] TeamApprovalService.review()
       │    ├─ 通过 → document.confirmStaged(token)
       │    │         copyObject(pending→正式) + insert + ETL
       │    └─ 拒绝 → document.discardStaged(token)
       │              deleteObject(pending)  ◄── 修复孤儿文件缺陷
       │
       ▼
[document] ETL 流水线 (归属无关)
   Extract(DocumentParser) → Transform(ChunkStrategy) → Load(vector_store)
   EtlStatusManager → rag_document.status

[document] DocumentAccessService (权限下沉 SQL JOIN team_member)
   list/listTeam/get/delete → 一条 SQL 校验归属, 不再 Service 层 if, 不调 team 代码

         team ──► document (唯一耦合: 审批确认 + 额度查询)
         document 不依赖 team (权限用 SQL JOIN)
         rag 纯检索, 读 document 写入的 vector_store
```

### 3.3 耦合点收敛对照

| 当前 | 目标 |
|---|---|
| team→rag 22 处（含直接握 RagDocumentMapper） | team→document **2-3 处服务调用**（审批确认、额度查询） |
| rag→team 9 处（权限校验依赖 team） | **0 处**（权限下沉 SQL JOIN） |
| 入库逻辑分裂 3 处 | **1 处** DocumentIngestService |
| 权限规则重复 3 处 | **0 处重复**（SQL JOIN 或 document 内单一 access 服务） |
| 死代码 DocumentOwnershipChecker | 删除 |
| 审批拒绝留孤儿文件 | pending 区清理根治 |

---

## 4. 核心接口设计

> 所有签名已对照现有代码核实，`@Nullable` / 返回类型 / 参数顺序均与既有约定一致。

### 4.1 DocumentIngestService（统一入库入口）

```java
package com.smart.rag.document.ingest;

public interface DocumentIngestService {

    /** 个人 / 自动通过上传：直接入库 + 触发 ETL */
    DocumentUploadResponse ingestDirect(MultipartFile file, Long userId,
                                        @Nullable Long teamId,
                                        @Nullable Long replaceDocumentId);

    /**
     * 待审批上传：暂存 MinIO pending 区，返回 pendingToken（不入 rag_document 表）。
     * 供 team 审批流程在普通成员上传时调用。
     */
    PendingUpload stageForApproval(MultipartFile file, Long userId, Long teamId,
                                   @Nullable Long replaceDocumentId);

    /** 审批通过：pending 流转到正式区 + insert rag_document + 触发 ETL */
    DocumentUploadResponse confirmStaged(String pendingToken);

    /** 审批拒绝 / 超时：清理 pending 区文件（不入 rag_document）*/
    void discardStaged(String pendingToken);

    /**
     * 分片上传合并后入库（收编 ChunkUploadServiceImpl.performMerge 的入库段）。
     * mergedStream 为已合并的完整文件流。
     */
    DocumentUploadResponse ingestMerged(InputStream mergedStream, String fileName, String mimeType,
                                        long size, Long userId, @Nullable Long teamId,
                                        @Nullable Long replaceDocumentId);
}
```

`PendingUpload` 为只读 record（含 `pendingToken`、`fileName`、`size`、`teamId`、`userId`、`replaceDocumentId`）。

### 4.2 DocumentAccessService（权限下沉 SQL）

```java
package com.smart.rag.document.access;

public interface DocumentAccessService {

    /** 校验：当前用户能否读取某文档（失败抛 FORBIDDEN / NOT_FOUND）*/
    void assertCanRead(Long documentId, Long userId);

    /** 校验：当前用户能否变更某文档（delete / retry / supersede）*/
    void assertCanMutate(Long documentId, Long userId);

    /** 查询：当前用户在某团队的角色（用于 listByTeam 可见性分层，SQL JOIN team_member）*/
    @Nullable TeamMemberRole resolveRole(Long teamId, Long userId);

    /** 查询：某用户在团队已用上传空间（SQL SUM，供 team 额度校验）*/
    long usedBytes(Long teamId, Long userId);
}
```

`TeamMemberRole` 枚举：当前在 `team.enums`，重构后**保留在 team**（document 通过 SQL 读 `team_member.role` 数值 10/20/30 做判断，不 import team 的枚举类型；或在 document 内定义同值的只读 DTO 避免依赖）。

### 4.3 归属判定的 SQL 实现

**listByTeam 可见性分层**（非管理员只看自己上传 + 全队 COMPLETED）：

```sql
SELECT d.*
FROM rag_document d
LEFT JOIN team_member m
       ON m.team_id = d.team_id AND m.user_id = #{userId} AND m.status = 1
WHERE d.team_id = #{teamId}
  AND d.status <> 'SUPERSEDED'
  AND d.deleted = 0
  AND ( m.role IN (20, 30)            -- ADMIN/CREATOR 看全部
        OR d.user_id = #{userId}      -- 自己上传的任意状态
        OR d.status = 'COMPLETED' )   -- 全队 COMPLETED
ORDER BY d.create_time DESC
```

**assertCanRead / assertCanMutate**（一条 SQL 校验，替代 `isOwnerOrManager` 三处 if）：

```sql
SELECT EXISTS(
  SELECT 1 FROM rag_document d
  LEFT JOIN team_member m
         ON m.team_id = d.team_id AND m.user_id = #{userId} AND m.status = 1
  WHERE d.id = #{documentId} AND d.deleted = 0
    AND (
      -- 个人文档：仅本人
      (d.team_id IS NULL AND d.user_id = #{userId})
      OR
      -- 团队文档：是成员（读权限）；变更须 owner 或 ADMIN/CREATOR
      (d.team_id IS NOT NULL AND m.id IS NOT NULL
         AND (#{canMutate} = false
              OR d.user_id = #{userId}
              OR m.role IN (20, 30)))
    )
)
```

> 性能：`team_member` 已有唯一索引 `uk_team_user_active(team_id, user_id) WHERE status=1`（`V9__add_team.sql`），JOIN 命中索引。可见性分层的 `OR` 若影响查询计划，可拆 `UNION` 优化。

### 4.4 EtlDispatchService（从 rag 迁入，签名不变）

现有 `rag.service.EtlDispatchService` 接口（`dispatch` / `dispatchAsync` / `executeSingle` / `deleteVectors`）整体迁到 `document.etl`，方法签名零改动。

---

## 5. MinIO pending 区流转机制

### 5.1 存储位置

复用现有 `BucketResolver.resolve(teamId)` 返回的正式 bucket，在其下使用 `pending/` 路径前缀暂存：

- 待审批文件：`{teamBucket}/pending/{pendingToken}`
- 审批通过后正式文件：`{teamBucket}/{storageKey}`（与现有 TeamUploadStrategy 的 UUID storageKey 一致）

不新建独立 bucket，降低配置复杂度。`pendingToken = UUIDv7`（时序有序，便于按时间扫描孤儿）。

### 5.2 流转操作

- **通过**：`copyObject(bucket, pending/{token}, {storageKey})` + `deleteObject(bucket, pending/{token})`。MinIO `copyObject` 跨 key 同 bucket，原子且高效。
- **拒绝 / 超时**：`deleteObject(bucket, pending/{token})`。

### 5.3 FileStorageService 新增方法

现有接口（`rag.service.FileStorageService`）只有 `ensureBucketExists / upload / download / delete / presignedUrl`。新增：

```java
/** 同 bucket 内跨 key 复制（供 pending→正式 流转）*/
void copyObject(String bucket, String srcKey, String dstKey);

/** 同 bucket 内跨 key 移动（copy + delete 组合）*/
default void moveObject(String bucket, String srcKey, String dstKey) {
    copyObject(bucket, srcKey, dstKey);
    delete(bucket, srcKey);
}
```

`MinioFileStorageService` 用 MinIO Java SDK 的 `copyObject(CopyConditions)` 实现。

### 5.4 token 持久化与清理

- **持久化**：审批记录表 `team_upload_approval` 新增 `pending_token VARCHAR(40)` 字段（migration `V22__approval_pending_token.sql`）。审批通过时读 token 调 `confirmStaged`。
- **清理**：复用现有 `ApprovalTimeoutJob`（每小时跑），超时拒绝时调 `documentIngest.discardStaged(token)` 同步清理 pending 文件。
- **孤儿扫描（可选）**：新增 `PendingCleanupJob`（每日），扫 `pending/` 前缀下超过 N 天且无对应审批记录的 token 清理（防御崩溃丢失的 pending）。

---

## 6. 旧类迁移映射表

| 当前类 / 包 | 目标位置 | 备注 |
|---|---|---|
| `rag.entity.RagDocument` | `document.entity.RagDocument` | 聚合根实体，字段不变 |
| `rag.mapper.RagDocumentMapper` | `document.mapper.RagDocumentMapper` | 新增 listTeamWithVisibility / assertAccess 等 SQL |
| `rag.dto.DocumentDTO` / `DocumentUploadResponse` | `document.dto.` | |
| `rag.etl.EtlStatus` | `document.etl.EtlStatus` | 枚举 |
| `rag.event.{DocumentCreated,DocumentDeleted,EtlCompleted,EtlFailed}Event` | `document.event.` | |
| `rag.service.FileStorageService` + `MinioFileStorageService` | `document.storage.` | 新增 copyObject/moveObject |
| `rag.upload.BucketResolver` | `document.storage.BucketResolver` | |
| `rag.upload.UploadStrategy` + `PersonalUploadStrategy` | `document.ingest.` | PersonalUploadStrategy 核心入库逻辑收编进 DocumentIngestService |
| `rag.service.EtlDispatchService` + impl | `document.etl.` | 接口签名不变 |
| `rag.etl.*`（Consumer/Strategy*/Extractor/Transformer/Loader/StatusManager/Bridge/Candidate/Result） | `document.etl.` | 整体迁入 |
| `rag.parser.*`（10 解析器 + Factory） | `document.parser.` | |
| `rag.chunk.*`（ChunkStrategy 全家族） | `document.chunk.` | |
| `rag.service.DocumentApplicationService` + impl | `document.DocumentApplicationService` | CRUD，改调 DocumentAccessService |
| `rag.service.impl.DocumentLifecycleService` | `document.lifecycle.` | 级联删除 |
| `rag.service.impl.DocumentSupersedeService` | `document.lifecycle.` | 版本替代 |
| `rag.service.impl.DocumentValidator` | `document.ingest.` | |
| `rag.upload.OrphanChunkCleaner` | `document.storage.` | 斩断对 team.upload.TeamBucketCleaner 依赖 |
| **删除** `team.security.DocumentOwnershipChecker` | — | 死代码（零调用），权限归 DocumentAccessService |
| **删除** `team.upload.TeamUploadStrategy` | — | 入库逻辑归 document；team 不再实现 UploadStrategy |
| **删除** `team.upload.UploadStrategyFactory` | — | document 内 DocumentIngestService 内部分流 |
| **删除** `team.upload.TeamBucketCleaner` | — | 清理职责归 document（OrphanChunkCleaner 扩展） |
| **改造** `team.service.impl.TeamApprovalServiceImpl` | 保留原位 | review 通过→confirmStaged；拒绝→discardStaged；删除对 RagDocumentMapper/EtlDispatchService 的依赖 |
| **新建** `team.service.TeamQuotaService`（或并入 TeamMemberService） | `team.service.` | 额度校验调 documentAccess.usedBytes |
| **保留** `rag.retrieval.*` | 原位 | 纯检索，读 vector_store |
| **保留** `rag.config.RagAdvisorFactory` | 原位 | 依赖 HybridSearchService |

---

## 7. 执行顺序（8 个 commit，按依赖排序）

> 每个 commit 独立编译 + 测试通过，可分多次会话完成。遵循 AGENTS.md「编辑后提交并推送」。

### Commit 1（地基）：新建 document 模块 + 迁移纯数据类

迁移**无行为依赖的纯数据 / 枚举 / 事件**类（零风险，纯 package 改动）：

- `RagDocument` / `RagDocumentMapper` / `DocumentDTO` / `DocumentUploadResponse` / `EtlStatus` / 4 个 Event 类
- 全局更新 import（含 team 对这些类的引用，方向变为 team→document）
- 验证：`mvn compile` + 全量测试

### Commit 2：迁移 MinIO 存储层 + 新增 pending 流转

- `FileStorageService` + `MinioFileStorageService` + `BucketResolver` → `document.storage.`
- `UploadStrategy` 接口 + `PersonalUploadStrategy` → `document.ingest.`
- `FileStorageService` 新增 `copyObject` / `moveObject`（MinioFileStorageService 实现）
- 验证：编译 + PersonalUploadStrategy 测试

### Commit 3（核心）：新建 DocumentIngestService

- 新建 `DocumentIngestService` 接口 + impl
- 实现 `ingestDirect`（收编 PersonalUploadStrategy.upload 入库逻辑）
- 实现 `stageForApproval` / `confirmStaged` / `discardStaged`（pending 流转，**修复孤儿文件 bug**）
- `DocumentController.upload` 改调 `ingestDirect`（个人 / 自动通过场景）
- 验证：编译 + 新增 ingest 单测（覆盖 pending 三态）

### Commit 4：迁移 ETL 流水线到 document

- `rag.etl.*` / `rag.parser.*` / `rag.chunk.*` → `document.{etl,parser,chunk}.`
- `RagAdvisorFactory` 留 rag（依赖检索，不改）
- 验证：编译 + ETL 相关测试

### Commit 5（核心）：权限下沉 SQL，新建 DocumentAccessService

- 新建 `DocumentAccessService` 接口 + impl
- 实现 `assertCanRead` / `assertCanMutate` / `resolveRole` / `usedBytes`（SQL JOIN team_member）
- `RagDocumentMapper` 新增 SQL 方法（listTeamWithVisibility / assertAccess / selectFileSizeSum）
- `DocumentApplicationServiceImpl` 的 list/listTeam/get/delete/retry 改调 DocumentAccessService（**删除 isOwnerOrManager 三处重复**）
- `DocumentSupersedeService.isOwner` 改调 DocumentAccessService
- **删除** `team.security.DocumentOwnershipChecker`（死代码）
- 验证：编译 + 权限相关测试

### Commit 6（核心）：重构 team 审批流程，斩断 team→document mapper

- `TeamApprovalServiceImpl.review` 通过→`confirmStaged(token)`；拒绝→`discardStaged(token)`
- 删除 `TeamApprovalServiceImpl` 对 `RagDocumentMapper` / `EtlDispatchService` 的直接依赖
- `team_upload_approval` 表新增 `pending_token` 字段（migration `V22`）
- 新建 `TeamQuotaService`：额度校验调 `documentAccess.usedBytes`
- **删除** `team.upload.{TeamUploadStrategy, UploadStrategyFactory, TeamBucketCleaner}`
- `TeamServiceImpl` 不再直接依赖 `FileStorageService` / `BucketResolver`
- 验证：编译 + TeamApprovalServiceImpl 测试

### Commit 7：迁移文档生命周期管理

- `DocumentApplicationService` + impl → `document.`
- `DocumentLifecycleService` / `DocumentSupersedeService` → `document.lifecycle.`
- `DocumentValidator` → `document.ingest.`
- `OrphanChunkCleaner` → `document.storage.`（斩断 team.upload.TeamBucketCleaner 依赖）
- 验证：编译 + 全量测试

### Commit 8（收尾）：清理残留 + 架构验证

- `ChunkUploadServiceImpl` 删除对 `team.service.TeamStatusService` 的依赖（团队活跃校验改 SQL JOIN 或前置 controller）
- `evaluation.runner.EvaluationRunner` 更新对 document 类的引用
- 验证：
  - `grep "import com.smart.rag.team" src/main/java/com/smart/rag/document/` → 空
  - `grep "import com.smart.rag.rag" src/main/java/com/smart/rag/team/` → 空（team 只依赖 document）
  - `grep "import com.smart.rag.team" src/main/java/com/smart/rag/rag/` → 空
  - 全量 `mvn test` 通过

---

## 8. 风险与缓解

| 风险 | 级别 | 缓解 |
|---|---|---|
| Commit 6 审批流程改造（pending→正式 copy + DB 事务） | 高 | `confirmStaged` / `discardStaged` 用编程式事务；失败回滚 + MinIO 文件清理补偿；先写单测覆盖 pending/confirm/discard 三态 |
| listByTeam SQL JOIN 性能（OR 条件影响查询计划） | 中 | team_member 有 `uk_team_user_active` 索引，JOIN 命中；必要时拆 UNION |
| 审批顺序变更的前端兼容 | 中 | API 响应不变（仍返回 documentId + status）；但 REJECTED 不再有 rag_document 行——见 §9 决策点 |
| 总计 8 commit 跨多次会话 | 低 | 每 commit 独立可编译可测，中途状态可随时停下不破坏主干 |

---

## 9. 待决策点

> 以下两项影响接口细节，实施前需确认。

1. **pending_token 持久化位置**：本方案设计放 `team_upload_approval` 表新增字段（持久、审批超时任务可查）。备选：Redis 存 token→文件元数据（更快但崩溃易丢）。倾向 DB 字段。
2. **审批拒绝后是否保留 rag_document 行**：当前留 REJECTED 行（前端可能展示「曾被拒」记录）。新方案若 pending 区不入 rag_document，拒绝则无行。
   - 若前端需展示被拒记录：`discardStaged` 时写一条 REJECTED 元数据行（无文件、无 ETL）。
   - 若不需要：直接清理，rag_document 不留痕。

---

## 10. 与既有设计文档的关系

- [ddd-domain-refactor.md](./ddd-domain-refactor.md)：宏观 DDD 四层蓝图，定义 `KnowledgeDocument` 聚合根。**本文是其 Knowledge 域的可执行落地子集**，不触及四层包结构这一更大改动。
- [chunk-upload.md](./chunk-upload.md)：分片上传设计。本文 Commit 3 的 `ingestMerged` 收编其 `performMerge` 入库段，分片会话 / Redis / Lua 逻辑不变。
- [RAG-DESIGN.md](../RAG-DESIGN.md)：RAG 检索设计。本文将 rag 瘦身为纯检索，与该文档的检索职责定义一致。

本方案不修改数据库表结构（仅新增 `team_upload_approval.pending_token` 一个字段），不动 Flyway 既有 migration，遵循 AGENTS.md「schema 由 Flyway 管理」。
