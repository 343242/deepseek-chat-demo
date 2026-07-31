# Implementation Plan — ECR Extraction Pipeline

## Prerequisites

- [ ] `ecr-db-migration` V21 schema 已落地（`db/migration/V21__entity_centric_index.sql` 通过 Flyway）
- [ ] 确认 DashScope embedding API 批量上限（`EmbeddingCapable.embedBatch` 文档或实测）

## Step 1 — EtlVectorizedEvent 事件定义

- [ ] 新建 `src/main/java/com/smart/rag/rag/event/EtlVectorizedEvent.java`
  - record 格式同 `EtlCompletedEvent`：`EtlVectorizedEvent(Long documentId, Long userId, @Nullable Long teamId)`
  - 参考 `src/main/java/com/smart/rag/rag/event/EtlCompletedEvent.java:12-16`
- [ ] 验证：`./mvnw compile`

## Step 2 — FastTrackStrategy 事件发布钩子（§8.2）

- [ ] 修改 `src/main/java/com/smart/rag/rag/etl/FastTrackStrategy.java`
  - 注入 `ApplicationEventPublisher`（构造函数 + 字段）
  - 在 `asyncVectorize()` 方法 `:196`（`loader.load(chunks)` 成功后）与 `:197`（`deleteFastTrackRows` 之前）之间插入：
    ```java
    eventPublisher.publishEvent(new EtlVectorizedEvent(
        c.documentId(), c.userId(), c.teamId()));
    ```
  - `exceptionally` 分支（`:201-205`）不发布事件（失败隔离，§8.3）
- [ ] 验证：`./mvnw compile` + FastTrackStrategy 现有测试仍绿

## Step 3 — MyBatis Mapper 脚手架

- [ ] 新建 `src/main/java/com/smart/rag/rag/mapper/EntityMapper.java`
  - `@Mapper` + `extends BaseMapper<RagEntity>`（RagEntity 为 `rag_entity` 的实体类）
  - 复杂 SQL 放 XML：`src/main/resources/mapper/EntityMapper.xml`
- [ ] 新建 `src/main/java/com/smart/rag/rag/mapper/EventMapper.java`
  - `@Mapper` + `extends BaseMapper<RagEvent>`
  - XML：`src/main/resources/mapper/EventMapper.xml`
- [ ] 新建 `src/main/java/com/smart/rag/rag/mapper/ChunkEntityMapper.java`
  - `@Mapper` + `extends BaseMapper<RagChunkEntity>`
  - XML：`src/main/resources/mapper/ChunkEntityMapper.xml`
- [ ] Mapper XML 中定义关键 SQL：
  - EntityMapper：`upsertByNormUserTeam`（INSERT ... ON CONFLICT DO UPDATE，含 description 追加拼接）
  - EntityMapper：`recalculateDegree`（UPDATE SET degree = subquery WHERE id IN）
  - EntityMapper：`deleteOrphans`（DELETE WHERE degree = 0）
  - ChunkEntityMapper：`selectEntityIdsByDocumentId`（通过 chunk_id 关联查出受影响 entity_id）
  - ChunkEntityMapper：`deleteByChunkIds`、`insertBatch`
  - EventMapper：`deleteByChunkIds`、`insertIgnore`
- [ ] 参考 `src/main/java/com/smart/rag/rag/mapper/RagDocumentMapper.java:9-10`（@Mapper 注解）和 `src/main/resources/mapper/VectorStoreMapper.xml:1-5`（XML namespace 约定）
- [ ] 验证：`./mvnw compile` + MyBatis mapper 扫描正确（启动测试或 mapper-scan 单测）

## Step 4 — 实体/事件/关联实体类

- [ ] 新建 `src/main/java/com/smart/rag/rag/entity/RagEntity.java`（对应 `rag_entity` 表）
- [ ] 新建 `src/main/java/com/smart/rag/rag/entity/RagEvent.java`（对应 `rag_event` 表）
- [ ] 新建 `src/main/java/com/smart/rag/rag/entity/RagChunkEntity.java`（对应 `rag_chunk_entity` 表）
- [ ] 字段与 V21 DDL 严格一致，参考 §3.1
- [ ] 验证：`./mvnw compile`

## Step 5 — EntityCanonicalizationService（§4.4 规范化）

- [ ] 新建 `src/main/java/com/smart/rag/rag/service/impl/EntityCanonicalizationService.java`
  - 注入 `EntityMapper`、`ChunkEntityMapper`
  - `canonicalize(String name)`: `NFC normalize → lowercase → trim`
  - `aggregateAndUpsert(List<ChunkExtraction>, Long userId, Long teamId)`:
    1. 按 name_norm 分组，拼接 description（句号分隔）
    2. 批量 UPSERT rag_entity（ON CONFLICT DO UPDATE SET description = rag_entity.description || ... ）
    3. 批量 INSERT rag_chunk_entity（ON CONFLICT DO NOTHING）
    4. 同步 degree（UPDATE rag_entity SET degree = (SELECT count(*) FROM rag_chunk_entity WHERE entity_id = rag_entity.id) WHERE id IN (:affectedEntityIds)）
