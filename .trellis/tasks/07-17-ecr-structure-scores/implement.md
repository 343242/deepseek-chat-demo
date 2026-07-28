# Implementation Plan — 离线结构分计算

本文件为 `ecr-structure-scores` 子任务的有序执行清单。所有路径经代码库核验。

## 前置条件（Gate 检查）

- [ ] `ecr-db-migration` 完成：V21 schema 落地（4 表 + v_entity_neighbors 视图 + 全部索引），clean DB 迁移通过
- [ ] `ecr-graph-algorithm` 完成：`com.smart.rag.infrastructure.algorithm.graph.WeightedGraph` + `AdjacencyListGraph` + `LouvainCommunityDetector` 可编译，Zachary Karate Club 单测通过
- [ ] `ecr-extraction-pipeline` 完成：EntityMapper 定义了 `batchUpdateCommunities(userId, teamId, Long2IntMap)`、`updateBridgeScores(userId, teamId)`、`clearStaleFlag(userId, teamId)` 方法签名

## 执行清单

### Step 1：EntityCooccurrenceMapper 接口 + XML

**文件**：
- `src/main/java/com/smart/rag/rag/mapper/EntityCooccurrenceMapper.java`
- `src/main/resources/mapper/EntityCooccurrenceMapper.xml`

**内容**：
- [ ] `@Mapper` 接口，package `com.smart.rag.rag.mapper`（已纳入 `@MapperScan`）
- [ ] Row record `CooccurrenceRow(Long entityA, Long entityB, int coCount)` 定义在 Mapper 接口内部（与 `VectorStoreMapper.VectorStoreRow` 约定一致）
- [ ] `List<CooccurrenceRow> selectByScope(@Param("userId") Long userId, @Param("teamId") Long teamId)` — XML：`SELECT entity_a, entity_b, co_count FROM rag_entity_cooccurrence WHERE user_id = #{userId} AND (#{teamId} IS NULL AND team_id IS NULL OR team_id = #{teamId})`
- [ ] `void projectCooccurrence(@Param("userId") Long userId, @Param("teamId") Long teamId)` — XML：§5.4 的 INSERT...ON CONFLICT SQL
- [ ] `void updateWeakTieScores(@Param("userId") Long userId, @Param("teamId") Long teamId)` — XML：§5.1 的 CTE SQL（neighbor_sets → neighbor_pairs → embeddedness → UPDATE rag_entity）
- [ ] XML resultMap `cooccurrenceRow` 映射到 `CooccurrenceRow`

**验证**：
```bash
./mvnw compile -pl . -q   # 编译通过
```

**参考规范**：`database-guidelines.md`（MyBatis-Plus XML 约定）、`logging-guidelines.md`（Mapper 层不直接打日志）

### Step 2：EntityCooccurrenceMapper 单测（共现投影）

**文件**：
- `src/test/java/com/smart/rag/rag/mapper/EntityCooccurrenceMapperTest.java`

**内容**：
- [ ] 使用 `@MybatisTest` + 内嵌 PG（项目已有 Flyway + testcontainers 约定）
- [ ] `testProjectCooccurrence_synthetic3Chunk5Entity()`：插入 3 chunk + 5 entity 的 rag_chunk_entity 数据（A∈{1,2}, B∈{2,3}, C∈{1,3}, D∈{1}, E∈{2}），调用 `projectCooccurrence`，断言 rag_entity_cooccurrence 有 5 行，co_count 正确（(A,B)=1, (A,C)=1, (B,C)=1, (A,D)=1, (B,E)=1）
- [ ] `testProjectCooccurrence_idempotent()`：相同数据两次调用后行数不变，co_count 不变（ON CONFLICT 幂等）
- [ ] `testSelectByScope_isolation()`：双用户数据互不干扰

**验证**：
```bash
./mvnw test -Dtest="EntityCooccurrenceMapperTest" -pl .
```

