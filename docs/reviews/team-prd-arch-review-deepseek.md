# 团队功能 PRD v1.1 — 第二轮深度架构审查报告

> 审查日期：2026-05-13 | 审查重点：架构嵌入 / 数据一致性 / 性能 / 策略落地 / 缓存冲突 / 并发 / 遗漏

---

## 一、架构风险

### AR-01 🔴 阻塞：ChatRequest 无法直接扩展 teamId — 破坏现有 record 序列化契约

**问题描述：**
`ChatRequest` 是一个 Java `record`，当前包含 `model, message, conversationId, ragEnabled, mode, enableThinking` 六个组件。PRD §6.5 计划新增 `teamId` 字段。但新增 record 组件会改变规范构造器和 accessor 签名，导致：
- **Jackson 反序列化失败**：现有前端传入的 JSON 不含 `teamId` 字段，Java record 的严格构造器会拒绝匹配（除非使用 `@JsonCreator` + `@JsonProperty` 显式绑定，但现有代码未配置）
- **`withModel()` 方法重复膨胀**：该方法需要显式传递所有组件，每次加字段都要更新

**影响代码：**
- `ChatRequest.java` — record 定义
- `ChatController.chat()` / `chatStreamGet()` / `chatStreamPost()` — 所有调用点
- 所有调用 `request.withModel()` 的位置（`ChatServiceImpl` 内部兜底链路）

**严重度：🔴 阻塞**

**建议方案：**
1. **方案 A（推荐）：** 在 `ChatRequest` 上添加 `@JsonCreator` 构造器 + `@JsonProperty` 声明每个组件，并为 `teamId` 设置 `@JsonProperty(defaultValue = "null")`（Jackson 2.16+ 支持 record 的 default value）
2. **方案 B：** 不使用 record 改回普通 POJO，用 `@NoArgsConstructor` + `@AllArgsConstructor`
3. **方案 C：** 将 `teamId` 放到 HTTP Header（`X-Team-Id`）而非 request body，避免改变 record 契约。但这样无法利用 `@Valid` 校验

### AR-02 🔴 阻塞：EtlCandidate 新增 teamId 字段导致编译级破坏

**问题描述：**
`EtlCandidate` 是 `record(Long documentId, String bucket, String objectKey, String fileName, String mimeType, long fileSize, Long userId)`。PRD §7.2 计划新增 `teamId` 字段。Java record 的构造器是强类型匹配的——新增组件后，**所有现有 `new EtlCandidate(...)` 调用点都需要修改**，否则编译失败。

**现有调用点（全部需要修改）：**

| 调用位置 | 行文 |
|----------|------|
| `DocumentApplicationServiceImpl.upload()` | `new EtlCandidate(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), currentUserId)` |
| `DocumentApplicationServiceImpl.uploadBatch()` | 同上 × N |
| `DocumentApplicationServiceImpl.retry()` | `etlDispatchService.dispatchAsync(id, doc.getBucket(), doc.getStorageKey(), doc.getFileName(), doc.getMimeType(), doc.getFileSize(), doc.getUserId())` — 间接经过 `EtlDispatchServiceImpl` 构造 |
| `EtlDispatchServiceImpl.executeSingle()` | `new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId)` |
| `EtlDispatchServiceImpl.dispatchAsync()` | `new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId)` |
| `ChunkUploadServiceImpl.performMerge()` | `etlDispatchService.dispatchAsync(docId, bucket, targetObjectKey, session.get("fileName"), session.get("mimeType"), Long.parseLong(session.get("fileSize")), userId)` — 间接构造 |

**影响代码：** 至少 7 处 `new EtlCandidate(...)` 调用 + 2 处 `dispatchAsync()` 间接调用。同时 `EtlPipelineServiceImpl.executeWithUserId()` 的 metadata 注入逻辑也需要增加 `teamId` 写入。

**严重度：🔴 阻塞**

**建议方案：**
1. **推荐：** 给 `teamId` 使用 `@Nullable` 默认值。Java record 不支持默认值，但可以使用紧凑构造器（compact constructor）配合 `Optional` 或使用 Builder 模式重写 `EtlCandidate`
2. 最简单：将 `teamId` 改为 `Long teamId` 并 **不修改** 个人上传的调用方，传入 `null`。每处改动为：`..., userId, null)`（末尾加一个参数）。需要统一在本次改动中完成，不能遗漏

### AR-03 🟡 高风险：策略模式拆解时，PersonalUploadStrategy 与 DocumentApplicationServiceImpl 的职责重叠

**问题描述：**
PRD §4.3.1 设计 `UploadStrategy` 接口 → `PersonalUploadStrategy` 封装现有逻辑。但现有 `DocumentApplicationServiceImpl` 不仅负责上传，还负责 `listAll()`、`getById()`、`delete()`、`retry()` 等操作。PRD 的设计是：

```
DocumentApplicationServiceImpl
  └── upload() → UploadStrategyFactory → PersonalUploadStrategy.upload()
  └── listAll() → 保持不变？
  └── delete() → 保持不变 + DocumentDeletePermissionChecker？
  └── retry() → 保持不变？
```

**核心问题：**
1. `PersonalUploadStrategy` 需要注入 `FileStorageService, EtlDispatchService, RagDocumentMapper, MinioProperties, DocumentValidator` — 与 `DocumentApplicationServiceImpl` 有大量依赖重叠
2. 如果 `PersonalUploadStrategy.upload()` 直接复制 `DocumentApplicationServiceImpl.upload()` 的逻辑，会形成事实上的**代码克隆**。一旦上游逻辑变更（如新增安全检查），两处都要改
3. 上传后的 `persistDocument()` 私有方法在 `DocumentApplicationServiceImpl` 中，`PersonalUploadStrategy` 需要自己的副本，或者提取为共享组件

