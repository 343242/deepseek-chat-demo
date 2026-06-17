# PRD v1.1 架构深度审查报告（第二轮）

> 审查人：GLM 主会话 | 日期：2026-05-13
> 审查对象：`docs/TEAM-FEATURE-PRD.md` v1.1
> 审查重点：架构嵌入可行性 / 集成难度 / 数据一致性 / 性能影响 / 落地风险

---

## 一、架构风险（阻塞级）

### AR-1: RagAdvisorFactory 缓存机制与 teamId 维度冲突 ⛔ 阻塞

**问题：** 现有 `RagAdvisorFactory` 使用 `ConcurrentHashMap<Long, RetrievalAugmentationAdvisor>` 按 userId 缓存 Advisor。每个 userId 只有一个 Advisor 实例，内部硬编码了 `FilterExpressionBuilder.eq("userId", ...)` 过滤条件。

新增 teamId 后，同一个用户在不同请求中可能检索个人空间（teamId=null）或不同团队空间（teamId=xxx），但缓存的 Advisor 只能绑定一种过滤条件。

**影响代码：**
- `RagAdvisorFactory.create(Long userId)` — 单参数签名，缓存 key 只有 userId
- `createUserIsolatedRetriever(Long userId)` — 只构建 userId 过滤
- `ChatAdvisorChainFactory.buildChain()` — 调用 `ragAdvisorFactory.create(userId)`

**PRD 说的：** `create(userId, teamId)` + `FilterExpressionBuilder.eq("teamId", teamId)`

**真实落地问题：**
1. 缓存 key 必须改为复合 key（如 `userId + "_" + teamId` 或 `Pair<Long, Long>`），但 `ConcurrentHashMap<Long, ...>` 改为 `ConcurrentHashMap<String, ...>` 会影响所有调用点
2. teamId=null 时（个人空间）只按 userId 过滤，teamId 有值时需 `userId AND teamId` 组合过滤——这是**两种不同的过滤策略**，不能简单拼字符串
3. **HybridDocumentRetriever** 内部 BM25 SQL 也有 `AND metadata->>'userId' = ?` 硬编码，需要同时加 `AND metadata->>'teamId' = ?` 条件
4. 缓存容量膨胀：10 个用户 × 平均 3 个团队 = 30 个 Advisor 实例，每个实例持有独立的 `VectorStoreDocumentRetriever` + filter

**建议方案：**
- **废弃 Advisor 缓存**，每次请求构建新实例（当前 PRD 中的 Advisor 本身是轻量级对象，真正的重资源 VectorStore 是共享的）
- 或者：缓存 key 改为 `record AdvisorCacheKey(Long userId, Long teamId)`，用 `ConcurrentHashMap<AdvisorCacheKey, ...>`
- `createUserIsolatedRetriever` 签名改为 `createRetriever(Long userId, @Nullable Long teamId)`，teamId 有值时构建复合 filter
- `HybridDocumentRetriever` 构造函数增加 `@Nullable Long teamId`，BM25 SQL 动态拼接

---

### AR-2: EtlStatus 状态机破坏风险 ⛔ 阻塞

**问题：** 现有 ETL 状态机是线性流转：`UPLOADED → PARSING → CHUNKING → VECTORIZING → COMPLETED`，异常时 `→ FAILED / VECTOR_FAILED`。`PROCESSING` 是中间聚合状态。

PRD 新增 `PENDING_APPROVAL` 和 `REJECTED`，但这两个状态位于 ETL 管道的**入口之前**——文档在 MinIO 中但尚未进入 ETL。

**影响代码：**
- `EtlStatusManager` — 所有 `updateStatus` 调用点
- `EtlDispatchServiceImpl` — `executeSingle()` / `dispatchAsync()` 会检查文档状态吗？
- `FastTrackStrategy` / `StandardStrategy` — 状态转换链
- 审批通过后触发 ETL 的入口点（PRD 只说"触发 ETL（异步）"，没指定走哪个方法）

**真实落地问题：**
1. 现有 `dispatchAsync()` 是否会检查 `rag_document.status`？如果它扫描 `UPLOADED` 状态的文档来触发，那 `PENDING_APPROVAL` 的文档不会被误触发吗？
2. 审批通过后调用 `dispatchAsync()` 还是 `executeSingle()`？两个方法签名都不含 teamId 参数，但 ETL 管道需要在 metadata 中写入 teamId
3. 如果 ETL 重试机制（如有定时任务扫描 FAILED 文档重试）不排除 PENDING_APPROVAL 状态，会尝试处理未审批的文档
4. REJECTED 文档的 MinIO 文件删除后，如果 ETL 重试逻辑尝试处理该文档会失败

