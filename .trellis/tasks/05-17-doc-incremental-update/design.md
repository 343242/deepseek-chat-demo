# 文档增量更新方案设计

> 日期：2026-05-18（v3 — 二次审查修订）
> 状态：READY FOR IMPLEMENTATION

## 1. 背景与问题

### 1.1 现状

当前文档处理链路是**全量式**的：

```
上传 → MD5秒传(完全相同则跳过) → ETL(全量解析→分块→写入vector_store) → COMPLETED
```

涉及的数据存储：

| 存储 | 内容 | 按 documentId 清理能力 |
|---|---|---|
| `rag_document` 表 | 文档元数据 | ✅ `deleteById`（逻辑删除） |
| `vector_store` 表 | chunk embedding + metadata | ✅ `VectorStoreLoader.deleteByDocumentId`（filter 删除，覆盖所有含该 documentId 的行，包括 embedding=NULL 的 BM25-only 行） |
| `vector_store` 表 | BM25 fastTrack 原文行 | ✅ `VectorStoreMapper.deleteFastTrackRows` |
| `vector_store` 表 | BM25 content_tsv（chunk 级） | ✅ 随 chunk 行一起被 `deleteByDocumentId` 删除 |
| MinIO | 原始文件 | ✅ `FileStorageService.delete` |

### 1.2 问题

当同一文档内容发生变化（MD5 不同）时：

1. **旧版本残留**：旧文档的 chunks/vectors/BM25 索引永久残留，检索时新旧版本同时命中
2. **无版本语义**：无法知道文档更新了几次、当前是哪个版本
3. **存储浪费**：MinIO 中旧版本文件不会被清理
4. **检索质量下降**：同一文档的旧 chunk 可能与新 chunk 竞争排名位置

### 1.3 设计目标

- **替换式更新**：用户显式指定替换目标文档，内容变化后自动替换为新版本
- **原子性**：新版本 ETL 完成后才清理旧版本数据
- **可追溯**：保留版本号和替换关系
- **向后兼容**：现有 API 不传 `replaceDocumentId` 时行为完全不变
- **OCP 合规**：通过事件驱动解耦，零修改现有上传策略核心逻辑

## 2. 方案设计

### 2.1 核心思路：用户显式指定替换（事件驱动）

**v3 关键变更（B1 修复）**：不再按文件名自动判定"同名文档"，改为 API 层传 `replaceDocumentId` 参数，用户在前端选择"更新此文档"时传旧文档 ID。

**优势**：
- 零误判 — 不存在"两个不相关的同名文件被错误替换"
- 跨文件名更新 — `report-draft.pdf` → `report-final.pdf` 也能关联
- 实现简单 — `findById(replaceDocumentId)` 比 `findByName+owner` 可靠

**不做 chunk 级 diff**（ROI 太低，embedding 层面无法确定哪些段落变了），而是：
- 用户传 `replaceDocumentId` 指定要替换的旧文档
- 新文档 ETL 成功后 → 清理旧文档的 vectors + BM25 + MinIO 文件
- 旧文档标记为 `SUPERSEDED` 状态
- 整个流程通过 Spring ApplicationEvent 事件驱动，不修改现有上传策略

### 2.2 数据模型变更

#### 2.2.1 EtlStatus 新增枚举值

```java
public enum EtlStatus {
    // ... 现有值不变
    SUPERSEDED("SUPERSEDED");  // 被新版本替代
}
```

**SUPERSEDED 状态机规则（H2 修复）**：

| 源状态 → SUPERSEDED | 允许 | 说明 |
|---|---|---|
| COMPLETED → SUPERSEDED | ✅ | 正常替换 |
| FAILED / VECTOR_FAILED → SUPERSEDED | ✅ | 失败文档被新版本替代 |
| PROCESSING / PARSING / CHUNKING / VECTORIZING → SUPERSEDED | ✅ | 正在 ETL 的文档被新版本替代，新文档 ETL 完成后清理旧向量 |
| UPLOADED → SUPERSEDED | ✅ | 刚上传未开始 ETL 被替代 |
| SUPERSEDED → 任何 | ❌ | 已被替代的文档不可再变更 |

