# PRD — ECR Extraction Pipeline

## Goal

离线实体/事件抽取、规范化、描述拼接 + embedding + 文档删除/supersede 时实体索引清理。在现有 ETL 管道 Load 阶段完成后异步触发实体索引构建，并在文档生命周期事件中执行级联清理，保证无孤儿数据。

## Confirmed Facts（代码库核验，2026-07-17）

### 事件发布钩子点

- **FastTrackStrategy.asyncVectorize**（`src/main/java/com/smart/rag/rag/etl/FastTrackStrategy.java:170-209`）：
  - `:196` `loader.load(chunks)` — chunk 落库完成点
  - `:197` `vectorStoreMapper.deleteFastTrackRows(c.documentId())` — BM25 临时行清理
  - **EtlVectorizedEvent 发布点**：`:196` 成功后、`:197` 之前插入（§8.2 时序修正）
  - `:170` 方法签名 `asyncVectorize(EtlCandidate c, List<Document> docs)`
  - `:201-205` `exceptionally` 分支 — 失败时走 `statusManager.markVectorFailed`，**不发布事件**（符合 §8.3 失败隔离）

- **EtlCompletedEvent**（`src/main/java/com/smart/rag/rag/event/EtlCompletedEvent.java`）：
  - record 格式：`EtlCompletedEvent(Long documentId, Long userId, @Nullable Long teamId)`
  - **EtlVectorizedEvent 须保持相同 record 结构**

### 清理钩子点

- **DocumentSupersedeService.supersedeOldVersion**（`src/main/java/com/smart/rag/rag/service/impl/DocumentSupersedeService.java:330-365`）：
  - `:342-343` `vectorStoreLoader.deleteByDocumentId(oldDocId)` — 清理必须在**此之前**执行（§8.4）
  - 当前步骤 1（`:331-339`）事务内更新 SUPERSEDED 状态
  - 步骤 2（`:341-346`）删向量 — **清理钩子插在步骤 1 完成后、步骤 2 之前**

- **DocumentLifecycleService.cascadeDelete**（`src/main/java/com/smart/rag/rag/service/impl/DocumentLifecycleService.java:53-84`）：
  - `:59` `vectorStoreLoader.deleteByDocumentId(id)` — 清理必须在**此之前**执行（§8.4）
  - `:73` `ragDocumentMapper.deleteById(id)` — DB 逻辑删除
  - `:76` `eventPublisher.publishEvent(new DocumentDeletedEvent(id))` — 已有事件发布机制

### LLM SPI

- **ChatCapable 接口**（`src/main/java/com/smart/rag/infrastructure/llm/ChatCapable.java`）：
  - `:18` `LlmResponse chat(ChatRequest request)` — 阻塞式对话，实体抽取使用此方法
  - 通过 `LlmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class)` 获取（llm-spi.md:66）
  - **DIP 约束**：EntityExtractionService 依赖 `ChatCapable` 接口，不依赖具体客户端（§10.3）

- **EmbeddingCapable 接口**（`src/main/java/com/smart/rag/infrastructure/llm/EmbeddingCapable.java`）：
  - `:19` `float[] embed(String text, EmbeddingType type)` — 单条 embed
  - `:28` `default List<float[]> embedBatch(List<String> texts, EmbeddingType type)` — 批量 embed（默认逐条）
  - `AbstractEmbeddingClient.embedBatch`（`:48-56`）默认 O(n) sequential，`BATCH_WARN_THRESHOLD=10`
  - **EntityEmbeddingService 须注意**：DashScope 厂商实现 `BailianEmbeddingClient` 可能未覆写 `embedBatch`，需分批调用（每批 ≤ 20 条，per EmbeddingCapable javadoc）

### Mapper 规范

- MyBatis-Plus：现有 Mapper 均为 `@Mapper` 注解 + `extends BaseMapper<T>`（如 `RagDocumentMapper:9-10`）
- XML 约定：namespace = `com.smart.rag.rag.mapper.XxxMapper`，XML 存放于 `src/main/resources/mapper/XxxMapper.xml`
- 复杂 SQL（含 CTE、ON CONFLICT、表达式索引等）放 XML；简单 CRUD 可用注解

### Prompt 模板