**影响代码：**
- `DocumentApplicationServiceImpl.java` — 需要移除 `upload()` / `uploadBatch()` 的现有实现，改为委托
- `PersonalUploadStrategy.java` — 全新文件，需要复制现有逻辑

**严重度：🟡 高风险**

**建议方案：**
1. 将 `persistDocument()` 提取为独立组件（如 `DocumentPersistenceService`），供两处共享
2. `PersonalUploadStrategy` 实现后，**从 `DocumentApplicationServiceImpl` 完全删除** 旧的上传代码（不是注释，是删除），避免遗留死代码被后续改动绕过
3. `DocumentApplicationServiceImpl.upload()` 改为纯粹的委托点：
   ```java
   public DocumentUploadResponse upload(MultipartFile file) {
       return uploadStrategyFactory.route(null).upload(file, null, SecurityUtils.getCurrentUserId());
   }
   ```
4. 回归测试必须覆盖：单文件上传、批量上传、秒传、续传、所有 MIME 类型的文件

---

## 二、集成难点

### IN-01 🔴 阻塞：RagAdvisorFactory advisorCache 无法直接扩展 teamId 维度

**问题描述：**
现有缓存设计：
```java
private final ConcurrentHashMap<Long, RetrievalAugmentationAdvisor> advisorCache = new ConcurrentHashMap<>();
public RetrievalAugmentationAdvisor create(Long userId) {
    return advisorCache.computeIfAbsent(userId, this::buildAdvisor);
}
```

PRD §6.5 计划改为 `create(userId, teamId)`。关键问题：

**1. 缓存 Key 设计歧义：**
- 个人检索（teamId=null）：`filterBuilder.eq("userId", userId)` → Advisor 实例 A
- 团队检索（teamId=123）：`filterBuilder.eq("teamId", teamId)` → Advisor 实例 B
- 用户在不同团队中：需要每个团队一个独立的 Advisor 实例

**2. 缓存失效问题：**
- Advisor 内部封装了 `VectorStoreDocumentRetriever` 或 `HybridDocumentRetriever`，这些检索器的构造函数接收了静态的 filter/filter expression
- 如果缓存 key 是 `"userId:teamId"`，那么用户从团队退出后，缓存的 Advisor 仍然存在，其内部的 filter 仍然有效（因为是向量存储层面的过滤）。但外层 `TeamMembershipVerifier` 会拦截非成员访问——所以安全上没问题，但缓存永远不会被清理，造成内存泄漏
- 如果缓存 key 是 `teamId` 独立于 userId，则不同用户共用同一个 Advisor 实例（共享向量检索器），但 `HybridDocumentRetriever` 的构造函数接收 `userId` 参数——**不匹配**

**3. HybridDocumentRetriever 的 userId 参数冲突：**
```java
public HybridDocumentRetriever(..., Long userId, ...) {
    this.userId = userId;
}
```
该检索器的 `userId` 在构造函数中固定，用于 BM25 SQL 中的 `WHERE metadata->>'userId' = ?`。当 `teamId` 不为 null 时，这个过滤条件需要改成 `teamId`。但 `HybridDocumentRetriever` 是个一次性的构造对象，不是线程安全的缓存对象。

**影响代码：**
- `RagAdvisorFactory.java` — `advisorCache` 结构 + `create()` 签名 + `buildAdvisor()` 逻辑
- `HybridDocumentRetriever.java` — `userId` 字段需重命名为通用隔离字段
- `ChatAdvisorChainFactory.java` — `buildChain()` 需要传递 `teamId`

**严重度：🔴 阻塞**

**建议方案：**
1. **缓存 Key 改为 `String` 复合键：**
   ```java
   private final ConcurrentHashMap<String, RetrievalAugmentationAdvisor> advisorCache = new ConcurrentHashMap<>();
   
   private static String advisorKey(Long userId, Long teamId) {
       return teamId != null ? "T:" + teamId : "U:" + userId;
   }
   ```
   
2. **HybridDocumentRetriever 重构为隔离维度参数化：**
   ```java
   // 用通用的 isolationField/isolationValue 替代 userId 参数
   public HybridDocumentRetriever(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
                                   RagRetrievalProperties properties, QueryNormalizer queryNormalizer,
                                   String isolationField, String isolationValue, ObjectMapper objectMapper) {
       this.isolationField = isolationField; // "userId" 或 "teamId"
       this.isolationValue = isolationValue;
   }
   ```
   
3. **缓存失效策略：** 
   - 用户退出团队时，无需主动清除缓存（`TeamMembershipVerifier` 已在校验层拦截）
   - 可设置 `advisorCache` 的最大容量（`new ConcurrentHashMap<>(128)` 或使用 Guava Cache/Caffeine 设置 TTL），防止内存泄漏
   - 不建议每次请求重建 Advisor（数据库连接/向量存储客户端连接开销不可忽略）

### IN-02 🔴 阻塞：ChunkUploadServiceImpl 的 ETL 触发路径未考虑 teamId

**问题描述：**
`ChunkUploadServiceImpl.performMerge()` 方法在线 277-283 直接：
```java
etlDispatchService.dispatchAsync(
    docId, bucket, targetObjectKey, session.get("fileName"),
    session.get("mimeType"), Long.parseLong(session.get("fileSize")), userId
);
```

PRD §4.3.4 说分片上传要"通过 `UploadStrategyFactory` 路由到对应策略"，但现有分片上传流程：
1. `init` 阶段创建 Redis session
2. `uploadChunk` 逐步上传分片
3. `performMerge()` 直接持久化 `rag_document`（status=PROCESSING）并触发 ETL

**问题：**
- 如果 `teamId` 在 `init` 阶段传入，需要存入 Redis session
- `persistDocument()` 在 `ChunkUploadServiceImpl` 中私有实现（行 355-369），直接设置 `status = EtlStatus.PROCESSING`
- 对于普通成员团队上传，分片合并后应设置 `PENDING_APPROVAL` 而非 `PROCESSING`，且**不应触发 ETL**
- 审批通过后触发 ETL，但 ETL 需要的 `bucket/objectKey` 在 Redis session 清除后已无法获取