**关键**：ETL 进行中的文档也可被替代。因为 `onEtlCompleted` 在新文档 ETL 完成后执行清理，此时旧文档的 ETL 可能也完成了（两次 ETL 并行），清理逻辑按 `documentId` 精确删除旧文档的 chunks，不影响新文档。

#### 2.2.2 rag_document 表新增字段

```sql
-- V14__document_incremental_update.sql

-- 文档版本号（每次文档更新自增）
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

-- 被哪个文档替代（null = 当前版本或初始上传）
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS superseded_by BIGINT;

-- 文档逻辑标识（同一文档的不同版本共享同一值）
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS document_group_id VARCHAR(36);

-- 唯一约束：同一文档组内版本号不重复（防并发冲突）
CREATE UNIQUE INDEX IF NOT EXISTS uq_rag_document_group_version
    ON rag_document (document_group_id, version)
    WHERE deleted = 0 AND document_group_id IS NOT NULL;

-- 辅助索引
CREATE INDEX IF NOT EXISTS idx_rag_document_group
    ON rag_document (document_group_id)
    WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_rag_document_superseded_by
    ON rag_document (superseded_by)
    WHERE superseded_by IS NOT NULL;

COMMENT ON COLUMN rag_document.version IS '文档版本号，替换时自增';
COMMENT ON COLUMN rag_document.superseded_by IS '被替代为哪个文档ID，NULL表示当前版本';
COMMENT ON COLUMN rag_document.document_group_id IS '文档逻辑标识，同一文档的不同版本共享（UUIDv7）';

-- ============================================================
-- 存量数据回填：为每个已有文档分配独立的 document_group_id
-- ============================================================
-- 存量文档每个都是独立版本，不需要关联（因为没有 replaceDocumentId 参数的历史数据）
UPDATE rag_document
SET document_group_id = id::TEXT
WHERE document_group_id IS NULL AND deleted = 0;
```

**字段说明**：

| 字段 | 说明 | 示例 |
|---|---|---|
| `version` | 版本号，同一 document_group_id 内递增 | 1, 2, 3 |
| `superseded_by` | 被哪个 doc.id 替代，NULL = 当前活跃版本 | 42 |
| `document_group_id` | 文档逻辑分组 ID | `01912ab...`（UUIDv7） |

**设计决策**：

- `document_group_id` 而非直接用 `file_name + user_id` 关联：避免文件名修改时断链，且支持跨文件名更新
- `superseded_by` 而非只看 `version`：可以直接从任意版本跳到最新版本
- `document_group_id + version` 唯一约束：防并发上传时版本号冲突
- 不删除旧版本记录：保留审计追溯能力

### 2.3 事件驱动架构

#### 2.3.1 新增事件

```java
// event.rag.com.smart.rag.DocumentCreatedEvent
// 文档记录创建后发出（所有上传路径统一触发）
public record DocumentCreatedEvent(
    Long documentId,
    @Nullable Long replaceDocumentId,  // v3 新增：用户指定的替换目标
    Long userId,
    @Nullable Long teamId
) {}

// event.rag.com.smart.rag.EtlCompletedEvent
// ETL 处理成功后发出
public record EtlCompletedEvent(
    Long documentId,
    Long userId,
    @Nullable Long teamId
) {}
```

#### 2.3.2 事件发布点

| 发布者 | 事件 | 时机 | 改动量 |
|---|---|---|---|
| `PersonalUploadStrategy` | `DocumentCreatedEvent` | `persistDocument` 之后 | +1 行 |
| `TeamUploadStrategy` | `DocumentCreatedEvent` | 文档记录创建之后 | +1 行 |
| `ChunkUploadServiceImpl` | `DocumentCreatedEvent` | `complete` 合并成功后 | +1 行 |
| `EtlDispatchServiceImpl` | `EtlCompletedEvent` | ETL 异步完成后 status=COMPLETED 时 | +1 行 |

**关键**：`replaceDocumentId` 从 API 层透传到事件，事件消费者据此决定是否走替换流程。

#### 2.3.3 事件消费：DocumentSupersedeService