- 现有 prompt 存放于 `src/main/resources/static/prompt/`（XML 格式）
- 实体抽取 prompt（§4.2）可存放同目录（如 `entity-extraction.xml`）或内联为常量（短 prompt）

## Requirements

### R1：三服务 SRP 拆分（§4.4）

| 服务 | 职责 | SRP 边界 |
|---|---|---|
| `EntityExtractionService`（`rag/service/impl/`） | **编排**：监听 `EtlCompletedEvent`/`EtlVectorizedEvent`，调度 Step 1-6 | 仅编排，不含规范化/embedding 逻辑 |
| `EntityCanonicalizationService`（`rag/service/impl/`） | name_norm 归一化 + description 分组拼接 + UPSERT + 写 `rag_chunk_entity` + degree 同步（派生列） | 仅规范化与聚合 |
| `EntityEmbeddingService`（`rag/service/impl/`） | 聚合后 description 的批量 embed → 更新 `rag_entity.embedding` | 仅 embedding（Step 4 异步） |

### R2：防 lost-update 批量策略（§4.4）

ETL 管道使用 IO/CPU 双线程池并行处理 chunk（`EtlPipelineServiceImpl`），多 chunk 可能同时抽取到同名实体（如 "PostgreSQL"）。**禁止逐 chunk SELECT→concat→UPDATE**。

采用**文档级聚合策略**：
1. 文档所有 chunk 抽取结果收集到内存
2. 按 `name_norm` 分组，拼接 description
3. 批量 UPSERT `rag_entity`（`ON CONFLICT (name_norm, user_id, COALESCE(team_id,-1)) DO UPDATE SET description = EXCLUDED.description, ...`）

### R3：Level-1 规范化（§4.3）

仅实现 `name_norm = NFC_normalize(lowercase(trim(name)))`。Level 2/3 为后续迭代。

### R4：描述拼接 + 压缩 + embed（§4.4）

- 同名实体跨 chunk 拼接 description（句号分隔）
- 超过 500 字符时用 LLM 压缩摘要后再 embed
- `degree` 作为**派生列**在写入 `rag_chunk_entity` 时同步维护（= entity 在 `rag_chunk_entity` 的行数），不在 `rag_entity` 表上设触发器

### R5：双事件监听（§8.2）

- `EtlVectorizedEvent(documentId, userId, teamId)` — 由 `FastTrackStrategy.asyncVectorize:196`（`loader.load()` 成功后）发布
- `EntityExtractionService` 提供 `@EventListener` 监听 `EtlCompletedEvent`（Standard 路径）和 `EtlVectorizedEvent`（FastTrack 路径），**委托同一 `extractAndIndex(documentId, userId, teamId)`**
- `extractAndIndex` 按 `documentId` 从 `vector_store` 读取已落库的 chunk，不依赖事件携带 chunkId

### R6：清理服务（§8.4）

`EntityIndexCleanupService.cleanupByDocumentId(documentId)` 统一清理逻辑，两个触发点：
1. `DocumentSupersedeService.supersedeOldVersion` — 步骤 1（事务更新 SUPERSEDED）完成后、步骤 2（`deleteByDocumentId`）**之前**
2. `DocumentLifecycleService.cascadeDelete` — `deleteByDocumentId` **之前**

清理 SQL（§8.4）：
- 删除 `rag_chunk_entity` WHERE chunk_id IN（文档 chunk）
- 删除 `rag_event` WHERE chunk_id IN（文档 chunk）
- 重算受影响 `rag_entity.degree`
- 删除 `degree=0` 的孤儿实体
- 标记受影响实体 `community_stale=TRUE`

### R7：失败隔离（§8.3）

- 单个 chunk LLM 抽取失败不阻塞其他 chunk
- FastTrack `asyncVectorize` 失败（`markVectorFailed`）→ 不发布 `EtlVectorizedEvent` → 不进入实体索引
- 实体抽取整体失败不影响 Path A/B（BM25/vector 检索不受影响）

### R8：跨用户隔离

- 所有实体/event 写入均携带 `user_id`/`team_id`
- `cleanupByDocumentId` 按 `documentId` 清理，隔离由外层保证（调用方只清理自己文档）
- `extractAndIndex` 查 `vector_store` 时按 `documentId` 过滤（通过 chunk metadata 中的 documentId）