### Step 3：CooccurrenceGraphLoader + 单测

**文件**：
- `src/main/java/com/smart/rag/rag/service/impl/CooccurrenceGraphLoader.java`
- `src/test/java/com/smart/rag/rag/service/impl/CooccurrenceGraphLoaderTest.java`

**内容**：
- [ ] `@Component`，依赖 `EntityCooccurrenceMapper`
- [ ] `WeightedGraph load(Long userId, @Nullable Long teamId)` — 调用 `cooccurrenceMapper.selectByScope()` → 构造 `AdjacencyListGraph`，逐行 `addEdge(entityA, entityB, coCount)`
- [ ] 单测：Mock `EntityCooccurrenceMapper.selectByScope()` 返回 3 行假数据 → 断言 `graph.nodeCount()=3`、`graph.edgeWeight(a,b)==coCount`、`graph.neighbors(a)` 包含 b

**验证**：
```bash
./mvnw test -Dtest="CooccurrenceGraphLoaderTest" -pl .
```

**参考规范**：`quality-guidelines.md`（DIP：依赖 WeightedGraph 接口）、`code-reuse-thinking-guide.md`

### Step 4：EntityIndexService（weak_tie 编排） + 手算值测试

**文件**：
- `src/main/java/com/smart/rag/rag/service/impl/EntityIndexService.java`
- `src/test/java/com/smart/rag/rag/service/impl/EntityIndexServiceTest.java`

**内容**：
- [ ] `@Component`，依赖 `EntityCooccurrenceMapper`
- [ ] `void computeWeakTieScores(Long userId, Long teamId)` — 委托 `cooccurrenceMapper.updateWeakTieScores(userId, teamId)`
- [ ] 手算值测试（集成测试）：
  - 构造小型共现图（三角形 A-B-C + 桥 D-A/E + 孤立 F）
  - A 邻居 = {B, C, D, E}，B 邻居 = {A, C}，C 邻居 = {A, B}
  - 嵌入度计算：(A,B) 的 Jaccard = |{A,C}∩{A,C}|/|{A,C}∪{A,C}| = 2/2 = 1.0
  - (A,C) 同理 = 1.0
  - (A,D)：D 邻居 = {A}，neighbors(A)∩neighbors(D) = {} → common=0, union=|{B,C,D,E}|+|{A}|=5 → Jaccard=0
  - (A,E)：同理 = 0
  - avg_emb(A) = (1.0 + 1.0 + 0 + 0) / 4 = 0.5
  - weak_tie_score(A) = 1 - 0.5 = 0.5
  - B 邻居 = {A, C}，(B,A) 的 Jaccard = 1.0，(B,C) 的 Jaccard = 1.0
  - avg_emb(B) = (1.0 + 1.0) / 2 = 1.0 → weak_tie_score(B) = 0.0
  - F 无邻居 → weak_tie_score(F) = 0.5（默认，COALESCE）
  - 断言数据库中 A=0.5, B=0.0, F=0.5

**验证**：
```bash
./mvnw test -Dtest="EntityIndexServiceTest" -pl .
```

### Step 5：CommunityDetectionJob + 合成图集成测试

**文件**：
- `src/main/java/com/smart/rag/rag/service/impl/CommunityDetectionJob.java`
- `src/test/java/com/smart/rag/rag/service/impl/CommunityDetectionJobTest.java`

**内容**：
- [ ] `@Component`，依赖 `CooccurrenceGraphLoader` + `EntityMapper`（extraction-pipeline 提供）
- [ ] `void run(Long userId, @Nullable Long teamId)`：
  ```
  WeightedGraph graph = graphLoader.load(userId, teamId);
  if (graph.nodeCount() < 2) return;
  Long2IntMap communities = new LouvainCommunityDetector(graph).detect();
  entityMapper.batchUpdateCommunities(userId, teamId, communities);
  entityMapper.updateBridgeScores(userId, teamId);
  entityMapper.clearStaleFlag(userId, teamId);
  ```