```java
// impl.service.rag.com.smart.rag.DocumentSupersedeService
// 职责单一：处理文档版本替换逻辑

@Service
public class DocumentSupersedeService {

    private final RagDocumentMapper ragDocumentMapper;
    private final VectorStoreMapper vectorStoreMapper;
    private final VectorStoreLoader vectorStoreLoader;
    private final FileStorageService fileStorageService;
    private final TransactionTemplate transactionTemplate;

    // 记录待替换关系：newDocId → oldDocId（ETL 完成后执行替换）
    // 启动补偿：应用启动时扫描并补偿未完成的 pendingSupersede
    private final ConcurrentHashMap<Long, Long> pendingSupersede = new ConcurrentHashMap<>();

    /**
     * 监听文档创建事件：根据 replaceDocumentId 建立版本关系
     */
    @EventListener
    @Async("etlIoExecutor")
    public void onDocumentCreated(DocumentCreatedEvent event) {
        try {
            if (event.replaceDocumentId() == null) {
                // 无替换目标 → 新文档，分配新的 document_group_id
                assignNewGroupId(event.documentId());
                return;
            }

            // 有替换目标 → 验证旧文档存在且属于当前用户
            RagDocument oldDoc = ragDocumentMapper.selectById(event.replaceDocumentId());
            if (oldDoc == null || oldDoc.getDeleted() == 1) {
                log.warn("Replace target not found or deleted: replaceDocumentId={}, degrading to new document",
                         event.replaceDocumentId());
                assignNewGroupId(event.documentId());
                return;
            }

            // 安全校验：旧文档必须属于当前用户（个人）或当前团队
            if (!isOwner(oldDoc, event.userId(), event.teamId())) {
                log.warn("Replace target ownership mismatch: replaceDocumentId={}, userId={}, teamId={}",
                         event.replaceDocumentId(), event.userId(), event.teamId());
                assignNewGroupId(event.documentId());
                return;
            }

            // 建立版本关系
            linkVersion(event.documentId(), oldDoc);

        } catch (Exception e) {
            log.warn("Supersede setup failed for docId={}, degrading to new document: {}",
                     event.documentId(), e.getMessage());
            assignNewGroupId(event.documentId());
        }
    }

    /**
     * 监听 ETL 完成事件：执行旧版本替换
     */
    @EventListener
    @Async("etlIoExecutor")
    public void onEtlCompleted(EtlCompletedEvent event) {
        Long oldDocId = pendingSupersede.remove(event.documentId());
        if (oldDocId == null) {
            return; // 非增量更新文档，跳过
        }
        supersedeOldVersion(oldDocId, event.documentId());
    }

    /**
     * 应用启动补偿（M1 修复）
     * 扫描 superseded_by IS NOT NULL 但旧文档 status != SUPERSEDED 的记录，
     * 补偿因重启丢失的 pendingSupersede。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingSupersede() {
        // 查找所有 status=COMPLETED 且 superseded_by IS NOT NULL 的旧文档
        // 这些文档理论上应该已经被标记为 SUPERSEDED，但可能因重启丢失
        List<RagDocument> staleDocs = ragDocumentMapper.findStaleSupersededTargets();
        for (RagDocument doc : staleDocs) {
            log.info("Recovering stale supersede: oldDocId={}, supersededBy={}", doc.getId(), doc.getSupersededBy());
            supersedeOldVersion(doc.getId(), doc.getSupersededBy());
        }
    }

    // === 内部方法 ===

    private boolean isOwner(RagDocument doc, Long userId, @Nullable Long teamId) {
        if (teamId != null) {
            return teamId.equals(doc.getTeamId());
        }
        return userId.equals(doc.getUserId());
    }

    private void assignNewGroupId(Long documentId) {
        String groupId = UUIDv7Generator.generate();  // 使用项目已有的 UUIDv7
        ragDocumentMapper.updateGroupId(documentId, groupId);
        log.debug("New document group assigned: docId={}, groupId={}", documentId, groupId);
    }

    /**
     * 建立版本关系 + 注册待替换
     */
    private void linkVersion(Long newDocId, RagDocument oldDoc) {
        int retryCount = 0;
        int maxRetry = 3;

        while (retryCount < maxRetry) {
            try {
                String groupId = oldDoc.getDocumentGroupId();
                if (groupId == null) {
                    // 旧文档没有 groupId（存量数据未回填或极端场景）
                    groupId = UUIDv7Generator.generate();
                    ragDocumentMapper.updateGroupId(oldDoc.getId(), groupId);
                }
                int nextVersion = oldDoc.getVersion() + 1;

                ragDocumentMapper.updateGroupIdAndVersion(newDocId, groupId, nextVersion);

                // 注册待替换关系（ETL 完成后清理旧版本）
                pendingSupersede.put(newDocId, oldDoc.getId());

                log.info("Document version linked: newDocId={}, oldDocId={}, groupId={}, version={}",
                         newDocId, oldDoc.getId(), groupId, nextVersion);
                return;

            } catch (DuplicateKeyException e) {
                log.info("Version conflict for groupId={}, retrying ({}/{})",
                         oldDoc.getDocumentGroupId(), retryCount + 1, maxRetry);
                retryCount++;
                oldDoc = ragDocumentMapper.selectById(oldDoc.getId());
                if (oldDoc == null) {
                    assignNewGroupId(newDocId);
                    return;
                }
            }
        }

        log.warn("Version conflict retry exhausted for newDocId={}, degrading to new document", newDocId);
        assignNewGroupId(newDocId);
    }

    /**
     * 执行旧版本替换（事务控制 + 独立清理）
     */
    private void supersedeOldVersion(Long oldDocId, Long newDocId) {
        // 步骤 1: 事务内更新旧文档状态
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ragDocumentMapper.updateSuperseded(oldDocId, newDocId);
            });
        } catch (Exception e) {
            log.error("Failed to mark old doc as SUPERSEDED: oldDocId={}, skipping cleanup: {}", oldDocId, e.getMessage());
            return;
        }

        // 步骤 2: 清理旧文档的 vectors（覆盖 embedding + BM25 chunk 级 + fastTrack）
        try {
            vectorStoreLoader.deleteByDocumentId(oldDocId);
        } catch (Exception e) {
            log.error("Failed to delete vectors for superseded docId={}: {}", oldDocId, e.getMessage());
        }

        try {
            vectorStoreMapper.deleteFastTrackRows(oldDocId);
        } catch (Exception e) {
            log.error("Failed to delete BM25 fastTrack for superseded docId={}: {}", oldDocId, e.getMessage());
        }

        // 步骤 3: 清理旧文档的 MinIO 文件
        RagDocument oldDoc = ragDocumentMapper.selectById(oldDocId);
        if (oldDoc != null) {
            try {
                fileStorageService.delete(oldDoc.getBucket(), oldDoc.getStorageKey());
            } catch (Exception e) {
                log.error("Failed to delete MinIO file for superseded docId={}: {}", oldDocId, e.getMessage());
            }
        }

        log.info("Document superseded: oldDocId={} → newDocId={}", oldDocId, newDocId);
    }
}
```