**建议方案：**
- PRD 明确：审批通过后调用 `etlDispatchService.dispatchAsync(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId)` — **但这个方法签名不含 teamId**
- 需要扩展 `dispatchAsync()` 签名增加 teamId，或在调用前将 teamId 写入 `rag_document.team_id`，ETL 管道从 `rag_document` 读取
- **建议走后者**：ETL 管道从 `rag_document` 表读取 teamId（已有 team_id 字段），不需要改 `EtlCandidate` record 签名。但需要修改 `EtlDispatchServiceImpl` 中构建 `EtlCandidate` 时加入 teamId
- 新增 EtlStatus 状态机文档：明确 PENDING_APPROVAL / REJECTED 是"ETL 前状态"，不参与 ETL 管道的状态转换
- 如有 ETL 重试定时任务，必须在查询条件中排除 `PENDING_APPROVAL` / `REJECTED`

---

### AR-3: 向量 metadata 缺少 teamId — 检索隔离不完整 🔴 高风险

**问题：** 现有 ETL 管道在写入向量时，metadata 只包含 `documentId` 和 `userId`：

```java
// StandardStrategy.java L114-115
chunk.getMetadata().put("documentId", docIdStr);
chunk.getMetadata().put("userId", userIdStr);
```

FastTrackStrategy 的 BM25 行同理。如果 ETL 管道不写入 `teamId` metadata，那么团队文档的向量数据无法按 teamId 过滤，RAG 检索会**返回所有用户的文档**（只要有 teamId tag 的）。

**影响代码：**
- `StandardStrategy.execute()` L114-115 — chunk metadata 写入
- `FastTrackStrategy.execute()` L145-146 — BM25 metadata 写入
- `FastTrackStrategy.asyncVectorize()` L177-178 — 异步向量化 metadata 写入
- `EtlCandidate` record — 不含 teamId 字段

**建议方案：**
1. `EtlCandidate` 新增 `Long teamId` 字段（PRD 已提到但需在 EtlCandidate.java 中实际加）
2. 所有 metadata 写入点增加：`if (teamId != null) chunk.getMetadata().put("teamId", String.valueOf(teamId))`
3. BM25 SQL 中的 metadata JSON 也要包含 teamId
4. **已存在的向量数据不包含 teamId**，需要考虑迁移策略（全量回刷或接受旧数据无 teamId 过滤）

---

## 二、集成难点（高风险）

### IN-1: UploadStrategy 策略模式迁移 — DocumentApplicationServiceImpl 逻辑提取复杂 🔴 高风险

**问题：** 现有 `DocumentApplicationServiceImpl.upload()` 逻辑包含：

1. 文件校验（DocumentValidator）
2. MinIO 存储（FileStorageService）
3. DB 写入（RagDocumentMapper）
4. ETL 触发（EtlDispatchService）
5. Redis 秒传/分片状态管理
6. **事务边界**：文件已存 MinIO 但 DB 写入失败的处理

PRD 说封装到 `PersonalUploadStrategy`，但"封装"不是简单搬代码：

**真实问题：**
- `PersonalUploadStrategy` 需要注入 `DocumentValidator`、`FileStorageService`、`RagDocumentMapper`、`EtlDispatchService` — 基本是 `DocumentApplicationServiceImpl` 的全部依赖
- `DocumentApplicationServiceImpl` 原本有事务管理，迁移后事务边界在哪？
- 批量上传（`uploadBatch`）返回部分成功结果的逻辑如何迁移？
- **回归风险**：个人上传是核心路径，任何迁移都可能引入 bug

**建议方案：**
- **不改现有代码**，采用装饰器模式：`DocumentApplicationServiceImpl` 保持不变，新增 `TeamUploadDecorator` 在调用前增加团队校验和审批逻辑
- 或者：`DocumentApplicationServiceImpl` 的 `upload()` 方法保持不变（作为个人上传），Controller 层根据 teamId 路由到新的 `TeamDocumentService.upload()`
- 如果坚持策略模式：先用 `PersonalUploadStrategy` 完整包装现有逻辑，写集成测试验证回归后，再在 `DocumentApplicationServiceImpl` 中切换为策略委托

---

### IN-2: 分片上传的团队校验时序问题 🟡 中风险

**问题：** `ChunkUploadServiceImpl.init()` 阶段校验 MIME 和文件大小，但不校验团队成员身份（因为当前没有团队概念）。加入团队后：