- [ ] 参考 `.trellis/spec/backend/database-guidelines.md`
- [ ] 验证：`./mvnw compile`

## Step 6 — EntityEmbeddingService（§4.4 embedding）

- [ ] 新建 `src/main/java/com/smart/rag/rag/service/impl/EntityEmbeddingService.java`
  - 注入 `LlmClientRegistry`（获取默认 `EmbeddingCapable`）、`EntityMapper`
  - `embedEntities(List<RagEntity>)`:
    1. 过滤已有 embedding 的实体（跳过）
    2. 过滤 description 超 500 字符 → 调用 ChatCapable 压缩摘要
    3. 分批 `embedBatch(texts, EmbeddingType.ENTITY)`（每批 ≤ `entity.embeddingBatchSize`，默认 10）
    4. 批量 UPDATE `rag_entity.embedding`
  - **遵循 `llm-spi.md`**：通过 `LlmClientRegistry.getDefault(LlmCapability.EMBEDDING, EmbeddingCapable.class)` 获取 EmbeddingCapable
- [ ] 验证：`./mvnw compile`

## Step 7 — EntityExtractionService（§4.1 编排）

- [ ] 新建 `src/main/java/com/smart/rag/rag/service/impl/EntityExtractionService.java`
  - 注入 `EntityCanonicalizationService`、`EntityEmbeddingService`、`EntityMapper`、`EventMapper`、`ChunkEntityMapper`、`LlmClientRegistry`（或 `RewriteClientResolver`）、`VectorStoreMapper`
  - `@EventListener onEtlCompleted(EtlCompletedEvent event)` → `extractAndIndex(event.documentId(), event.userId(), event.teamId())`
  - `@EventListener onEtlVectorized(EtlVectorizedEvent event)` → `extractAndIndex(event.documentId(), event.userId(), event.teamId())`
  - `extractAndIndex(Long documentId, Long userId, Long teamId)`:
    1. 从 `vector_store` 查文档所有 chunk（`SELECT id, content FROM vector_store WHERE metadata->>'documentId' = :docIdStr`）
    2. 并行对每个 chunk 调用 LLM 抽取（§4.2 prompt → JSON 解析 → event + entities）
    3. 委托 `EntityCanonicalizationService.aggregateAndUpsert(...)`
    4. 写 `rag_event`（per chunk: chunk_id + summary）
    5. 委托 `EntityEmbeddingService.embedEntities(...)`
    6. 标记受影响实体 `community_stale=TRUE`
  - **遵循 `llm-spi.md`**：通过 `LlmClientRegistry` 获取 `ChatCapable`，**禁止注入 `ChatClient.Builder`**
  - 抽取 prompt（§4.2）内联为类常量或 `static/prompt/entity-extraction.xml`
- [ ] 参考 `.trellis/spec/backend/llm-spi.md`（注入契约 §2）和 `.trellis/spec/backend/error-handling.md`（异常处理）
- [ ] 验证：`./mvnw compile`

## Step 8 — EntityIndexCleanupService（§8.4 清理）

- [ ] 新建 `src/main/java/com/smart/rag/rag/service/impl/EntityIndexCleanupService.java`
  - 注入 `ChunkEntityMapper`、`EventMapper`、`EntityMapper`、`VectorStoreMapper`
  - `cleanupByDocumentId(Long documentId)`:
    1. `SELECT id FROM vector_store WHERE metadata->>'documentId' = :docIdStr`（方案 B：先查 chunk_id 列表，避免全表扫描）
    2. `SELECT DISTINCT entity_id FROM rag_chunk_entity WHERE chunk_id IN (:chunkIds)`（捕获受影响 entity_id）
    3. `DELETE FROM rag_chunk_entity WHERE chunk_id IN (:chunkIds)`
    4. `DELETE FROM rag_event WHERE chunk_id IN (:chunkIds)`
    5. `UPDATE rag_entity SET degree = (SELECT count(*) FROM rag_chunk_entity WHERE entity_id = rag_entity.id) WHERE id IN (:affectedEntityIds)`
    6. `DELETE FROM rag_entity WHERE degree = 0`
    7. 标记受影响实体 `community_stale=TRUE`
- [ ] 参考 §8.4 CTE SQL（转换为分步 Java + Mapper 调用）
- [ ] 验证：`./mvnw compile`

## Step 9 — 清理集成接入

### 9a. DocumentSupersedeService supersedeOldVersion