## Acceptance Criteria

- [ ] AC1：ingest 一篇多主题文档 → `rag_entity`/`rag_event`/`rag_chunk_entity` 三表正确 populated（entity.name_norm 已归一化，description 已拼接，degree = COUNT(chunk_entity)，event.summary 非空，event.chunk_id 对应 vector_store.id）
- [ ] AC2：`degree` = `SELECT count(*) FROM rag_chunk_entity WHERE entity_id = X`，每个实体的 degree 值一致
- [ ] AC3：同名实体（如 "PostgreSQL" 出现在 3 个 chunk）description 为三段拼接，embedding 为拼接后文本的向量
- [ ] AC4：description 超过 500 字符时被 LLM 压缩后 embed
- [ ] AC5：删除文档后 → `rag_chunk_entity`/`rag_event` 无残留指向已删 chunk_id 的行；受影响 `rag_entity.degree` 已重算；`degree=0` 的孤儿实体已清除
- [ ] AC6：supersede 场景 → 旧文档实体清理完成后才删旧向量 → 新文档实体索引独立重建 → 共享实体 degree 正确
- [ ] AC7：LLM 抽取单个 chunk 失败 → 其他 chunk 正常抽取 → vector_store 原始 chunk 检索不受影响（Path A/B 可用）
- [ ] AC8：FastTrack 路径 `asyncVectorize` 失败 → 不发布 `EtlVectorizedEvent` → 无实体索引数据产生
- [ ] AC9：StandardStrategy 路径 ETL 完成 → 发布 `EtlCompletedEvent` → 实体索引正常构建
- [ ] AC10：跨用户隔离 — user A 文档实体不出现在 user B 的查询结果中

## Dependencies（来自父 PRD 依赖图，逐字）

- **`ecr-db-migration`**：V21 schema 必须存在（4 表 + 索引），否则 mapper CRUD 失败
- 本子任务产出被以下子任务依赖：
  - `ecr-structure-scores` 需要 `EntityMapper`/`ChunkEntityMapper` 读取实体数据构建共现图
  - `ecr-path-c-retrieval` 需要 `EntityMapper`/`ChunkEntityMapper`/`EventMapper` 进行在线检索

## Out of Scope

- 结构分计算（weak_tie_score/bridge_score/community_id）— 属 `ecr-structure-scores`
- 在线检索 Path C（EntitySeedExtractor 等）— 属 `ecr-path-c-retrieval`
- 图算法（WeightedGraph/Louvain）— 属 `ecr-graph-algorithm`
- Level 2/3 规范化（别名词典、embedding 相似度合并）— 后续迭代
- `rag_entity_cooccurrence` 表写入 — 属 `ecr-structure-scores` 的 `EntityCooccurrenceMapper`
- EntityIndexService（§5 weak_tie/bridge SQL）— 属 `ecr-structure-scores`

## Open Questions

- **OQ1（已解决）ChatCapable vs 项目 LLM SPI**：已确认使用 `ChatCapable` 接口（`infrastructure/llm/ChatCapable.java`），通过 `LlmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class)` 获取。`entity.extractionModel` 可配置指定非默认候选 ID。遵循 `llm-spi.md` 注入契约（§2），**禁止**直接注入 `ChatClient.Builder`。
- **OQ2（待确认）batch embedding API 限制**：DashScope 文本嵌入 API 单次上限需确认（EmbeddingCapable javadoc 建议 ≤ 20 条）。`EntityEmbeddingService` 须分批调用，每批不超过供应商限制。实际批次大小可通过配置项控制（如 `entity.embeddingBatchSize`，默认 10）。
- **OQ3（已解决）Mapper 归属**：`EntityMapper`/`EventMapper`/`ChunkEntityMapper` 归本子任务（父 design.md Cross-Child Contract 第 1 条）。`EntityCooccurrenceMapper` 归 `ecr-structure-scores`。
- **OQ4（待确认）Prompt 模板外部化 vs 内联**：§4.2 prompt 较短（~20 行），可内联为类常量或外置 XML。建议内联为常量字符串，避免增加 XML 维护负担。实现时按 `rag/config/` 下配置项控制。