### 2.4 更新流程时序

```
用户选择"更新此文档" → 上传新文件（API: replaceDocumentId=42）
    │
    ├─ 1. MD5 秒传检查（完全相同 → 秒传，不变）
    │
    ├─ 2. 正常创建文档记录（与首次上传完全一致）
    │      └─ status = UPLOADED, document_group_id = NULL, version = 1
    │
    ├─ 3. publishEvent(DocumentCreatedEvent(replaceDocumentId=42))
    │
    ├─ 4. DocumentSupersedeService.onDocumentCreated() 异步处理：
    │      ├─ replaceDocumentId != null → selectById(42) 验证存在+归属
    │      ├─ 继承旧文档的 documentGroupId
    │      ├─ 设置 newDoc.version = oldDoc.version + 1
    │      └─ 注册 pendingSupersede(newDocId → 42)
    │
    ├─ 5. ETL 异步执行（不变）
    │
    ├─ 6. ETL 完成 → publishEvent(EtlCompletedEvent)
    │
    └─ 7. DocumentSupersedeService.onEtlCompleted() 异步处理：
           ├─ 旧文档 status=SUPERSEDED, superseded_by=新ID
           ├─ 清理旧 vectors + BM25（独立 try-catch）
           └─ 清理旧 MinIO 文件（独立 try-catch）
```

