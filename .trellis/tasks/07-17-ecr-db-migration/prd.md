# Flyway V21 Entity-Centric Schema（4 表 + 1 视图 + 全部索引）

## Goal

创建 Flyway 迁移脚本 `V21__entity_centric_index.sql`，落地实体中心索引层的完整数据模型：4 张表（`rag_entity`、`rag_chunk_entity`、`rag_event`、`rag_entity_cooccurrence`）+ 1 个视图（`v_entity_neighbors`）+ 全部索引（含表达式唯一索引、HNSW 向量索引、隔离/查找索引）。

## Confirmed Facts（代码库核验）

- **pgvector 扩展**：在 `V2__vector_store_bm25.sql:13` 中通过 `CREATE EXTENSION IF NOT EXISTS vector` 启用。V21 无需重复创建。
- **向量维度**：`V2:21` 确认 `vector_store.embedding VECTOR(1024)`。V21 中 `rag_entity.embedding` 和 `rag_event.embedding` 均为 `vector(1024)`，与主表一致。
- **HNSW 参数约定**：`V13__hnsw_tuning.sql:30-32` 确认项目标准为 `m = 32, ef_construction = 128`，使用 `vector_cosine_ops` 距离度量。V21 的两个 HNSW 索引严格复用此参数。
- **迁移版本**：当前最高为 `V20`（`V20__trace_event.sql`，链路追踪事件表），V21 为正确序号（原计划 V20，因 trace_event 先行占用而顺延）。
- **命名约定**（`database-guidelines.md`）：唯一索引前缀 `uk_`，普通索引前缀 `idx_`；时间列使用 `TIMESTAMPTZ`；Flyway 脚本需幂等（V21 为纯新增，无重复执行风险，但仍遵循项目惯例）。
- **注释风格**：项目迁移脚本以 `-- ============` 头部块 + 分节注释 + 表内行注释为惯例（参见 V13、V19）。

## Requirements

### R1：`rag_entity` 表 + 索引（§3.1, §13）

