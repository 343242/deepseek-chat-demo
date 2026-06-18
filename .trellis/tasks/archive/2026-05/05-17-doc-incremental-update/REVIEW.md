# 设计审查报告：文档增量更新方案

> 审查对象：`.trellis/tasks/05-17-doc-incremental-update/design.md`
> 审查依据：`.trellis/spec/backend/` + `.trellis/spec/guides/`
> 审查时间：2026-05-17

---

## 审查结论：需要修订后实施

发现 **4 个 P0**、**5 个 P1**、**4 个建议项**。

---

## P0 — 必须修复

### P0-1: 替换操作缺少事务控制

**spec 违规**：`database-guidelines.md` — "统一使用 TransactionTemplate 编程式事务"

**问题**：设计的替换流程涉及多步写操作：
1. `rag_document.status → SUPERSEDED`（UPDATE）
2. `rag_document.superseded_by → 新文档ID`（UPDATE）
3. `vector_store` 删除旧 chunk（DELETE）
4. `vector_store` 删除 BM25 fastTrack 行（DELETE）
5. MinIO 文件删除

步骤 1-2 是 DB 操作，3-4 也是 DB 操作，5 是外部调用。设计中只提到"每步独立 try-catch + 日志"，但没有定义事务边界。

**风险**：步骤 1 成功但步骤 2 失败 → 旧文档状态已变但 superseded_by 为空，无法追溯。

**修复建议**：
```java
// 步骤 1-2 必须在同一个 TransactionTemplate 中
transactionTemplate.executeWithoutResult(status -> {
    oldDoc.setStatus(SUPERSEDED);
    oldDoc.setSupersededBy(newDocId);
    ragDocumentMapper.updateById(oldDoc);
});
// 步骤 3-5 各自独立 try-catch（与现有 cascadeDelete 一致的容错模式）
```

---

### P0-2: 并发上传同名文件的版本号冲突

**spec 违规**：`quality-guidelines.md` — "check-then-act 要防并发：唯一约束兜底"

**问题**：设计中的同名文档查找是 check-then-act 模式：
```
T1: 请求A 查到旧文档 version=1
T2: 请求B 查到旧文档 version=1  （还没被A更新）
T3: 请求A 创建新文档 version=2
T4: 请求B 创建新文档 version=2  ← 版本号冲突
```

**修复建议**：
- 方案 A（推荐）：`document_group_id + version` 加唯一约束，`INSERT` 时冲突则重查最新 version 重试
- 方案 B：用 `SELECT ... FOR UPDATE` 锁住旧文档行再创建新版本（但会影响并发上传性能）

---

### P0-3: OCP 违规 — 修改了 4 个现有类

**spec 违规**：`quality-guidelines.md` — "开闭原则 (OCP) 必须遵守：加功能 = 加新类，不是改旧类"

**问题**：设计要求修改：
1. `PersonalUploadStrategy.java` — 注入同名文档查找逻辑
2. `TeamUploadStrategy.java` — 同上
3. `ChunkUploadServiceImpl.java` — 分片上传完成时的同名文档查找
4. `EtlDispatchServiceImpl.java` — ETL 完成回调中调用替换服务

这 4 处修改的本质都是"在上传完成后/ETL 完成后插入一段增量更新逻辑"。

**修复建议**：使用**事件驱动**模式解耦：
```java
// 新增事件
public class DocumentCreatedEvent extends ApplicationEvent {
    private final Long documentId;
    private final String fileName;
    private final Long userId;
    private final Long teamId;
}

// DocumentSupersedeService 监听事件
@EventListener
public void onDocumentCreated(DocumentCreatedEvent event) {
    // 查找同名旧文档 → 设置版本 → 注册替换回调
}

// ETL 完成也发事件
@EventListener
public void onEtlCompleted(EtlCompletedEvent event) {
    // 执行旧版本清理
}
```

这样 `PersonalUploadStrategy`、`TeamUploadStrategy`、`ChunkUploadServiceImpl` 只需在文档创建后发一个事件，不需要知道增量更新的存在。`EtlDispatchServiceImpl` 在 ETL 完成后也发事件。**零修改现有类的核心逻辑**。

---

### P0-4: 未定义失败场景的异常处理

