# 离线结构分计算：共现图 + weak_tie_score + bridge_score + 社区检测编排

## Goal

实现实体中心索引的离线结构分计算管线：从 `rag_chunk_entity` 投影共现图到 `rag_entity_cooccurrence`（§5.4），计算 P0 `weak_tie_score`（邻域 Jaccard 不重叠度，§5.1）与 P1 `bridge_score`（社区桥接分，§5.2），以及 Leiden 社区检测编排（§5.2 ④⑤）。所有结构分为缓存属性，在线只读不计算（§5.3）。

## Authoritative Design

技术设计的唯一来源是 `docs/design/entity-centric-retrieval.md`（1605 行）。本文件及 `design.md`/`implement.md` 均为指向该主文档的薄指针。所有 §x.y 引用均指该主文档。

## Confirmed Facts（代码库核验）

- **Mapper 约定**：`com.smart.rag.rag.mapper` 包已纳入 `@MapperScan`（`SmartRagApplication:24-32`）。复杂 SQL 统一使用 **XML mapper**（`VectorStoreMapper` 全部 SQL 在 `src/main/resources/mapper/VectorStoreMapper.xml`，接口无 `@Select/@Update` 注解）。`EntityCooccurrenceMapper` 的 weak_tie UPDATE...FROM CTE（§5.1，~50 行）与共现投影 INSERT...ON CONFLICT（§5.4）**必须走 XML**。
- **包路径**：业务编排类放在 `com.smart.rag.rag.service.impl`（已有 `EtlPipelineServiceImpl`、`DocumentSupersedeService` 等先例）。图算法接口来自 `com.smart.rag.infrastructure.algorithm.graph`（sibling `ecr-graph-algorithm` 产出，当前目录不存在）。
- **触发模式**：项目中存在两种异步模式：(a) `@Scheduled` cron/fixedDelay 定时任务（`OrphanChunkCleaner`、`ApprovalTimeoutJob`、`McpConnectionRecoveryScheduler` 等）；(b) `@EventListener + @Async("etlIoExecutor")` 事件驱动（`DocumentSupersedeService`、`EtlDocumentConsumer`）。CommunityDetectionJob 按 §8.1 Step 6 由 `EntityExtractionService.extractAndIndex()` 调用（事件驱动），但 §5.3 增量策略也需支持定时全量刷新。本子任务负责 CommunityDetectionJob 自身的 `run(userId, teamId)` 方法，触发机制由 extraction-pipeline 调用决定。
- **v_entity_neighbors 视图**：由 `ecr-db-migration` 的 V21 DDL 定义（§13:1569-1574），作为普通数据库视图在 SQL 中直接引用（`FROM v_entity_neighbors`），无需特殊前缀。
- **EntityMapper 归属**：`EntityMapper` 属 `ecr-extraction-pipeline`（父 design.md contract #1），不在本子任务范围。`CommunityDetectionJob` 调用 `entityMapper.batchUpdateCommunities()`、`entityMapper.updateBridgeScores()`、`entityMapper.clearStaleFlag()` —— 这些方法由 extraction-pipeline 定义并实现，本子任务仅调用。
- **degree 列只读**：`rag_entity.degree` 是 `ecr-extraction-pipeline` 的派生列，本子任务仅读取 `WHERE degree < 100`（§5.1 性能预算），**不得写**（父 design.md contract #2）。
- **事务模式**：项目禁止 `@Transactional`，使用 `TransactionTemplate` 编程式事务（`database-guidelines.md:79-91`）。

## Requirements

### R1：共现图投影（§5.4）

- `EntityCooccurrenceMapper.projectCooccurrence(userId, teamId)` 执行 `INSERT INTO rag_entity_cooccurrence ... ON CONFLICT DO UPDATE SET co_count = EXCLUDED.co_count`。
- 从 `rag_chunk_entity` 自连接投影，`LEAST/GREATEST(entity_id)` 规范边方向，按 `user_id + COALESCE(team_id, -1)` 严格隔离。
- 投影范围：全量（按 userId/teamId），非增量——每次 ETL 完成后对该 scope 全量重投影（§5.3 增量表）。

### R2：weak_tie_score 批量计算（§5.1）

- `EntityCooccurrenceMapper.updateWeakTieScores(userId, teamId)` 执行 §5.1 的 CTE SQL（neighbor_sets → neighbor_pairs → embeddedness → UPDATE rag_entity）。
- `WHERE re.degree < 100` 性能预算：degree ≥ 100 的 hub 实体 weak_tie 维持默认 0.5，不参与计算。
- `weak_tie_score = COALESCE(1.0 - avg_emb, 0.5)` — 无邻居或无邻居对的实体保持默认。

### R3：bridge_score 纯 SQL 计算（§5.2 Step 2）

- `EntityMapper.updateBridgeScores(userId, teamId)` 执行 §5.2 bridge SQL（通过 v_entity_neighbors JOIN 获取邻居的不同 community_id 数量，排除自身社区）。
- 此方法由 `ecr-extraction-pipeline` 的 EntityMapper 提供，`CommunityDetectionJob` 调用。

### R4：Leiden 社区检测编排（§5.2 ⑤）