**影响代码：**
- `ChunkUploadServiceImpl.java` — `persistDocument()` 方法、`performMerge()` 方法
- `ChunkUploadInitRequest.java` — 需要加 `teamId` 字段
- Redis session 结构需要增加 `teamId` 和 `status` 字段

**严重度：🔴 阻塞**

**建议方案：**
1. 在 `ChunkUploadInitRequest` 中新增 `teamId`（可选，null=个人）
2. Redis session 中存储 `teamId` 和 `status`（PENDING_APPROVAL 或 PROCESSING）
3. `persistDocument()` 改为根据 session 中的 status 设置 `rag_document.status`：
   ```java
   String statusCode = session.get("status");
   EtlStatus status = statusCode != null ? EtlStatus.valueOf(statusCode) : EtlStatus.PROCESSING;
   doc.setStatus(status);
   ```
4. `performMerge()` 末尾的 `etlDispatchService.dispatchAsync()` 调用增加条件：
   ```java
   if (status != EtlStatus.PENDING_APPROVAL) {
       etlDispatchService.dispatchAsync(...);
   }
   ```
5. 将 MinIO 合并后的 objectKey 持久化到 `rag_document` 记录中（已通过 `persistDocument()` 完成），确保审批通过时可以直接从 `rag_document` 读取 bucket/key 重新发起 ETL

### IN-03 🟡 高风险：`listAll()` 的查询语义变更 — 现有查询只过滤 userId

**问题描述：**
`DocumentApplicationServiceImpl.listAll()` 当前查询逻辑：
```java
List<RagDocument> docs = ragDocumentMapper.selectList(
    new LambdaQueryWrapper<RagDocument>()
        .eq(RagDocument::getUserId, currentUserId)
        .orderByDesc(RagDocument::getCreateTime));
```

PRD §6.4 说 `GET /api/documents` 返回"个人文档 + 团队文档（标注 teamId）"。这意味着查询条件需要变成：
```sql
WHERE (user_id = ? AND team_id IS NULL)  -- 个人文档
   OR (team_id IN (SELECT team_id FROM team_member WHERE user_id = ? AND status = 1))  -- 团队文档
   AND status NOT IN ('PENDING_APPROVAL', 'REJECTED')  -- 排除审批中/已拒绝
```

**问题：**
- 现有 `LambdaQueryWrapper` 无法表达 `OR` + 子查询的复合条件，需要在 MyBatis XML 中实现
- 用户加入团队越多，查询越慢（子查询 + IN clause）
- `DocumentDTO` record 不含 `teamId` 字段，前端无法区分个人/团队文档

**影响代码：**
- `DocumentApplicationServiceImpl.listAll()`
- `RagDocumentMapper.java` / 对应 XML
- `DocumentDTO.java` — 需新增 `teamId` 字段

**严重度：🟡 高风险**

**建议方案：**
1. 在 `RagDocumentMapper` 中新增自定义 SQL：`selectUserAccessibleDocuments(@Param("userId") Long userId)`
2. 在 `DocumentDTO` 中增加 `Long teamId` 字段（`null` = 个人文档）
3. 如果用户加入的团队数量很多（>20），考虑分两次查询：
   - 查询1：个人文档（LIMIT + OFFSET），条件简单
   - 查询2：团队文档，通过 `WHERE team_id IN (...)` + LIMIT
   - 合并结果后排序分页（应用层分页）

### IN-04 🟡 高风险：VectorStoreLoader 写入时缺少 teamId metadata

**问题描述：**
`EtlPipelineServiceImpl.executeWithUserId()` 在线 92-95：
```java
String docIdStr = String.valueOf(documentId);
String userIdStr = String.valueOf(userId);
for (Document chunk : chunks) {
    chunk.getMetadata().put("documentId", docIdStr);
    chunk.getMetadata().put("userId", userIdStr);
}
```

PRD §4.5.3 要求 RAG 检索时通过 `FilterExpressionBuilder.eq("teamId", teamId)` 过滤。但这要求向量数据在写入时就携带 `teamId` metadata。

**问题：**
- 当前写入 metadata 包含 `documentId` 和 `userId`，不包含 `teamId`
- 团队文档的 ETL 触发时需要额外传入 `teamId`，但目前 `EtlCandidate` 没有这个字段
- `VectorStoreLoader.load()` 只负责写入，不参与 metadata 构建

**影响代码：**
- `EtlPipelineServiceImpl.executeWithUserId()` — metadata 注入点
- `EtlCandidate` — record 定义
- 所有 ETL 触发调用点 — `DocumentApplicationServiceImpl`、`ChunkUploadServiceImpl`、`TeamUploadStrategy`（新增）

**严重度：🟡 高风险**

**建议方案：**
1. `EtlCandidate` 新增 `Long teamId` 字段（也可用 `String teamId`）
2. `executeWithUserId()` 方法签名增加 `teamId` 参数，在 metadata 注入阶段写入：
   ```java
   if (teamId != null) {
       chunk.getMetadata().put("teamId", String.valueOf(teamId));
   }
   ```
3. `EtlDispatchServiceImpl.dispatchAsync()` 签名同样需要扩展
4. **兼容性注意：** 现有向量数据没有 `teamId` metadata，检索时需要额外判断。建议为存量数据补充 `teamId` metadata（通过 documentId 反查 rag_document.team_id）

### IN-05 🟡 高风险：HybridDocumentRetriever 的 BM25 和向量检索隔离逻辑需要重构

**问题描述：**
`HybridDocumentRetriever` 硬编码了 `userId` 隔离：
- 向量检索：`filterBuilder.eq("userId", String.valueOf(userId))`（行 105）
- BM25 检索：`WHERE metadata->>'userId' = ?`（行 153）