**spec 违规**：`error-handling.md` — "业务异常统一抛 BusinessException"

**问题**：设计中缺少以下场景的异常定义：
1. 同名文档查找失败 → 怎么处理？静默降级为新文档？还是抛异常？
2. 版本号冲突重试次数用尽 → 抛什么异常？
3. 旧版本清理全部失败（vectors + BM25 + MinIO 都失败）→ 是回滚还是接受？

**修复建议**：在设计中明确：
```
- 同名文档查找失败 → 降级为新文档上传（不影响核心功能）
- 版本号冲突 → 最多重试 3 次，超出后降级为新文档（不阻塞上传）
- 清理失败 → 与 cascadeDelete 一致：记录日志 + 告警，不阻塞新文档
```

---

## P1 — 建议修复

### P1-1: 分片上传场景的增量更新流程不完整

设计提到"ChunkUploadServiceImpl 分片上传完成时同样查找同名文档"，但分片上传有独特的时序：
- `init` 阶段：前端传 `fileMd5` + `fileName`
- `upload` 阶段：逐片上传
- `complete` 阶段：合并 → 计算 MD5 → 触发 ETL

同名文档查找应该在 `init` 还是 `complete` 阶段？`init` 阶段时文件还没有完全上传，此时就锁定版本号可能导致长时间占用。建议在 `complete` 阶段（合并成功后、ETL 前）处理。

### P1-2: `document_group_id` 为 NULL 的存量数据迁移

V14 迁移脚本给已有数据加字段，`document_group_id` 默认 NULL。设计说"向后兼容"但没说明：
- 存量文档的 `document_group_id` 何时回填？
- 如果不回填，存量文档上传新版本时无法关联到旧版本，失去了增量更新能力

建议：在 V14 中加一步回填（按 `file_name + user_id` 或 `file_name + team_id` 分组，每组分配一个 `document_group_id`）。

### P1-3: 清理旧 BM25 数据不完整

`VectorStoreLoader.deleteByDocumentId` 通过 `metadata->>'documentId'` 删除 vectors，但 BM25 的 content_tsv chunks 也是按 documentId 关联的。设计中提到 `deleteFastTrackRows` 但没有提到清理 BM25 chunk 级的 tsvector 数据。

需确认：`VectorStoreLoader.deleteByDocumentId` 的 filter 删除是否覆盖了所有含该 documentId 的行（包括 embedding=NULL 的 BM25-only 行）？

### P1-4: API 契约未定义

设计提到"可选新增 `GET /api/documents/{id}/history`"但没有定义：
- 响应格式（返回 DTO 列表？包含哪些字段？）
- 权限要求（同文档查看权限？）
- 是否需要分页

### P1-5: 检索侧未适配 SUPERSEDED 过滤

设计提到"查询 SUPERSEDED 状态的文档被正确过滤"，但检索侧（`HybridDocumentRetriever`）查的是 `vector_store` 表，不是 `rag_document` 表。被 SUPERSEDE 的文档的 vectors 在清理前仍然存在于 vector_store 中，检索仍会命中。

需要在设计中明确：SUPERSEDE 后的 vector 清理是同步还是异步？如果是异步，检索侧是否需要额外的过滤机制？

---

## 建议项

### S-1: 事件驱动替代直接修改

（见 P0-3 的修复建议）使用 Spring `ApplicationEvent` 解耦，符合项目已有的 DIP 实践。

### S-2: DocumentSupersedeService 包路径

建议放在 `com.demo.chat.rag.service.impl`，与 `DocumentLifecycleService` 同级，保持一致性。

### S-3: 链式 SUPERSCEDE 的处理

设计中边界场景提到"v1→v2→v3，中间版本可能未完成就被替代"，但没有详细说明处理逻辑。建议明确：
- v1 已被 superseded_by=v2，v2 还在 PROCESSING 时又来了 v3
- v3 应该 supersede v2（不是 v1）
- 查找逻辑应查 `superseded_by IS NULL` 的最新版本

### S-4: 回滚能力

设计中旧版本标记为 SUPERSEDED 但不删除。建议补充：
- 是否提供 API 让用户手动回滚到旧版本？（将 SUPERSEDED 改回 COMPLETED + 清理新版本）
- 还是只作为审计记录？