- `CommunityDetectionJob.run(userId, teamId)` 编排：load → detect → write communities → update bridge → clearStaleFlag。
- `CooccurrenceGraphLoader.load(userId, teamId)` 从 `rag_entity_cooccurrence` 读取全部边，构造 `WeightedGraph`（`AdjacencyListGraph`）。
- `nodeCount < 2` 时跳过（单实体无需社区检测）。
- `LeidenCommunityDetector` 由 sibling `ecr-graph-algorithm` 提供（import `com.smart.rag.infrastructure.algorithm.graph.LeidenCommunityDetector` + `WeightedGraph` + `AdjacencyListGraph`）。
- `entityMapper.batchUpdateCommunities(userId, teamId, communities)` 将 node → community_id 写回。
- `entityMapper.clearStaleFlag(userId, teamId)` **全量**清除该 scope 下所有实体的 `community_stale`（§5.2⑤ 注释 + §9.2 `community_stale_entity_ratio` 指标——Leiden 全量刷新后应→0）。不是增量部分清除。

### R5：安全隔离

- 所有 SQL 严格按 `user_id + COALESCE(team_id, -1)` 隔离（§3.2）。共现图投影、weak_tie 计算、社区检测加载均限定 scope。
- 跨用户隔离：user A 的共现图/结构分不包含 user B 的任何数据。

### R6：degree 只读契约

- 本子任务的 SQL **仅读取** `rag_entity.degree`（`WHERE degree < 100`），**绝不写入** degree 列。写入由 `ecr-extraction-pipeline` 的 `EntityCanonicalizationService` 在写入 `rag_chunk_entity` 时增量维护、在 `EntityIndexCleanupService` 清理时全量重算（§8.4）。

## Acceptance Criteria

- [ ] AC1（共现投影正确性）：给定 3 个 chunk、5 个实体（A 出现在 chunk1+2，B 出现在 chunk2+3，C 出现在 chunk1+3，D 仅 chunk1，E 仅 chunk2），投影后 `rag_entity_cooccurrence` 包含边 (A,B)=1, (A,C)=1, (B,C)=1, (A,D)=1, (B,E)=1 共 5 条，co_count 正确，且 ON CONFLICT 重跑幂等。
- [ ] AC2（weak_tie 手算验证）：构造小型合成图（3 个实体三角形 + 1 个桥接实体 + 1 个孤立实体），手动计算 Jaccard embeddedness 后验证 `weak_tie_score` 与手算值误差 < 1e-6。degree ≥ 100 的 hub weak_tie = 0.5（默认值不变）。
- [ ] AC3（bridge_score 正确性）：Leiden 社区检测后，桥接实体的 bridge_score = 邻居中属于不同社区的数量（排除自身社区）。纯社区内部实体的 bridge_score = 0。
- [ ] AC4（CommunityDetectionJob 端到端）：在 Zachary Karate Club 标准图或合成图上，CommunityDetectionJob 完成后：(a) 所有节点的 community_id 非 NULL；(b) bridge_score 与社区分配一致；(c) `community_stale` 对该 scope **全部**为 FALSE；(d) degree 不受影响。
- [ ] AC5（用户隔离）：双用户各 ingest 文档后，各自 CommunityDetectionJob.run() 不影响对方共现图/结构分。
- [ ] AC6（增量安全）：重复 run() 幂等——相同 scope 两次执行后结构分不变，无重复行，无 stale 泄漏。

## Dependencies（来自父 prd.md 依赖图）

- **ecr-db-migration**（Wave 0）：V21 schema 必须先落地——`rag_entity`（含 weak_tie_score/bridge_score/community_id/community_stale 列）、`rag_chunk_entity`、`rag_entity_cooccurrence`、`v_entity_neighbors` 视图。
- **ecr-graph-algorithm**（Wave 0）：`WeightedGraph` 接口 + `AdjacencyListGraph` + `LeidenCommunityDetector`（import `com.smart.rag.infrastructure.algorithm.graph.*`）。
- **ecr-extraction-pipeline**（Wave 1）：需实体数据已落库（`rag_entity` + `rag_chunk_entity` populated）；`EntityMapper.batchUpdateCommunities/updateBridgeScores/clearStaleFlag` 方法已定义——**本子任务调用这些方法，extraction-pipeline 负责实现**。触发 CommunityDetectionJob 的调用由 extraction-pipeline 的 `EntityExtractionService.extractAndIndex()` Step 6 负责（§8.1）。

## Out of Scope

- 图算法内部实现（`WeightedGraph`/`AdjacencyListGraph`/`LeidenCommunityDetector` 的代码——属 `ecr-graph-algorithm`）
- 实体抽取/规范化/embedding（属 `ecr-extraction-pipeline`）
- 在线检索 Path C（属 `ecr-path-c-retrieval`，仅读取本子任务写入的列）
- EntityMapper 的实现（方法签名属 extraction-pipeline 所有，本子任务仅调用）
- `@Scheduled` 定时全量刷新的调度配置（本子任务提供 `run(userId, teamId)` 方法，调度器由 extraction-pipeline 或独立配置注册）

## Open Questions

- **OQ1（触发机制）**：CommunityDetectionJob 是仅由 extraction-pipeline 在 ETL 完成后事件驱动调用（per-scope），还是同时需要 `@Scheduled` 定时全量刷新（§5.3 提到"每日/每周一次全量"）？如果是后者，需要一个 scheduler 组件遍历活跃 userId/teamId 调用 `run()`——这个 scheduler 属于本子任务还是 extraction-pipeline？当前倾向：本子任务仅提供 `run(userId, teamId)` 方法，调度由 extraction-pipeline 或独立配置负责。
- **OQ2（Leiden 种子稳定性）**：§11.3 提到增量写入下社区划分可能抖动。当前 `clearStaleFlag` 是全量清除（Leiden 覆盖所有节点）。是否需要"仅当社区分配变化时才更新 bridge_score"的增量优化？Phase 1 暂不做——全量重写即可。