- `init` 阶段需要校验团队成员身份 + 上传额度（基于总文件大小）
- `uploadChunk` 阶段：成员在上传过程中被移除，分片上传到一半怎么办？
- `complete` 阶段：合并后创建 rag_document，此时成员可能已不存在

**PRD 说：** "`init` 阶段即进行团队成员校验和额度校验"

**真实问题：**
- 分片上传是长时间操作（可能跨越几分钟），中间成员状态变化无法感知
- `complete` 合并成功后创建 rag_document，应该再校验一次成员状态
- `ChunkUploadServiceImpl` 注入了 `SecurityUtils.getCurrentUserId()` 硬编码获取用户 ID，策略模式注入不进去

**建议方案：**
- init 阶段校验（准入控制）
- complete 阶段**再校验一次**成员状态 + 额度（最终一致性保障）
- 如果 complete 时成员已不存在，清理已上传分片 + MinIO 临时文件，返回错误

---

### IN-3: ChatRequest record 不可变 — teamId 传递链复杂 🟡 中风险

**问题：** `ChatRequest` 是 Java record（不可变），新增 `teamId` 字段后：

1. `ChatRequest` record 新增 `Long teamId` 字段
2. `withModel()` 方法（用于降级）需要同步更新，否则降级时 teamId 丢失
3. `ChatAdvisorChainFactory.buildChain()` 需要从 `ChatRequest` 中取 teamId 传给 `RagAdvisorFactory`
4. SSE 流式接口（`chatStream`）的参数传递：当前通过 `ChatRequest` body 传递，GET 请求需 query param

**影响代码：**
- `ChatRequest.withModel()` — 降级时需要 `new ChatRequest(newModel, message, conversationId, ragEnabled, mode, enableThinking, teamId)`
- `ChatController` — GET `/api/chat/stream` 需新增 `@RequestParam(required=false) Long teamId`
- `ChatServiceImpl.chat()` / `chatStream()` — 需传递 teamId 给 `ChatAdvisorChainFactory`
- `ChatAdvisorChainFactory.buildChain()` — 签名增加 teamId 参数

**建议方案：**
- `ChatRequest` record 新增 `Long teamId`，更新 `withModel()` 方法
- `ChatAdvisorChainFactory.buildChain(conversationId, request, modeStrategy)` 已经接收完整的 `ChatRequest`，直接从 `request.teamId()` 取值
- `RagAdvisorFactory.create(userId)` → `create(userId, teamId)` — 签名扩展
- SSE GET 接口：teamId 作为可选 query param，构建临时 ChatRequest 传入

---

## 三、遗漏改动点

### OM-1: HybridDocumentRetriever BM25 查询遗漏 teamId 过滤

**问题：** `HybridDocumentRetriever` 的 BM25 SQL 硬编码了 `AND metadata->>'userId' = ?`，但没有 teamId 过滤。团队文档的 BM25 行（`FastTrackStrategy.writeBm25Row`）写入时包含 teamId metadata，但检索时不按 teamId 过滤会返回跨团队结果。

**影响代码：** `HybridDocumentRetriever.bm25Search()` SQL

**建议：** BM25 SQL 增加动态条件：
```sql
AND metadata->>'userId' = ?
AND (metadata->>'teamId' = ? OR (? IS NULL AND metadata->>'teamId' IS NULL))
```

---

### OM-2: FastTrackStrategy BM25 行缺少 teamId metadata

**问题：** `FastTrackStrategy.writeBm25Row(Long documentId, String content, Long userId)` 签名不含 teamId，写入的 metadata 只有 `documentId` 和 `userId`。

**影响代码：** `FastTrackStrategy.writeBm25Row()` L144-146

**建议：** 签名增加 `@Nullable Long teamId`，metadata 有条件写入 teamId。

---

### OM-3: ParentDocumentPostProcessor 回查 SQL 缺少 teamId 隔离

**问题：** `ParentDocumentPostProcessor` 通过 `parentId` 从 `vector_store` 表回查父文档内容，SQL 没有按 userId 或 teamId 过滤。虽然 parentId 本身是唯一的，不会返回错误数据，但如果未来有文档 ID 冲突（理论上不可能但防御性编码），就存在风险。

**影响：** 低风险，当前不影响正确性。

---

### OM-4: DocumentApplicationServiceImpl 秒传逻辑未考虑团队场景

**问题：** `ChunkUploadServiceImpl.init()` 中有秒传检查（`findExistingForQuickUpload`），按 `fileMd5 + userId` 查找已有文档。团队场景下，同一文件可能被不同成员上传到不同团队——秒传匹配应该只匹配同一空间（个人 or 同一团队）。

