# Design — 离线结构分计算

## Authoritative Source

完整技术设计见 **`docs/design/entity-centric-retrieval.md`**。本文件仅记录子任务级设计补充。

## 主文档引用

| 章节 | 内容 | 本子任务相关性 |
|---|---|---|
| §3.1 | 数据模型（4 表 + 1 视图） | 读取/写入 `rag_entity`（weak_tie/bridge/community 列）、`rag_entity_cooccurrence`、`v_entity_neighbors` |
| §5.1 | weak_tie_score 计算（Jaccard + degree<100 cap） | **核心**：EntityCooccurrenceMapper SQL |
| §5.2 ①②③ | WeightedGraph / AdjacencyListGraph / LouvainCommunityDetector | **依赖**（ecr-graph-algorithm 提供） |
| §5.2 ④ | CooccurrenceGraphLoader | **拥有**：DB → WeightedGraph 加载 |
| §5.2 ⑤ | CommunityDetectionJob | **拥有**：编排 load→detect→write→bridge→clearStale |
| §5.3 | 增量维护策略 | 架构决策：全量重投影 vs 增量 |
| §5.4 | 共现图投影 SQL | **拥有**：EntityCooccurrenceMapper INSERT...ON CONFLICT |
| §8.1 | ETL 集成点 | CommunityDetectionJob 由 extraction-pipeline Step 6 调用 |
| §9.2 | community_stale_entity_ratio 指标 | clearStaleFlag 全量清除的监控依据 |
| §11.3 | Louvain 稳定性 | 种子抖动风险 + 缓解策略 |
| §13 | V21 DDL | v_entity_neighbors 视图定义 |

## 子任务特有设计

### 1. 三层 SRP/CARP/DIP 职责映射

本子任务拥有 §5.2 的 ④⑤ 两个业务类，加上 §5.1/§5.4 的两个 Mapper 方法。职责映射：

| 类/接口 | SRP 职责 | 依赖方向 | 设计原则 |
|---|---|---|---|
| `EntityCooccurrenceMapper` | 共现图 CRUD（投影 INSERT、weak_tie UPDATE CTE、边查询） | 依赖 MyBatis，被 GraphLoader/EntityIndexService 调用 | SRP：仅数据访问 |
| `CooccurrenceGraphLoader` | DB → `WeightedGraph` 转换（SRP：仅加载） | 依赖 EntityCooccurrenceMapper + AdjacencyListGraph（DIP 通过接口） | DIP：依赖 WeightedGraph 接口，不依赖具体算法 |
| `CommunityDetectionJob` | 编排 load→detect→write→bridge→clearStale（SRP：仅编排） | 依赖 CooccurrenceGraphLoader（合成）+ LouvainCommunityDetector（直接构造）+ EntityMapper（跨子调用） | DIP：Detector 无状态纯算法，构造即用 |
| `EntityIndexService`（§5.1 SQL 容器） | weak_tie_score 计算编排（调用 Mapper SQL） | 依赖 EntityCooccurrenceMapper | SRP：仅编排弱联系计算 |

**注意**：设计文档 §10.2 将 EntityIndexService 定义为"weak_tie_score 计算（纯 SQL 驱动）"。本子任务将其实现为一个薄 Service，封装 weak_tie SQL 调用。bridge_score SQL 由 CommunityDetectionJob 间接调用（通过 EntityMapper）。

### 2. degree 列只读契约（父 design.md contract #2）

本子任务的 **所有 SQL** 仅 SELECT `rag_entity.degree`（§5.1 `WHERE re.degree < 100`），绝不 INSERT/UPDATE 该列。

| SQL | degree 操作 |
|---|---|
| 共现投影（§5.4） | 不涉及 degree |
| weak_tie UPDATE（§5.1） | `WHERE re.degree < 100` — 读取 |
| bridge UPDATE（§5.2） | 不涉及 degree |
| clearStaleFlag | 不涉及 degree |

### 3. clearStaleFlag 必须全量（非增量）

§5.2⑤ 代码注释 `entityMapper.clearStaleFlag(userId, teamId)` + §9.2 指标 `community_stale_entity_ratio`（Louvain 全量刷新后应→0）明确要求：Louvain 运行后对该 scope **全部**实体清除 stale flag。

```
-- 全量清除（非按 community_id 增量）
UPDATE rag_entity SET community_stale = FALSE
WHERE user_id = :userId AND (team_id = :teamId OR (:teamId IS NULL AND team_id IS NULL));
```

**不允许** `WHERE community_id IN (检测覆盖的节点集合)`——因为 Louvain 会覆盖所有节点，未被覆盖的节点（如 degree=0 的新实体）仍应标记为非 stale（它们在下一轮检测中不需要重算）。

### 4. 共现投影：全量重投影策略（§5.3 增量表对应）

| 场景 | 投影策略 | 理由 |
|---|---|---|
| 新 chunk + 新实体 | 全量重投影（按 userId/teamId） | 共现图是无向加权简单图，单次 INSERT...ON CONFLICT 幂等——新增边 INSERT，已有边 ON CONFLICT 更新 co_count。全量重投影比增量跟踪更简单、更安全。 |
| 文档删除 | 由 `EntityIndexCleanupService`（extraction-pipeline）处理清理 + 标记 stale，本子任务下次 run() 时全量重投影自然去除失效边 | 无需增量删除逻辑 |

### 5. Mapper SQL 组织

所有 SQL 走 XML mapper（与项目 `VectorStoreMapper` 约定一致）：

- `src/main/resources/mapper/EntityCooccurrenceMapper.xml` — 共现投影 INSERT、weak_tie UPDATE CTE、selectByScope 查询
- `EntityCooccurrenceMapper.java`（接口）— 方法签名 + row record 定义

bridge_score UPDATE SQL 定义在 `EntityMapper.xml`（extraction-pipeline 所有），CommunityDetectionJob 通过 EntityMapper 接口调用。

### 6. 设计原则映射

| 原则 | 落实点 |
|---|---|
| SRP | 4 个类各司一职——Mapper（数据访问）、GraphLoader（加载）、CommunityDetectionJob（编排）、EntityIndexService（weak_tie 编排） |
| DIP | CommunityDetectionJob 通过 CooccurrenceGraphLoader 合成获取 WeightedGraph；LouvainCommunityDetector 直接构造（无状态纯算法） |
| CARP | Louvain 算法在 `infrastructure/algorithm/graph/`，本子任务仅使用接口 |
| OCP | 新增图类型只需新增 WeightedGraph 实现；新增结构分算法只需新增 Service 类 |