PRD 要求支持 `teamId` 过滤。有两种修改方式：

**方式 A（切换）：** 根据 teamId 是否为 null 选择过滤字段
- 问题：`HybridDocumentRetriever` 在构造时确定参数，不能运行时切换

**方式 B（叠加）：** 同时过滤 userId 和 teamId
- 问题：个人文档没有 teamId metadata，`eq("teamId", null)` 不可行

**影响代码：**
- `HybridDocumentRetriever.java` — `userId` 字段 + 所有过滤逻辑
- `RagAdvisorFactory.createUserIsolatedRetriever()` — 构造参数传递

**严重度：🟡 高风险**

**建议方案：**
将 `HybridDocumentRetriever` 的隔离逻辑参数化：

```java
public class HybridDocumentRetriever implements DocumentRetriever {
    private final String isolationField;  // "userId" 或 "teamId"
    private final String isolationValue;  // String.valueOf(userId) 或 String.valueOf(teamId)
    
    // 向量检索
    var filter = filterBuilder.eq(isolationField, isolationValue).build();
    
    // BM25 检索
    // SQL: WHERE metadata->>? = ?  (参数：isolationField, isolationValue)
}
```

同时修改 `RagAdvisorFactory.createUserIsolatedRetriever()`：
```java
private DocumentRetriever createUserIsolatedRetriever(Long userId, Long teamId) {
    String isolationField = teamId != null ? "teamId" : "userId";
    String isolationValue = String.valueOf(teamId != null ? teamId : userId);
    // ...
}
```

### IN-06 🟡 高风险：DocumentController.delete() 与 DocumentDeletePermissionChecker 的集成点未定义

**问题描述：**
PRD §4.5.2 设计了 `DocumentDeletePermissionChecker` 统一处理个人/团队文档的删除权限。但当前 `DocumentController.delete()` 直接调用：
```java
@DeleteMapping("/{id}")
public GlobalResponse<Void> delete(@PathVariable Long id) {
    documentService.delete(id);
    return GlobalResponse.ok("文档已删除");
}
```

底层 `DocumentApplicationServiceImpl.delete()` 调用 `findAndVerifyOwner(id)`，只检查 `userId == doc.userId`。对于团队文档，需要新增权限判断：文档上传者本人 或 CREATOR/ADMIN。

**问题：**
- `DocumentDeletePermissionChecker` 的调用点放在哪里？如果在 `DocumentApplicationServiceImpl.delete()` 中替换 `findAndVerifyOwner()`，需要注入 `TeamMembershipVerifier`
- 如果在 Controller 层通过 AOP 或 `@PreAuthorize` 处理，无法获取 `teamId`（需要先查数据库得到 `rag_document.team_id`）
- 权限校验失败返回什么？现有 `findAndVerifyOwner()` 返回 `null` → `delete()` 返回 `false`。新逻辑应该抛 `NO_PERMISSION_DELETE_TEAM_DOC` 异常

**影响代码：**
- `DocumentApplicationServiceImpl.delete()` + `findAndVerifyOwner()`
- `DocumentController.delete()`
- `DocumentDeletePermissionChecker`（新增）

**严重度：🟡 高风险**

**建议方案：**
1. 在 `DocumentApplicationServiceImpl.delete()` 中，将 `findAndVerifyOwner()` 替换为调用 `DocumentDeletePermissionChecker.check(documentId)`
2. `DocumentDeletePermissionChecker` 内部逻辑：
   ```
   if (teamId == null) → 现有逻辑（userId 匹配）
   if (teamId != null) → TeamMembershipVerifier.verifyMember(teamId, userId) + 如果 role == MEMBER 还需要 userId == doc.userId
   ```
3. 失败时抛 `BusinessException(NO_PERMISSION_DELETE_TEAM_DOC)`，Controller 层通过 GlobalExceptionHandler 统一处理

### IN-07 🟡 高风险：ChunkUploadServiceImpl.validateFileSize() 硬编码 50MB，与团队额度冲突

**问题描述：**
```java
private void validateFileSize(Long fileSize) {
    long maxBytes = DataSize.parse("50MB").toBytes();
    if (fileSize > maxBytes) {
        throw new BusinessException(ErrorCode.UPLOAD_FILE_TOO_LARGE);
    }
}
```

PRD §4.3.2 要求成员上传时校验 `file.size ≤ member.upload_limit_mb`，创建者校验 `file.size ≤ team.creator_upload_limit_mb`。但 `validateFileSize()` 硬编码 50MB，会**在额度校验之前**就拦截超大文件。

**影响代码：**
- `ChunkUploadServiceImpl.validateFileSize()` — 硬编码上限
- `ChunkUploadServiceImpl.init()` — validateFileSize 在额度校验之前调用

**严重度：🟡 高风险**

**建议方案：**
1. 将 `validateFileSize()` 改为接收参数：
   ```java
   private void validateFileSize(Long fileSize, long maxBytes) {
       if (fileSize > maxBytes) {
           throw new BusinessException(ErrorCode.UPLOAD_FILE_TOO_LARGE, 
               "文件大小超出限制: " + fileSize + " > " + maxBytes);
       }
   }
   ```
2. 在 `init()` 方法中，根据是否有 `teamId` 动态获取限额：个人上传用 `documentProperties.maxFileSize`；团队上传查 `team_member.upload_limit_mb`

---

## 三、遗漏改动

### OM-01 🔴 阻塞：DocumentApplicationServiceImpl.retry() 未考虑团队文档重试

**问题描述：**
```java
public DocumentUploadResponse retry(Long id) {
    RagDocument doc = findAndVerifyOwner(id);
    if (doc == null) {
        throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
    }
    if (doc.getStatus() != EtlStatus.FAILED && doc.getStatus() != EtlStatus.VECTOR_FAILED) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "...");
    }
    // ... 清理旧向量 + 重新 dispatchAsync
}
```

