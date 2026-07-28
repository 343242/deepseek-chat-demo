# Design — ECR Extraction Pipeline

## Authoritative Source

完整技术设计见 **`docs/design/entity-centric-retrieval.md`**（1605 行，已通过 2 轮审计）。本文件仅记录子任务级设计补充；任何正文细节以主文档为准。

## 章节引用映射

| 本子任务关注点 | 主文档章节 |
|---|---|
| 抽取流程 6 步 | §4.1 |
| 抽取 Prompt 模板 | §4.2 |
| 规范化策略（Level 1） | §4.3 |
| description 拼接 + embed + 防竞态 + SRP 拆分 | §4.4 |
| ETL 集成点 + FastTrack 时序修正 | §8.1, §8.2 |
| 失败隔离 | §8.3 |
| 文档删除/supersede 清理 | §8.4 |
| 组件包路径表 | §10.2 |
| 设计原则审计 | §10.3 |
| 延迟预算 | §11.4 |

## 子任务级设计补充

### 1. 三服务 SRP 拆分映射（§4.4）

```
EntityExtractionService（编排）
  ├─ @EventListener onEtlCompleted(EtlCompletedEvent)
  ├─ @EventListener onEtlVectorized(EtlVectorizedEvent)
  ├─ extractAndIndex(documentId, userId, teamId)
  │   ├─ Step 1: 从 vector_store 查 chunk 列表
  │   ├─ Step 2: 并行 LLM 抽取（per chunk）→ 收集到内存
  │   ├─ Step 3: 委托 EntityCanonicalizationService
  │   │         → name_norm 归一化 + 分组拼接 + UPSERT rag_entity + 写 rag_chunk_entity + degree 同步
  │   ├─ Step 4: 委托 EntityEmbeddingService
  │   │         → 批量 embed 聚合后 description → 更新 rag_entity.embedding
  │   ├─ Step 5: 标记 community_stale=TRUE（触发 structure-scores 批处理）
  │   └─ Step 6: 委托 EntityIndexService（属 ecr-structure-scores）
  └─ 不含规范化/embedding 逻辑（SRP）

EntityCanonicalizationService（规范化）
  ├─ canonicalize(name): String → name_norm = NFC(lowercase(trim(name)))
  ├─ aggregateAndUpsert(extractions, userId, teamId)
  │   ├─ 按 name_norm 分组
  │   ├─ 拼接 description（句号分隔）
  │   ├─ 批量 UPSERT rag_entity (ON CONFLICT DO UPDATE)
  │   ├─ 批量 INSERT rag_chunk_entity (ON CONFLICT DO NOTHING)
  │   └─ 同步 degree（UPDATE rag_entity SET degree = subquery）
  └─ 不含 LLM 调用/embedding 逻辑（SRP）

EntityEmbeddingService（embedding）
  ├─ embedDescriptions(entities): 异步批量
  │   ├─ 过滤 description 超 500 字符 → LLM 压缩
  │   ├─ 分批 embed（每批 ≤ batchSize，默认 10）
  │   └─ UPDATE rag_entity SET embedding = :vec WHERE id = :id
  └─ 不含规范化/chunk_entity 写入逻辑（SRP）
```

### 2. Lost-Update 防护（§4.4 并发安全）

**风险场景**：文档 D 的 chunk_1 和 chunk_2 并行抽取到同名实体 "PostgreSQL"。如果各自独立 `SELECT → concat → UPDATE`，线程 B 覆盖线程 A 的拼接结果。

**防护策略**：文档级聚合——所有 chunk 抽取结果先收集到内存（List），然后按 name_norm 分组一次性拼接后批量 UPSERT。单文档处理为单线程（per documentId），不存在并发写入同名实体的竞态。

**跨文档竞态**：不同文档同时抽取同名实体（如 user A 和 user B 各自上传包含 "PostgreSQL" 的文档）。`ON CONFLICT (name_norm, user_id, COALESCE(team_id,-1)) DO UPDATE` 的唯一索引保证不同用户的同名实体互不冲突。同用户场景下，两文档的 extractAndIndex 可能并发执行——UPSERT 的 `ON CONFLICT DO UPDATE SET description = rag_entity.description || '\n' || EXCLUDED.description` 拼接顺序不确定但最终一致（description 拼接是幂等的，append 语义）。