- `CREATE TABLE rag_entity` 含 14 列：`id BIGSERIAL PK`, `name_norm VARCHAR(500) NOT NULL`, `name_display VARCHAR(500)`, `description TEXT`, `embedding vector(1024)`, `user_id BIGINT NOT NULL`, `team_id BIGINT`, `degree INTEGER NOT NULL DEFAULT 0`, `weak_tie_score DOUBLE PRECISION DEFAULT 0.5`, `bridge_score DOUBLE PRECISION DEFAULT 0`, `community_id INTEGER`, `community_stale BOOLEAN NOT NULL DEFAULT TRUE`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`。
- `uk_entity_norm_user_team` 表达式唯一索引：`(name_norm, user_id, COALESCE(team_id, -1))`。理由：PostgreSQL 表级 `UNIQUE CONSTRAINT` 不接受表达式（如 `COALESCE`），必须用表达式唯一索引实现"同用户/团队下 `name_norm` 唯一"的语义（§3.1 注释）。
- `idx_entity_embedding`：HNSW 索引，`USING hnsw (embedding vector_cosine_ops) WITH (m = 32, ef_construction = 128)`。
- `idx_entity_user_team`：隔离查找索引 `(user_id, team_id)`。
- `idx_entity_name_norm`：规范化名称查找索引 `(name_norm)`。

### R2：`rag_chunk_entity` 关联表 + 索引（§3.1, §13）

- `CREATE TABLE rag_chunk_entity` 含 3 列：`chunk_id UUID NOT NULL`, `entity_id BIGINT NOT NULL`, `created_at TIMESTAMPTZ`。复合 PK `(chunk_id, entity_id)`。
- `idx_ce_entity`：按 entity 反查索引 `(entity_id)`。
- `idx_ce_chunk`：按 chunk 反查索引 `(chunk_id)`。

### R3：`rag_event` 表 + 索引（§3.1, §13）

- `CREATE TABLE rag_event` 含 7 列：`id BIGSERIAL PK`, `chunk_id UUID NOT NULL UNIQUE`, `summary TEXT NOT NULL`, `embedding vector(1024)`, `user_id BIGINT NOT NULL`, `team_id BIGINT`, `document_id BIGINT NOT NULL`, `created_at TIMESTAMPTZ`。
- `idx_event_embedding`：HNSW 索引，参数同 R1。
- `idx_event_user_team`：隔离查找索引 `(user_id, team_id)`。

### R4：`rag_entity_cooccurrence` 共现表 + 索引（§3.1, §13）

- `CREATE TABLE rag_entity_cooccurrence` 含 6 列：`id BIGSERIAL PK`（代理主键，因表级 PK 不接受 `LEAST/GREATEST` 表达式）, `entity_a BIGINT NOT NULL`, `entity_b BIGINT NOT NULL`, `co_count INTEGER NOT NULL`, `user_id BIGINT NOT NULL`, `team_id BIGINT`, `created_at TIMESTAMPTZ`。
- `uk_cocur_scope_pair` 表达式唯一索引：`(user_id, COALESCE(team_id, -1), LEAST(entity_a, entity_b), GREATEST(entity_a, entity_b))`。`LEAST/GREATEST` 保证 `(a,b)` 和 `(b,a)` 映射到同一约束键，实现无向边去重。
- `idx_cocur_pair`：`(entity_a, entity_b)`。
- `idx_cocur_user`：`(user_id, team_id)`。

### R5：`v_entity_neighbors` 视图（§13）

- `CREATE OR REPLACE VIEW v_entity_neighbors AS ... UNION ALL ...`：从 `rag_entity_cooccurrence` 生成双向邻接关系（`entity_a→entity_b` + `entity_b→entity_a`），供 `CooccurrenceGraphLoader`（`ecr-structure-scores`）和 `weak_tie_score` SQL（§5.1）使用。

### R6：Down SQL（验证用）

- 提供完整的回滚 SQL（`DROP TABLE` + `DROP VIEW`），以注释形式附在迁移文件末尾或单独提供验证脚本。Flyway 不执行 down SQL，但用于人工验证回滚完整性。

## Acceptance Criteria

- [ ] AC1：`flyway:migrate` 在干净 DB 上成功应用 V21，无错误。
- [ ] AC2：迁移后 `\d rag_entity` 显示 14 列 + 4 个索引（含 `uk_entity_norm_user_team`）。
- [ ] AC3：`\d rag_chunk_entity` 显示复合 PK + 2 个索引。
- [ ] AC4：`\d rag_event` 显示 7 列 + `chunk_id UNIQUE` 约束 + 2 个索引。
- [ ] AC5：`\d rag_entity_cooccurrence` 显示 6 列 + `uk_cocur_scope_pair` 表达式唯一索引 + 2 个普通索引。
- [ ] AC6：`\d+ v_entity_neighbors` 视图存在，`SELECT count(*) FROM v_entity_neighbors` 在空表返回 0。
- [ ] AC7：表达式唯一索引生效——尝试 INSERT 两条 `(name_norm='test', user_id=1, team_id=NULL)` 的 `rag_entity` 行，第二条应报 unique violation。
- [ ] AC8：共现表无向去重生效——INSERT `(entity_a=1, entity_b=2, ...)` 后，INSERT `(entity_a=2, entity_b=1, ...)` 应报 unique violation。
- [ ] AC9：Down SQL 可手动执行（`DROP VIEW + DROP TABLE` 四表），回滚后 Flyway schema_history 仍记录 V21（不可逆，符合项目 Flyway 惯例）。
- [ ] AC10：HNSW 索引参数正确——`\di+ idx_entity_embedding` 显示 `m=32, ef_construction=128`。

## Dependencies（来自父 PRD）

**无依赖**（Wave 0）。本子任务与其他两个 Wave 0 子任务（`ecr-graph-algorithm`、`ecr-retrieval-path-abstraction`）互不依赖，可并行启动。

以下子任务**依赖**本子任务：
- `ecr-extraction-pipeline`（表必须存在才能写入）
- `ecr-structure-scores`（需读取共现表结构）
- `ecr-path-c-retrieval`（需读取四表）

## Out of Scope

- 任何 Java 代码（Mapper、Entity、Service）
- 任何测试代码
- `vector_store` 表结构变更（Spring AI 管理契约，零侵入）
- pgvector 扩展创建（V2 已完成）
- 数据填充或种子数据
- Flyway 配置变更

## Open Questions

无。本子任务为纯 DDL 迁移，所有细节在设计文档 §3 + §13 中已明确。