PRD 未提及团队文档的 retry 场景：
- 团队成员上传的文档审批通过后 ETL 失败（FAILED / VECTOR_FAILED）→ 谁可以重试？上传者？管理员？
- `findAndVerifyOwner()` 只检查 `userId == doc.userId`，管理员无法重试他人文档
- 重试时 `dispatchAsync()` 需要携带 `teamId` 以正确注入向量 metadata

**影响代码：**
- `DocumentApplicationServiceImpl.retry()`
- `EtlDispatchServiceImpl.dispatchAsync()` — 签名需要 `teamId` 参数

**严重度：🔴 阻塞**

**建议方案：**
1. `retry()` 方法增加权限判断：个人文档 → 验证所有权；团队文档 → 验证团队成员身份
2. 重试时从 `RagDocument.teamId` 读取 teamId，传递给 `dispatchAsync()`
3. `dispatchAsync()` 签名扩展：
   ```java
   public void dispatchAsync(Long documentId, String bucket, String objectKey, 
                             String fileName, String mimeType, long fileSize, 
                             Long userId, Long teamId)
   ```

### OM-02 🟡 高风险：EtlStatusManager 缺少状态转换校验 — PENDING_APPROVAL 可能被误写

**问题描述：**
`EtlStatusManager.updateStatus()` 方法：
```java
public void updateStatus(Long documentId, EtlStatus status) {
    transactionTemplate.executeWithoutResult(ts -> {
        RagDocument update = new RagDocument();
        update.setId(documentId);
        update.setStatus(status);
        update.setUpdateTime(LocalDateTime.now());
        ragDocumentMapper.updateById(update);
    });
}
```

**没有任何状态转换校验**。这意味着：
- 异步任务可以将 `PENDING_APPROVAL` 状态的文档直接设为 `PARSING`（绕过审批）
- 如果 ETL 调度逻辑错误地将 PENDING_APPROVAL 文档加入了 ETL 队列，会在未审批的情况下构建向量
- REJECTED 状态的文档理论上也能被设为 PROCESSING

**影响代码：**
- `EtlStatusManager.java` — `updateStatus()` 方法

**严重度：🟡 高风险**

**建议方案：**
1. 在 `updateStatus()` 或 `EtlPipelineServiceImpl.executeWithUserId()` 入口增加**前置状态校验**：
   ```java
   RagDocument doc = ragDocumentMapper.selectById(documentId);
   if (doc == null || doc.getStatus() == EtlStatus.PENDING_APPROVAL 
                    || doc.getStatus() == EtlStatus.REJECTED) {
       log.error("Cannot start ETL for document {} in status {}", documentId, doc.getStatus());
       return; // 或抛异常
   }
   ```
2. 推荐定义合法的状态机转换映射：
   ```
   PENDING_APPROVAL → UPLOADED (审批通过)
   PENDING_APPROVAL → REJECTED (审批拒绝)
   UPLOADED → PARSING → CHUNKING → VECTORIZING → COMPLETED
   UPLOADED → FAILED
   PROCESSING → PARSING → CHUNKING → VECTORIZING → COMPLETED
   PROCESSING → FAILED / VECTOR_FAILED
   REJECTED → (终结状态，不可转换)
   ```

### OM-03 🟡 高风险：审批通过后触发 ETL 时，EtlCandidate 缺少 teamId

**问题描述：**
PRD §4.4.2 审批通过后调用：`@Transactional` 更新 `rag_document.status → UPLOADED` + 触发 ETL（异步）。但审批触发的 ETL 需要将 `teamId` 写入向量 metadata。当前：
- 审批 Service（`TeamApprovalServiceImpl`，新增代码）需要调用 `etlDispatchService.dispatchAsync()`
- 但 `dispatchAsync()` 当前签名没有 `teamId` 参数
- 需要从 `RagDocument.teamId` 获取并传递

**影响代码：**
- `EtlDispatchServiceImpl.dispatchAsync()` / `executeSingle()` — 签名
- `EtlCandidate` — record 定义
- `EtlPipelineServiceImpl.executeWithUserId()` — metadata 注入
- `TeamApprovalServiceImpl`（新增）— 审批通过后的 ETL 触发

**严重度：🟡 高风险**

**建议方案：**
1. 扩展 `dispatchAsync()` 签名为包含 `teamId` 参数
2. 或在 `EtlCandidate` 中增加 `teamId` 字段，`EtlPipelineServiceImpl` 从 `candidate.teamId()` 读取并写入 metadata
3. 审批通过后的 ETL 触发建议使用 `@TransactionalEventListener` 解耦，不要在审批事务中直接调用异步 ETL

### OM-04 🟡 高风险：DocumentDTO 和 DocumentUploadResponse 缺少 teamId 字段

**问题描述：**
- `DocumentDTO` record 不含 `teamId` — 前端无法区分文档属于个人还是团队
- `DocumentUploadResponse` record 不含 `teamId` — 上传响应无法告知客户端文档关联的团队

**影响代码：**
- `DocumentDTO.java`
- `DocumentUploadResponse.java`
- `DocumentApplicationServiceImpl.toDTO()` — 需要设置 teamId
- 前端对接 — 所有展示文档列表的地方

**严重度：🟡 高风险**

**建议方案：**
1. `DocumentDTO` 新增 `Long teamId` 组件（`null` = 个人文档）
2. `DocumentUploadResponse` 新增 `Long teamId` 组件
3. 所有构造这两类 DTO 的地方都需要更新（`upload()`, `uploadBatch()`, `upload()`, `retry()`, `toDTO()`）

### OM-05 🟡 高风险：RagDocument 的 findAndVerifyOwner() 语义不适用于团队文档

