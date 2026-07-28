# Design — ECR DB Migration (V21 Schema)

## Authoritative Source

完整技术设计见 **`docs/design/entity-centric-retrieval.md`**。本文件仅记录 `ecr-db-migration` 子任务的设计补充与边界说明；所有正文细节以主文档为准。

## §References

| 子任务关注点 | 主文档章节 | 关键内容 |
|---|---|---|
| 4 表 DDL + 全部索引 | §3.1（行 106-178） | 完整 `CREATE TABLE` + `CREATE INDEX` SQL |
| 设计决策表 | §3.2（行 180-190） | 不改 vector_store、name_norm 规范化、隔离字段、degree 保留但不进打分 |
| 完整迁移脚本 | §13（行 1497-1575） | V21 `V21__entity_centric_index.sql` 字节级参考 |
| 清理 SQL 参考 | §8.4（行 1213-1249） | 理解 DELETE/UPDATE 路径的表依赖关系 |
| 双重隔离机制 | §10.1（行 1322-1335） | entity 层 BIGINT vs chunk 层字符串两种 userId 绑定 |

## Child-Specific Design Notes

### 1. 表达式唯一索引（Expression Unique Index）

**问题**：PostgreSQL 表级 `UNIQUE (col1, COALESCE(col2, -1))` 语法不被接受——`UNIQUE CONSTRAINT` 定义只能引用列名，不能包含表达式。

**方案**：改用 `CREATE UNIQUE INDEX ... ON table (expression_list)`。这在本迁移中出现两处：

| 索引 | 表达式 | 目的 |
|---|---|---|
| `uk_entity_norm_user_team` | `(name_norm, user_id, COALESCE(team_id, -1))` | 同用户/团队下 `name_norm` 唯一；`team_id=NULL` 时映射为 `-1` |
| `uk_cocur_scope_pair` | `(user_id, COALESCE(team_id, -1), LEAST(entity_a, entity_b), GREATEST(entity_a, entity_b))` | 无向边去重 + scope 隔离；`(a,b)` 和 `(b,a)` 映射到同一键 |

**COALESCE(team_id, -1) 理由**：`team_id` 可 NULL（个人文档）。SQL 中 `NULL != NULL`，若不加 COALESCE，两条 `(name='X', user=1, team=NULL)` 行不会触发唯一冲突。用 `-1` 替代 NULL 是 PostgreSQL 惯用模式（见 §3.1 行 127 注释）。

**LEAST/GREATEST 理由**：共现图是无向图，`(entity_a=1, entity_b=2)` 与 `(entity_a=2, entity_b=1)` 是同一条边。`LEAST/GREATEST` 强制小值在前，使两条 INSERT 映射到同一唯一键。

### 2. HNSW 索引参数

V21 中两个 HNSW 索引（`rag_entity.embedding` 和 `rag_event.embedding`）严格复用 V13 建立的项目标准：

- `vector_cosine_ops`：余弦距离（项目唯一使用的距离度量）
- `m = 32`：图连通性（V13 从默认 16 翻倍，提升召回）
- `ef_construction = 128`：构建质量（V13 从默认 64 翻倍）

查询时参数 `hnsw.ef_search = 64` 由 V13 `ALTER DATABASE` 设置，全局生效，V21 无需额外设置。

### 3. v_entity_neighbors 视图设计

```sql
CREATE OR REPLACE VIEW v_entity_neighbors AS
SELECT entity_a AS entity_id, entity_b AS neighbor_id, user_id, team_id
FROM rag_entity_cooccurrence
UNION ALL
SELECT entity_b AS entity_id, entity_a AS neighbor_id, user_id, team_id
FROM rag_entity_cooccurrence;
```

使用 `UNION ALL`（非 `UNION`）因为 `rag_entity_cooccurrence` 本身不会存储 `(a,a)` 自环，不存在重复行需要去重。视图为 `CooccurrenceGraphLoader`（`ecr-structure-scores`）提供双向邻接查询，避免每次 JOIN 时写双向逻辑。

### 4. 隔离列设计

每张表都包含 `user_id BIGINT NOT NULL` + `team_id BIGINT`（或 UNIQUE 约​​束隐含的隔离语义）。这实现了 §10.1 的安全隔离双轨：

- **entity/event 层**：`WHERE e.user_id = :userId`（BIGINT 绑定）
- **chunk 回链层**：`WHERE vs.metadata->>'userId' = :userIdStr`（字符串绑定，由 `ecr-extraction-pipeline` 实现）

V21 只负责建表建列；隔离查询逻辑由 Java 层实现。

### 5. V21 为纯新增迁移

- 不修改 V1-V19 任何对象（不 ALTER、不 DROP）
- 不依赖 V1-V19 的任何表结构（V21 四表独立，通过 `rag_chunk_entity.chunk_id` 外键语义关联 `vector_store.id`，但 SQL 层不建 `REFERENCES` 约束——主文档未定义 FK，由应用层保证一致性）
- `vector_store` 表零改动（Spring AI 管理契约）

## Design Principle Mapping

| 原则 | 本子任务落实 |
|---|---|
| SRP | V21 仅包含 DDL，不混入 Java 代码 |
| OCP | 不修改既有迁移/表，纯新增 |
| DIP | N/A（无代码依赖） |
| CARP | N/A（纯 DDL） |