**注意**：跨文档并发拼接 description 时，`ON CONFLICT DO UPDATE` 的 `description` 拼接 SQL 须使用 `rag_entity.description || '\n' || EXCLUDED.description`（追加新描述），而非替换。这保证两个文档的描述都保留。但如果两个文档同时 UPSERT，可能产生重复追加（取决于 PG 行锁时序）。**推荐**：在 extractAndIndex 入口加 per-documentId 锁（如 `ConcurrentHashMap<Long, Boolean>` 或数据库行锁），保证同一文档的 extractAndIndex 串行执行。

### 3. degree 派生列维护契约（父 design.md Cross-Child Contract 第 2 条）

- `rag_entity.degree` = `SELECT count(*) FROM rag_chunk_entity WHERE entity_id = :entityId`
- **写入时机**：EntityCanonicalizationService 在写入 `rag_chunk_entity` 后，通过单条 UPDATE 同步 degree
- **清理时**：EntityIndexCleanupService 重算受影响实体的 degree
- **读取方**：`ecr-structure-scores` 仅读取 degree 用于 `WHERE degree < 100` 性能预算，**不得写** degree
- 不使用数据库触发器（避免隐式副作用，违反项目 KISS 原则）

### 4. Supersede 清理顺序（§8.4）

```
supersedeOldVersion(oldDocId, newDocId):
  Step 1: 事务 → 标记旧文档 SUPERSEDED  （已有，:331-339）
  Step 1.5: 【新增】EntityIndexCleanupService.cleanupByDocumentId(oldDocId)
           → 在删 vector_store 之前，捕获受影响 entity_id
  Step 2: vectorStoreLoader.deleteByDocumentId(oldDocId)  （已有，:342-343）
  Step 3: vectorStoreMapper.deleteFastTrackRows(oldDocId)  （已有，:348-352）
  Step 4: fileStorageService.delete(...)                   （已有，:355-361）
```

新文档的实体索引由新文档自身的 `EtlVectorizedEvent`/`EtlCompletedEvent` 异步驱动，不依赖 supersede 方法内的时序。共享实体（如 "PostgreSQL"）的 degree 在旧文档清理时重算减一，新文档抽取时再加一。

### 5. §8.4 G3 性能注记（子决策）

`vector_store.metadata` 是 `JSON` 非 `JSONB`（schema:807），`metadata->>'documentId'` 的清理走全表扫描。两个选项：

| 方案 | 优劣 |
|---|---|
| A: 建表达式索引 `CREATE INDEX idx_vs_meta_docid ON vector_store ((metadata->>'documentId'))` | 清理快，但增加写入开销 |
| B: 先按 `documentId` 查 chunk_id 列表（走现有 indexes），再用 `chunk_id IN (...)` 删除 | 不改 vector_store schema，额外一次 SELECT |

**建议采用方案 B**：不改 `vector_store` 表结构（Spring AI 契约），在 EntityIndexCleanupService 中先 `SELECT id FROM vector_store WHERE metadata->>'documentId' = :docIdStr` 取 chunk_id 列表，再用 `chunk_id IN (...)` 删除。性能可接受（单文档通常 < 100 chunk）。

## Design Principle Mapping

| 原则 | 落实点 |
|---|---|
| **SRP** | EntityExtractionService（编排）/ EntityCanonicalizationService（规范化聚合）/ EntityEmbeddingService（embedding）三服务各司一职；EntityIndexCleanupService 独立清理 |
| **DIP** | 依赖 `ChatCapable` 接口进行 LLM 抽取，不依赖具体客户端；通过 `LlmClientRegistry` 获取实例 |
| **OCP** | 新增的三个服务为纯新增类，不修改现有服务方法签名（除 FastTrackStrategy 新增事件发布 + DocumentSupersedeService 新增清理调用点） |
| **ISP** | Mapper 接口小而专（EntityMapper/EventMapper/ChunkEntityMapper 各负责一表） |
| **KISS** | Level-1 规范化（NFC+lowercase+trim），零成本无外部依赖；description 500 字符硬阈值简单可控 |