**问题描述：**
`DocumentApplicationServiceImpl.findAndVerifyOwner()` 的逻辑：
```java
if (!currentUserId.equals(doc.getUserId())) {
    return null;  // 静默拒绝
}
```
这个方法被 `getById()`, `delete()`, `retry()` 三个方法调用。对于团队文档：
- **getById()** — 团队成员应该可以查看团队内的任何文档，不需要 ownership 检查
- **delete()** — 需要 `DocumentDeletePermissionChecker`，不是 ownership 检查
- **retry()** — 需要团队成员身份，不一定是 owner

**影响代码：**
- `DocumentApplicationServiceImpl.findAndVerifyOwner()` — 核心权限方法
- `getById()`, `delete()`, `retry()` — 所有调用点

**严重度：🟡 高风险**

**建议方案：**
1. 将 `findAndVerifyOwner()` **拆分为两个方法**：
   ```java
   // 用于 getById：团队文档允许成员查看
   private RagDocument findAndVerifyAccess(Long id) { ... }
   // 用于 delete：委托给 DocumentDeletePermissionChecker
   private RagDocument findAndVerifyDelete(Long id) { ... }
   // 用于 retry：检查是否可操作
   private RagDocument findAndVerifyOperable(Long id) { ... }
   ```
2. 或引入策略模式，通过 `teamId` 是否为 null 判断路径

### OM-06 🟡 高风险：ChatController 的 GET 流式接口中 ChatRequest 构造缺少 teamId

**问题描述：**
`ChatController.chatStreamGet()` 当前手动构造 `ChatRequest`：
```java
ChatRequest request = new ChatRequest(model, message, conversationId, ragEnabled, mode, enableThinking);
```

PRD §6.5 说 GET 流式接口需增加 `teamId` 参数，但这里没体现。需要在构造 `ChatRequest` 时增加 `teamId` 参数。

**影响代码：**
- `ChatController.chatStreamGet()` — 请求参数声明 + ChatRequest 构造
- `ChatRequest` record — 新增组件

**严重度：🟡 高风险**（如果遗漏，GET 流式接口无法团队检索）

**建议方案：**
```java
@RequestParam(required = false) Long teamId
// ...
ChatRequest request = new ChatRequest(model, message, conversationId, ragEnabled, mode, enableThinking, teamId);
```
需要处理 `ChatRequest` 中 teamId 的 `withModel()` 拷贝方法。

### OM-07 🟡 高风险：ChunkUploadServiceImpl 的秒传逻辑未区分团队文档

**问题描述：**
`ChunkUploadServiceImpl.findExistingForQuickUpload()` 按 `(fileMd5, userId)` 查询：
```java
return ragDocumentMapper.selectOne(
    new LambdaQueryWrapper<RagDocument>()
        .eq(RagDocument::getFileMd5, fileMd5)
        .eq(RagDocument::getUserId, userId)
        .in(RagDocument::getStatus, EtlStatus.COMPLETED, EtlStatus.PROCESSING)
        .eq(RagDocument::getDeleted, 0)
        .last("LIMIT 1")
);
```

**问题：**
- 个人文档和团队文档使用相同的 MD5 空间——同一个文件被同一个用户上传到不同团队时，秒传会错误地复用另一个团队的文档
- 应该在查询条件中加入 `team_id` 限定

**影响代码：**
- `ChunkUploadServiceImpl.findExistingForQuickUpload()`

**严重度：🟡 高风险**（可能导致跨团队文档复用错误）

**建议方案：**
```java
query.eq(RagDocument::getTeamId, teamId)  // null 表示个人空间
```
若 teamId 为 null，需要显式 `IS NULL` 条件。

### OM-08 🟠 中风险：审批超时任务删除 MinIO 文件失败时的补偿未定义

**问题描述：**
PRD §4.4.3 设计"审批超时自动拒绝 + 删除 MinIO 文件"。但未定义文件删除失败时的处理：
- 失败后文件是孤立在 MinIO 中的吗？
- 是否需要重试机制？重试几次？
- 如果 MinIO 不可用（网络分区），定时任务是否应该继续执行下一个审批？

**影响代码：**
- `ApprovalTimeoutJob.java`（新增）
- `FileStorageService` — delete 操作

**严重度：🟠 中风险**

**建议方案：**
1. 删除文件失败时标记 `rag_document.status = PENDING_CLEANUP`（与团队解散的清理逻辑一致）
2. 定时任务单独处理 PENDING_CLEANUP 状态的文档
3. 审批超时任务加 try-catch 包裹单条处理，避免一条失败阻塞整个批次

### OM-09 🟠 中风险：团队检索时，用户可能已退出团队但缓存未清理

**问题描述：**
PRD §4.5.3 说"检索前通过 `TeamMembershipVerifier` 验证请求者是团队成员"。这确保安全性是 OK 的。但从系统运维角度：
- 用户从团队退出后，其 `RagAdvisorFactory.advisorCache` 中缓存的该团队 Advisor 永久存在
- 如果用户频繁加入/退出团队，缓存会积累大量无用条目
- 当前用 `ConcurrentHashMap` 无 TTL 无容量限制，长期运行会 OOM

**影响代码：**
- `RagAdvisorFactory.advisorCache`

**严重度：🟠 中风险**

**建议方案：**
1. 在 `ChatAdvisorChainFactory.buildChain()` 中，对 teamId 不为 null 的场景，不使用缓存（直接 `buildAdvisor`），减少缓存复杂度
2. 或使用 `Caffeine` 缓存替换 `ConcurrentHashMap`，设置 `expireAfterAccess(30, TimeUnit.MINUTES)` + `maximumSize(200)`

### OM-10 🟠 中风险：创建者解散团队时，待审批文档的文件处理遗漏

**问题描述：**
PRD §4.1.3 解散团队的流程是：
1. 逻辑删除 team + team_member + rag_document
2. 异步清理 MinIO 文件 + 向量数据

但未明确处理 **PENDING_APPROVAL** 状态的文档：
- 这些文档的 MinIO 文件已上传但未被审批
- 解散流程应该拒绝这些审批并清理文件，还是直接标记为已清理？
- 如果解散流程只是逻辑删除 `rag_document`（设 deleted=1），MinIO 文件清理异步进行，PENDING 审批记录（`team_upload_approval`）是否也要同步更新状态？