**不传 replaceDocumentId 时**：行为与当前完全一致，新建文档 + 分配新 groupId。

### 2.5 关键时序保证

**核心原则：新版本 ETL 成功后才清理旧版本**

```
时间线：
  T1: 新文档插入 → status=PROCESSING, 旧文档不受影响
  T2: DocumentSupersedeService 异步建立版本关系
  T3: ETL 开始（新 chunk 写入 vector_store）
  T4: ETL 完成 → status=COMPLETED
  T5: DocumentSupersedeService 执行旧版本替换 ← 此时才清理
```

如果在 T3-T4 之间 ETL 失败：
- 新文档 status=FAILED
- `pendingSupersede` 中的记录不会被消费（`EtlCompletedEvent` 不会发出）
- 旧文档保持 COMPLETED 不变
- 检索仍然命中旧版本 → **数据一致性不受影响**

**M1 补偿**：如果应用在 T4-T5 之间重启：
- `pendingSupersede` 内存数据丢失
- 应用重启后 `recoverPendingSupersede()` 扫描并补偿
- 旧文档的 vectors 最终会被清理

### 2.6 替换目标的判定规则（v3 修订）

| 场景 | 判定方式 | 说明 |
|---|---|---|
| 个人文档替换 | `replaceDocumentId` → `selectById` → 校验 `userId` | 用户显式指定 |
| 团队文档替换 | `replaceDocumentId` → `selectById` → 校验 `teamId` | 用户显式指定 |
| 无替换意图 | `replaceDocumentId = null` | 新建文档，行为不变 |

**安全校验**：旧文档必须属于当前用户（个人）或当前团队（团队），防止越权替换。

## 3. 异常处理策略

| 场景 | 处理方式 | 理由 |
|---|---|---|
| 替换目标不存在/已删除 | 降级为新文档（assignNewGroupId） | 增量更新非核心功能，不阻塞上传 |
| 替换目标归属不匹配 | 降级为新文档 | 安全兜底 |
| 旧文档无 groupId | 生成新 groupId 并赋给旧文档，新文档继承 | 兼容存量数据 |
| 版本号唯一约束冲突 | 重查最新 version 重试，最多 3 次 | 覆盖并发场景 |
| 重试 3 次仍冲突 | 降级为新文档 | 不阻塞上传 |
| 标记 SUPERSEDED 失败 | 日志 error + 跳过清理 | 旧数据保持不变 |
| 清理旧 vectors 失败 | 日志 error + 继续后续步骤 | 与 cascadeDelete 容错一致 |
| 清理旧 MinIO 失败 | 日志 error + 继续 | 同上 |
| 全部清理失败 | 新文档 COMPLETED + 旧文档仍活跃 | 检索命中两版本，可接受 |

**降级原则**：增量更新的任何失败都不应阻塞文档上传和 ETL 的核心流程。

## 4. 代码变更清单

### 4.1 数据层

| 文件 | 变更 |
|---|---|
| **新增** `V14__document_incremental_update.sql` | 新增字段 + 唯一约束 + 索引 + 存量回填 |
| `RagDocument.java` | 新增 `version`, `supersededBy`, `documentGroupId` 字段 |
| `EtlStatus.java` | 新增 `SUPERSEDED` 枚举值 |
| `RagDocumentMapper.java` | 新增 `updateGroupId`, `updateGroupIdAndVersion`, `updateSuperseded`, `findStaleSupersededTargets` 方法 |

### 4.2 事件层（新增）

| 文件 | 说明 |
|---|---|
| **新增** `event.rag.com.smart.rag.DocumentCreatedEvent` | 文档创建事件（含 `replaceDocumentId`） |
| **新增** `event.rag.com.smart.rag.EtlCompletedEvent` | ETL 完成事件 |

### 4.3 服务层