- [ ] 日志：INFO 级别记录开始/完成/节点数/社区数（`logging-guidelines.md`）
- [ ] 集成测试（需真实 DB + Louvain）：
  - 构造合成共现图（两个三角形 + 一条桥边：A-B-C 三角形 + D-E-F 三角形 + C-D 桥）
  - 调用 `communityDetectionJob.run(userId, teamId)`
  - 断言：(a) 所有节点 community_id 非 NULL；(b) A/B/C 同社区，D/E/F 同社区（或 Louvain 合理划分），C/D 桥接；(c) C 和 D 的 bridge_score ≥ 1（连接不同社区）；(d) A/B/E/F 的 bridge_score 可能 = 0（纯社区内部）；(e) `community_stale` 全部 = FALSE；(f) `degree` 列不变
- [ ] 单实体跳过测试：`nodeCount < 2` 时不调用 Louvain，无异常

**验证**：
```bash
./mvnw test -Dtest="CommunityDetectionJobTest" -pl .
```

### Step 6：bridge_score SQL 正确性验证

**文件**：复用 `CommunityDetectionJobTest`

**内容**：
- [ ] 在 Step 5 合成图测试中已覆盖——bridge_score = 邻居中不同社区数（排除自身）
- [ ] 额外边界用例：全部同社区时 bridge_score = 0

### Step 7：幂等性 + 重复运行测试

**文件**：复用 `CommunityDetectionJobTest`

**内容**：
- [ ] `testRun_idempotent()`：对同一 scope 连续调用 `run()` 两次，断言结构分不变、无重复行、stale 全 FALSE

## 交叉子任务协调

| 协调项 | 对方子任务 | 协调内容 |
|---|---|---|
| EntityMapper 方法签名 | `ecr-extraction-pipeline` | `batchUpdateCommunities(Long userId, Long teamId, Long2IntMap communities)`、`updateBridgeScores(Long userId, Long teamId)`、`clearStaleFlag(Long userId, Long teamId)` — extraction-pipeline 负责实现（含 bridge_score XML SQL），本子任务仅调用 |
| WeightedGraph / Louvain API | `ecr-graph-algorithm` | import `com.smart.rag.infrastructure.algorithm.graph.WeightedGraph` + `AdjacencyListGraph` + `LouvainCommunityDetector` — 验证接口签名与 §5.2 一致（`Long2IntMap detect()` 返回 node → community_id） |
| 结构分列所有权 | `ecr-path-c-retrieval` | path-c 仅读取 `weak_tie_score`/`bridge_score`/`community_id`，不写不改 |

## 验证命令汇总

```bash
# 编译
./mvnw compile -pl . -q

# 单测（按 step）
./mvnw test -Dtest="EntityCooccurrenceMapperTest" -pl .
./mvnw test -Dtest="CooccurrenceGraphLoaderTest" -pl .
./mvnw test -Dtest="EntityIndexServiceTest" -pl .
./mvnw test -Dtest="CommunityDetectionJobTest" -pl .

# 全量
./mvnw test -pl .
```

## Review Gates

- [ ] **Gate A**（Step 2 后）：共现投影正确 + 幂等 + 用户隔离
- [ ] **Gate B**（Step 4 后）：weak_tie 手算值匹配（核心验收 AC2）
- [ ] **Gate C**（Step 5 后）：CommunityDetectionJob 端到端——社区分配 + bridge + stale 清除 + degree 不变（核心验收 AC4）

## Rollback Points

- Step 1-2：纯新增 Mapper 文件 + XML，revert 即可
- Step 3-4：纯新增 Service 文件，revert 即可
- Step 5-6：纯新增 Job 文件，revert 即可
- 无对现有代码的修改（`@MapperScan` 已覆盖 `com.smart.rag.rag.mapper`，无需改动）