**影响代码：**
- `TeamServiceImpl.dissolve()`（新增）
- `ApprovalTimeoutJob.java`（新增）

**严重度：🟠 中风险**

**建议方案：**
1. 解散团队时，将所有 PENDING_APPROVAL 文档的状态更新为 REJECTED（审计友好）
2. 批量更新所有 PENDING 审批记录为 REJECTED + `review_comment = "团队已解散"`
3. 文件清理放入异步队列，与已审批文档的文件一起处理

### OM-11 🟠 中风险：缺少团队文档的 ETL 状态变更通知

**问题描述：**
个人信息流中，`DocumentApplicationServiceImpl.upload()` 上传后直接返回 `PROCESSING` 状态，前端轮询或 WebSocket 通知最终状态。但团队文档的流程：
1. 上传 → `PENDING_APPROVAL`
2. 审批通过 → `UPLOADED` → ETL 触发 → `PROCESSING` → `COMPLETED`/`FAILED`

PRD 没有设计状态变更通知机制（WebSocket/SSE 推送）。前端需要知道：
- 我的审批是否被通过/拒绝
- 文档 ETL 是否完成

**影响代码：** 新增 WebSocket/SSE 通知机制或增加前端轮询端点

**严重度：🟠 中风险**

**建议方案：**
1. 最小方案：提供 `GET /api/documents/{id}` 接口返回实时状态，前端定时轮询
2. 中期方案：审批通过/拒绝时通过 Server-Sent Events 推送状态变更
3. PRD 中补充状态变更的客户端体验设计

---

## 四、并发边界

### CC-01 🔴 阻塞：解散团队与审批操作的并发竞态

**问题描述：**
场景：CREATOR 执行解散团队操作的同时，ADMIN 正在审批某文档。

- **Thread A（解散）：** 逻辑删除 `rag_document`（设置 deleted=1）
- **Thread B（审批通过）：** 乐观锁更新 `team_upload_approval.status` → 成功 → 更新 `rag_document.status` → 成功 → 触发 ETL

此时 **Thread B 可能成功审批一个已被逻辑删除的文档**。ETL 触发后会发现文档 deleted=1，导致 ETL 失败或向量数据泄漏。

**保护缺口：** PRD 的审批乐观锁（`WHERE status='PENDING'`）**只保护审批记录之间的并发**，不保护审批与团队解散之间的并发。审批操作没有检查 `team.deleted` 或 `rag_document.deleted` 状态。

**影响代码：**
- `TeamServiceImpl.dissolve()`（新增）— 团队解散流程
- `TeamApprovalServiceImpl.approve()`（新增）— 审批通过流程

**严重度：🔴 阻塞**

**建议方案：**
1. 审批通过后，在触发 ETL 之前检查 `rag_document.deleted == 0`，如果已被删除则回滚审批状态
2. 解散团队操作使用**排他锁**：`SELECT ... FROM team WHERE id = ? FOR UPDATE`，确保解散期间没有并发审批
3. 或在业务层使用 Redis 分布式锁：`lock:team:dissolve:{teamId}`，审批通过也需要获取同一把锁的**共享锁**
4. **推荐方案：** 解散团队的事务同时更新所有 PENDING 审批记录为 REJECTED，审批操作的乐观锁 `WHERE status='PENDING'` 会自动失败（因为状态已被改为 REJECTED）

### CC-02 🟡 高风险：成员被移除时，其待审批文档的审批状态未处理

**问题描述：**
- User A（MEMBER）上传文档到团队，处于 PENDING_APPROVAL
- CREATOR 在审批前执行"移除成员"操作（`team_member.status = 0`）
- 该文档的审批记录仍然存在（PENDING 状态），但上传者已不是成员
- 后续 CREATOR/ADMIN 查看成员上传记录时会发现一个不属于任何成员的上传

**影响代码：**
- `TeamMemberServiceImpl.removeMember()`（新增）
- `TeamApprovalServiceImpl` — 审批列表查询

**严重度：🟡 高风险**

**建议方案：**
1. 移除成员时自动拒绝其所有 PENDING 审批：`UPDATE team_upload_approval SET status=REJECTED, review_comment='上传者已退出团队' WHERE uploader_id=? AND team_id=? AND status='PENDING'`
2. 同步更新对应的 `rag_document.status` → REJECTED
3. 异步清理 MinIO 文件

### CC-03 🟡 高风险：两个管理员同时审批同一文档 — 乐观锁已保护但缺少事务隔离级别验证

**问题描述：**
PRD §4.4.2 设计了乐观锁：
```sql
UPDATE team_upload_approval
SET status='APPROVED', reviewer_id=?, reviewed_at=NOW()
WHERE id=? AND status='PENDING'
```

这个设计是正确的。但需要验证：
1. MySQL 默认 RR 隔离级别下，两个管理员的 `SELECT ... WHERE status='PENDING'` 都能读到该记录
2. 第一个 UPDATE 成功（affects=1），第二个 UPDATE 发现 affects=0 → 抛异常
3. **但第二个管理员的 SELECT 已经读到了 PENDING 状态**，如果前端显示"待审批列表"时两个管理员同时看到同一条记录并都点了"通过"，第二个会得到错误提示，这是 OK 的

**潜在问题：** 第一个审批通过的事务中，更新 `rag_document.status → UPLOADED` 和触发 ETL 不在同一个事务中（ETL 是异步的）。如果更新 rag_document 成功但 ETL 触发失败，需要补偿。

**影响代码：**
- `TeamApprovalServiceImpl.approve()`（新增）

**严重度：🟡 高风险**