| 文件 | 变更 |
|---|---|
| **新增** `DocumentSupersedeService` | 替换逻辑编排（事件监听 + 版本关联 + 旧版本清理 + 启动补偿） |
| `PersonalUploadStrategy.java` | **+1 行** `publishEvent(new DocumentCreatedEvent(...))` |
| `TeamUploadStrategy.java` | **+1 行** `publishEvent(new DocumentCreatedEvent(...))` |
| `ChunkUploadServiceImpl.java` | **+1 行** `publishEvent(new DocumentCreatedEvent(...))` 在 complete 阶段 |
| `EtlDispatchServiceImpl.java` | **+1 行** `publishEvent(new EtlCompletedEvent(...))` 在异步完成回调中 |

### 4.4 API 层

| 文件 | 变更 |
|---|---|
| `DocumentDTO.java` | 新增 `version`, `supersededBy`, `documentGroupId` 字段 |
| `DocumentController.java` | 新增 `GET /api/documents/{id}/history` 版本历史查询；upload 方法增加可选参数 `replaceDocumentId` |
| `UploadStrategy` 接口 | `upload` 方法签名增加 `@Nullable Long replaceDocumentId` 参数 |
| `PersonalUploadStrategy` / `TeamUploadStrategy` | `upload` 方法透传 `replaceDocumentId` |

#### 4.4.1 API 变更

**上传接口增加可选参数**：
```
POST /api/documents/upload?replaceDocumentId=42
POST /api/documents/upload?replaceDocumentId=42&teamId=1
```

不传 `replaceDocumentId` 时行为完全不变（向后兼容）。

**版本历史 API**：
```
GET /api/documents/{id}/history

权限: @PreAuthorize("isAuthenticated()") + verifyAccess（同文档查看权限）

响应: GlobalResponse<List<DocumentDTO>>
[
  {
    "id": 42,
    "fileName": "report.pdf",
    "version": 3,
    "status": "COMPLETED",
    "documentGroupId": "01912ab...",
    "createTime": "2026-05-17T12:00:00+08:00"
  },
  {
    "id": 35,
    "fileName": "report.pdf",
    "version": 2,
    "status": "SUPERSEDED",
    "supersededBy": 42,
    "createTime": "2026-05-16T10:00:00+08:00"
  }
]
```

### 4.5 现有查询适配

- `DocumentApplicationServiceImpl.listAll()` — 已通过 `status` 过滤，SUPERSEDED 不在默认列表
- `DocumentApplicationServiceImpl.getById()` — SUPERSEDED 文档仍可查看（通过 history API 也能查到）
- `findExistingForQuickUpload()` — 已通过 `status` 过滤，SUPERSEDED 不会命中秒传
- `DocumentApplicationServiceImpl.retry()` — 新增校验：SUPERSEDED 状态不允许重试
- **检索侧（H1 修复）**：旧文档 SUPERSEDED 后，`vectorStoreLoader.deleteByDocumentId()` 会立即删除其所有 chunks，确保检索不再命中旧版本。这是唯一防线 — 无需在检索侧额外过滤 SUPERSEDED 状态。

## 5. 边界场景

| 场景 | 处理方式 |
|---|---|
| 并发上传替换同一旧文档 | `document_group_id + version` 唯一约束兜底，冲突重试 3 次，耗尽则降级 |
| 旧文档正在 ETL 中被替换 | 新文档 ETL 完成后清理旧文档的 chunks，旧文档的 ETL 可能已完成但 chunks 被删 |
| 链式替换 v1→v2→v3 | 每层独立 supersede，新文档继承旧文档的 groupId |
| 分片上传的增量更新 | 在 complete 阶段（合并成功后）发事件，含 `replaceDocumentId` |
| 存量文档 | V14 迁移脚本回填 `document_group_id = id::TEXT`，后续上传可正常指定替换 |
| `replaceDocumentId` 指向不存在的文档 | 降级为新文档，assignNewGroupId |
| `replaceDocumentId` 指向他人文档 | 安全校验不通过，降级为新文档 |

## 6. 与现有机制的协同

### 6.1 MD5 秒传（不变）