- [ ] 修改 `src/main/java/com/smart/rag/rag/service/impl/DocumentSupersedeService.java`
  - 注入 `EntityIndexCleanupService`
  - 在 `supersedeOldVersion()` `:330` 方法中，步骤 1（`:331-339`，事务更新 SUPERSEDED）完成后、步骤 2（`:341-343`，`deleteByDocumentId`）**之前**插入：
    ```java
    try {
        entityIndexCleanupService.cleanupByDocumentId(oldDocId);
    } catch (Exception e) {
        log.error("Failed to cleanup entity index for superseded docId={}: {}", oldDocId, e.getMessage());
    }
    ```
- [ ] 验证：`./mvnw test -Dtest=DocumentSupersedeServiceTest` 全绿

### 9b. DocumentLifecycleService cascadeDelete

- [ ] 修改 `src/main/java/com/smart/rag/rag/service/impl/DocumentLifecycleService.java`
  - 注入 `EntityIndexCleanupService`
  - 在 `cascadeDelete()` `:59`（`vectorStoreLoader.deleteByDocumentId(id)`）**之前**插入：
    ```java
    try {
        entityIndexCleanupService.cleanupByDocumentId(id);
    } catch (Exception e) {
        log.error("Failed to cleanup entity index for deleted docId={}: {}", id, e.getMessage());
    }
    ```
- [ ] 验证：DocumentLifecycleService 现有测试全绿

## Step 10 — 单元测试

- [ ] `EntityCanonicalizationServiceTest`：canonicalize 输出符合 NFC+lowercase+trim；aggregateAndUpsert 正确分组拼接
- [ ] `EntityEmbeddingServiceTest`：500 字符阈值压缩逻辑；分批调用正确
- [ ] `EntityExtractionServiceTest`：两个事件监听器委托同一方法；chunk 抽取失败不阻塞其他 chunk
- [ ] `EntityIndexCleanupServiceTest`：cleanupByDocumentId 清理 chunk_entity/event/degree 正确；degree=0 孤儿清除
- [ ] 参考测试规范：`.trellis/spec/backend/quality-guidelines.md`

## Step 11 — 集成测试

- [ ] 新建 `src/test/java/com/smart/rag/rag/service/impl/EntityExtractionServiceIntegrationTest.java`
  - 测试场景：ingest 一篇真实文档 → 断言 rag_entity/rag_event/rag_chunk_entity populated
  - 条件：`app.rag.entity.enabled=true` + 数据库 V21 schema + LLM 候选可用
- [ ] 参考现有集成测试模式（`evaluation/runner` 框架可复用）
- [ ] 验证：`./mvnw test -Dtest=EntityExtractionServiceIntegrationTest`

## Validation Commands

```bash
# 编译验证
./mvnw compile

# 单测
./mvnw test -Dtest='EntityCanonicalizationServiceTest,EntityEmbeddingServiceTest,EntityExtractionServiceTest,EntityIndexCleanupServiceTest'

# Supersede/Lifecycle 回归
./mvnw test -Dtest='DocumentSupersedeServiceTest'

# 集成测试（需数据库 + LLM）
./mvnw test -Dtest='EntityExtractionServiceIntegrationTest'
```

## Review Gates

- [ ] **Gate 1**：Step 1-8 完成后 — `./mvnw compile` 通过，无编译错误
- [ ] **Gate 2**：Step 10 完成后 — 所有单测绿色
- [ ] **Gate 3**：Step 9 完成后 — `DocumentSupersedeServiceTest` + `DocumentLifecycleService` 测试绿色（无回归）
- [ ] **Gate 4**：Step 11 完成后 — 集成测试通过（ingest → 三表 populated）

## Rollback Points

- Step 2（FastTrackStrategy 改动）— 移除事件发布行 + ApplicationEventPublisher 注入即可回滚
- Step 9（清理集成）— 移除 EntityIndexCleanupService 调用即可回滚，不影响现有 supersede/delete 流程
- Step 3-8（纯新增类）— 删除新增文件即可回滚
- 整体回滚：`entity.enabled=false` 关闭（或删除 V21 迁移）

## Referenced Specs

- `.trellis/spec/backend/llm-spi.md` — ChatCapable 注入契约、EmbeddingCapable 用法
- `.trellis/spec/backend/error-handling.md` — 异常类型选择（RemoteException for LLM failure）
- `.trellis/spec/backend/logging-guidelines.md` — 日志级别规范
- `.trellis/spec/backend/database-guidelines.md` — SQL/MyBatis 编写规范
- `.trellis/spec/backend/quality-guidelines.md` — 测试规范
- `.trellis/spec/guides/code-reuse-thinking-guide.md` — 复用判断
- `.trellis/spec/guides/cross-layer-thinking-guide.md` — 跨层影响评估