**建议方案：**
1. 乐观锁已足够保护并发审批，无需额外措施
2. 审批事务只做 `approval.status=APPROVED` + `rag_document.status=UPLOADED`，ETL 触发使用 `@TransactionalEventListener(phase = AFTER_COMMIT)` 确保事务提交后触发
3. ETL 触发失败时应有补偿机制（定时任务扫描 status=UPLOADED 但无向量数据的文档）

### CC-04 🟠 中风险：同一成员同时上传多个文件到同一团队

**问题描述：**
PRD 设计的上传额度 `member.upload_limit_mb` 是每次上传请求的文件大小上限，非累计额度。每人可以同时发起多个上传请求（通过分片上传或多次 REST 调用），系统没有做并发上传的限流或并发控制。

**问题：** 这不是功能 bug（每次校验是幂等的），但如果团队有 50 个成员同时大量上传，MinIO 存储和 ETL 处理队列可能过载。

**严重度：🟠 中风险**

**建议方案：**
1. 短期：在 Controller 层添加 `@RateLimiter`（Guava RateLimiter 或 Spring Bucket4j），按 userId 限流
2. 中期：`ThreadPoolTaskExecutor` 设置合理队列大小和拒绝策略

---

## 五、落地建议

### RA-01：策略模式迁移的推荐实施顺序

**推荐顺序（非并行）：**

```
Phase 1 — 提取基础设施（无业务变更）
  1. 提取 DocumentPersistenceService（共享 persistDocument 逻辑）
  2. 扩展 EtlCandidate 增加 teamId 字段（传入 null 的调用点更新）
  3. 扩展 EtlDispatchService.dispatchAsync() 签名增加 teamId
  4. 扩展 EtlPipelineServiceImpl 的 metadata 注入（teamId 为 null 时不写）

Phase 2 — 引入策略模式（无回归风险）
  5. 实现 UploadStrategy 接口 + PersonalUploadStrategy（复制现有逻辑）
  6. 实现 UploadStrategyFactory（先只路由到 PersonalUploadStrategy）
  7. DocumentApplicationServiceImpl.upload()/uploadBatch() 改为委托策略
  8. ⚠️ 此时全链路回归测试（个人上传不可有任何变化）

Phase 3 — 团队功能接入
  9. 实现 TeamUploadStrategy + 额度校验 + 审批流程
  10. UploadStrategyFactory 增加 teamId→TeamUploadStrategy 路由
  11. DocumentController 增加 teamId 参数
  12. ChunkUploadController/Service 增加 teamId 支持

Phase 4 — RAG 检索
  13. ChatRequest 增加 teamId（处理 Jackson 兼容性）
  14. RagAdvisorFactory.create(userId, teamId) 重构缓存键和 HybridDocumentRetriever
  15. ChatAdvisorChainFactory + ChatServiceImpl 传递 teamId
```

### RA-02：关键测试用例建议

| 测试场景 | 验证点 | 优先级 |
|----------|--------|--------|
| 个人上传 + 批量上传 | 行为与改造前完全一致（对比测试） | P0 |
| 个人秒传 | findExistingForQuickUpload 不误匹配团队文档 | P0 |
| 团队普通成员上传 → 审批拒绝 | MinIO 文件被删除，rag_document.status=REJECTED | P0 |
| 两个管理员同时审批同一文档 | 乐观锁 winner 成功，loser 得到 APPROVAL_ALREADY_PROCESSED | P0 |
| 解散团队 + 并发审批 | 解散后审批无法成功，所有 PENDING 变为 REJECTED | P0 |
| 个人 RAG 检索 | teamId=null 时仅检索 userId 匹配的文档 | P0 |
| 团队 RAG 检索 | teamId=xxx 时仅检索该团队的文档 | P0 |
| 用户从团队退出后尝试检索该团队文档 | TeamMembershipVerifier 拦截 403 | P1 |
| 分片上传团队文档 | ChunkUpload 的 persistDocument 正确设置 PENDING_APPROVAL | P1 |
| 审批超时 7 天后自动拒绝 | 定时任务正确更新状态 + 清理 MinIO | P1 |
| ChatRequest JSON 反序列化（无 teamId 字段） | 旧客户端请求不报错 | P1 |

### RA-03：监控指标建议

| 指标 | 用途 |
|------|------|
| `team_upload_pending_count` | 待审批积压监控 |
| `team_upload_approval_latency_p99` | 审批端到端延迟 |
| `rag_advisor_cache_size` | Advisor 缓存大小，防内存泄漏 |
| `team_dissolve_cleanup_failures` | 解散团队异步清理失败计数 |
| `approval_concurrent_conflict_count` | 乐观锁冲突次数 |

---

## 总结

| 严重度 | 数量 | 关键项 |
|--------|------|--------|
| 🔴 阻塞 | 6 | AR-01 ChatRequest Jackson、AR-02 EtlCandidate 编译破坏、AR-03 策略迁移回归、IN-01 缓存键冲突、IN-02 分片上传 teamId 穿透、OM-01 retry 未适配 |
| 🟡 高风险 | 12 | IN-03~07 查询语义/向量/VRL/删除权限/硬编码、OM-02~08 状态机/ETL/审批/通知/DTO/search/秒传、CC-02/03 成员移除/审批并发 |
| 🟠 中风险 | 4 | OM-08~10 清理失败/缓存泄漏/解散遗漏、CC-04 并发上传 |

**核心结论：**
PRD v1.1 在功能完整性上已相当完善，但在以下三个系统的**具体对接点**上存在阻塞性遗漏：
1. **RagAdvisorFactory 缓存键设计**（IN-01）需要重新设计复合键
2. **ChunkUploadServiceImpl 的分片上传链路**（IN-02）完全绕过了策略模式，teamId 无法穿透
3. **ChatRequest record 扩展**（AR-01）和 **EtlCandidate record 扩展**（AR-02）导致编译级破坏

建议在代码落地前先解决这 6 个阻塞项，再按 RA-01 的分阶段实施顺序推进。