**影响代码：** `ChunkUploadServiceImpl.findExistingForQuickUpload()`

**建议：** 秒传查询增加 teamId 过滤条件。

---

### OM-5: DocumentApplicationServiceImpl 中 EtlCandidate 构建需加 teamId

**问题：** 现有代码：
```java
candidates.add(new EtlCandidate(ragDoc.getId(), bucket, storageKey, originalFilename, mimeType, file.getSize(), currentUserId));
```
`EtlCandidate` record 新增 teamId 后，这里必须更新构造参数。

**影响代码：** `DocumentApplicationServiceImpl.upload()` L93-106

---

### OM-6: EtlDispatchServiceImpl 构建 EtlCandidate 需传递 teamId

**问题：** `EtlDispatchServiceImpl.executeSingle()` L58：
```java
EtlCandidate candidate = new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId);
```
以及 `dispatchAsync()` L75 同理。签名不含 teamId，但审批通过后触发 ETL 需要传递 teamId。

**建议：** 两个方法签名都增加 `@Nullable Long teamId` 参数。

---

## 四、性能影响评估

### PF-1: 团队成员身份校验延迟

每次团队文档上传、检索、删除都需查询 `team_member` 表校验成员身份。

**预估影响：**
- 单次 DB 查询 ~1-5ms（有索引 `idx_team_member_user(user_id, status)`）
- 上传路径：额外 1 次查询（校验成员）+ 1 次查询（获取额度）= +2-10ms
- RAG 检索路径：额外 1 次查询 = +1-5ms
- **可接受**，不需要缓存

---

### PF-2: RagAdvisorFactory 缓存容量膨胀

如果保留缓存机制：用户数 × 平均团队数 = 缓存条目数。

**预估：** 100 用户 × 3 团队 = 300 个 Advisor 实例。每个实例持有引用（VectorStore 共享），内存影响不大。但缓存失效策略复杂（成员退出团队后，对应的 Advisor 应失效）。

**建议：** 不缓存，每次构建新实例。VectorStore 本身是共享 Bean，Advisor 创建开销极低。

---

### PF-3: team_upload_approval 表增长

每条团队文档上传产生一条审批记录（审批通过后不再更新）。

**预估：** 低量级，单团队日上传量 < 100 时，年增长 < 36,500 条。按团队数 100 算 = 365 万/年，可接受。

---

## 五、落地建议总结

| 优先级 | 建议 |
|--------|------|
| ⛔ P0 | AR-1: RagAdvisorFactory 缓存机制必须重新设计，建议废弃缓存或改复合 key |
| ⛔ P0 | AR-2: EtlStatus 状态机扩展需文档化，ETL 管道入口需排除 PENDING_APPROVAL 状态 |
| ⛔ P0 | AR-3: ETL metadata 写入必须包含 teamId，否则团队检索隔离无效 |
| 🔴 P1 | IN-1: UploadStrategy 迁移建议先写集成测试验证回归，或考虑装饰器模式降低风险 |
| 🔴 P1 | OM-1/OM-2: BM25 写入和检索都需 teamId 支持，遗漏会导致混合检索模式返回跨团队结果 |
| 🔴 P1 | OM-5/OM-6: EtlCandidate + EtlDispatchService 签名扩展 |
| 🟡 P2 | IN-2: 分片上传 complete 阶段需二次校验成员状态 |
| 🟡 P2 | IN-3: ChatRequest.withModel() 需同步更新 teamId |
| 🟡 P2 | OM-4: 秒传逻辑需按 teamId 隔离 |
| 🟢 P3 | PF-2: 考虑不缓存 Advisor 实例 |

---

## 六、整体评估

**能否真实落地？** 可以，但 PRD 需要补充以下内容：

1. **RagAdvisorFactory 改造方案**（缓存策略 + 复合 filter 构建）— 当前缺失
2. **ETL 管道改造清单**（EtlCandidate 签名 + metadata 写入 + BM25 SQL + 排除逻辑）— 当前只提到"传递 teamId"，过于笼统
3. **已有向量数据的迁移策略**（旧数据无 teamId metadata）— 完全未提及
4. **分片上传的团队校验时序**（init + complete 双重校验）— 当前只提 init
5. **秒传逻辑的 teamId 隔离** — 完全未提及
6. **ChatRequest.withModel() 降级兼容** — 完全未提及

**建议：** 上述 6 项补充到 PRD §7.2「需要改动的模块」中，特别是 ETL 管道改造需要逐文件列出变更点。