MD5 完全相同 → 秒传命中 → 返回已有文档 ID。
这与增量更新**互补**：
- MD5 相同 → 秒传（零成本）
- MD5 不同 + replaceDocumentId → 增量更新（替换旧版本）
- MD5 不同 + 无 replaceDocumentId → 正常上传

### 6.2 文档删除（不变）

删除 SUPERSEDED 状态的文档 → `cascadeDelete` 照常工作。
删除当前活跃版本 → 正常清理 vectors + MinIO。

### 6.3 文档重试（适配）

`retry()` 方法新增校验：如果文档 `supersededBy != null`，抛 `BusinessException`（"文档已被新版本替代，无法重试"）。

## 7. 实施计划

### Phase 1: 数据模型 + 基础设施（~30min）
- V14 迁移脚本（字段 + 唯一约束 + 索引 + 存量回填）
- RagDocument 实体新增字段
- EtlStatus 新增 SUPERSEDED
- RagDocumentMapper 新增方法
- 编译验证

### Phase 2: 事件 + 替换服务（~45min）
- 新增 DocumentCreatedEvent / EtlCompletedEvent
- 新增 DocumentSupersedeService（核心逻辑）
- 编译验证

### Phase 3: 事件发布接入 + API 适配（~30min）
- PersonalUploadStrategy / TeamUploadStrategy / ChunkUploadServiceImpl 各 +1 行
- EtlDispatchServiceImpl +1 行
- UploadStrategy 接口增加 replaceDocumentId 参数
- DocumentController 增加 replaceDocumentId 可选参数
- DocumentDTO 新增字段
- DocumentController 新增 history 端点
- retry() 新增 SUPERSEDED 校验
- 编译验证

### Phase 4: 测试验证（~30min）
- 单元测试：显式替换、版本号递增、唯一约束冲突重试、降级处理
- 编译 + 全量测试
- 启动 + 端到端验证

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| ETL 成功后清理旧版本失败（部分清理） | 每步独立 try-catch + error 日志，启动补偿 |
| 并发上传替换同一旧文档 | 唯一约束兜底 + 重试 3 次 + 降级 |
| 大文档替换时新旧版本 vectors 短暂共存 | vector 清理在新文档 ETL 完成后立即执行，窗口极短 |
| 应用重启丢失 pendingSupersede | `recoverPendingSupersede()` 启动补偿 |
| 迁移脚本对已有数据 | `version DEFAULT 1` + `superseded_by NULL` + 存量回填 |
| 事件异步处理失败 | 降级为新文档，不影响核心上传流程 |

## 9. 审查修订记录

| 修订 | 对应审查项 | 变更 |
|---|---|---|
| v2 §2.2 | P0-1 | 替换操作使用 TransactionTemplate 编程式事务 |
| v2 §2.2 | P0-2 | `document_group_id + version` 唯一约束 + 冲突重试 |
| v2 §2.3 | P0-3 | 改用事件驱动，上传策略只加 +1 行 publishEvent |
| v2 §3 | P0-4 | 新增异常处理策略表，明确降级原则 |
| v2 §4.3 | P1-1 | 分片上传在 complete 阶段发事件 |
| v2 §2.2 | P1-2 | V14 迁移脚本包含存量数据回填 SQL |
| v2 §1.1 | P1-3 | 明确 `deleteByDocumentId` 覆盖所有含该 documentId 的行 |
| v2 §4.4.1 | P1-4 | 新增 API 契约定义 |
| v2 §4.5 | P1-5 | 明确 vector 清理在 ETL 完成后同步执行 |
| v2 §5 | S-3 | 链式 SUPERSCEDE 详细时序 |
| **v3 §2.1** | **B1** | **同名判定改为 `replaceDocumentId` 参数，用户显式指定替换目标** |
| **v3 §2.2.1** | **H2** | **定义完整的 SUPERSEDED 状态机规则** |
| **v3 §4.5** | **H1** | **明确检索侧防线：vector 删除是唯一防线，无需额外过滤** |
| **v3 §2.3.3** | **M1** | **新增 `recoverPendingSupersede()` 启动补偿** |
| **v3 §2.6** | **M2** | **替换判定统一用 `replaceDocumentId`，团队场景校验 teamId 而非 userId** |
