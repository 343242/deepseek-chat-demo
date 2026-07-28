-- ============================================================
-- V21__entity_centric_index.sql — 实体中心索引层（Entity-Centric Retrieval）
--
-- 来源任务：.trellis/tasks/07-17-ecr-db-migration（父任务 07-17-entity-centric-retrieval）
-- 设计文档：docs/design/entity-centric-retrieval.md §3.1（数据模型）+ §13（迁移脚本字节级参考）
--
-- 落地 4 张表 + 1 个视图 + 全部索引：
--   1. rag_entity            — 实体规范化主表（含结构分缓存列 weak_tie_score/bridge_score/community_*）
--   2. rag_chunk_entity      — chunk ↔ entity 多对多关联（复合 PK）
--   3. rag_event             — SAG event-entity 超边的 event 端（chunk 级事件摘要 + embedding）
--   4. rag_entity_cooccurrence — 实体共现无向边（结构分计算输入）
--   5. v_entity_neighbors    — 共现图双向邻接视图（CooccurrenceGraphLoader / weak_tie SQL 用）
--
-- 关键设计决策（§3.2 / §10.1）：
--   - vector_store 表零改动（Spring AI 管理契约），V21 不依赖 V1-V20 任何既有表
--   - 安全隔离双轨：entity/event 层 user_id BIGINT + chunk 回链层 metadata->>'userId' 字符串
--   - name_norm 规范化名称为业务唯一键；degree 保留但不参与打分（仅 WHERE degree<100 性能预算）
--   - 结构分（weak_tie/bridge/community）为离线缓存列，在线只读，默认值兜底不阻塞查询（§5.3）
--
-- 幂等性：V21 为纯新增迁移，Flyway 版本号保证仅执行一次；视图使用 CREATE OR REPLACE。
-- ============================================================

-- 1. rag_entity
CREATE TABLE rag_entity (
    id              BIGSERIAL PRIMARY KEY,
    name_norm       VARCHAR(500) NOT NULL,
    name_display    VARCHAR(500),
    description     TEXT,
    embedding       vector(1024),
    user_id         BIGINT NOT NULL,
    team_id         BIGINT,
    degree          INTEGER NOT NULL DEFAULT 0,
    -- P0: 弱联系分（离线计算，与 §3.1 对齐）
    weak_tie_score  DOUBLE PRECISION DEFAULT 0.5,
    -- P1: 桥接分（离线计算，与 §3.1 对齐）
    bridge_score    DOUBLE PRECISION DEFAULT 0,
    community_id    INTEGER,
    community_stale BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- 表级 UNIQUE 不接受 COALESCE 表达式，改用表达式唯一索引
CREATE UNIQUE INDEX uk_entity_norm_user_team ON rag_entity (name_norm, user_id, COALESCE(team_id, -1));
CREATE INDEX idx_entity_embedding ON rag_entity
    USING hnsw (embedding vector_cosine_ops) WITH (m = 32, ef_construction = 128);
CREATE INDEX idx_entity_user_team ON rag_entity (user_id, team_id);
CREATE INDEX idx_entity_name_norm ON rag_entity (name_norm);

-- 2. rag_chunk_entity
CREATE TABLE rag_chunk_entity (
    chunk_id    UUID NOT NULL,
    entity_id   BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (chunk_id, entity_id)
);
CREATE INDEX idx_ce_entity ON rag_chunk_entity (entity_id);
CREATE INDEX idx_ce_chunk  ON rag_chunk_entity (chunk_id);

-- 3. rag_event
CREATE TABLE rag_event (
    id          BIGSERIAL PRIMARY KEY,
    chunk_id    UUID NOT NULL UNIQUE,
    summary     TEXT NOT NULL,
    embedding   vector(1024),
    user_id     BIGINT NOT NULL,
    team_id     BIGINT,
    document_id BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_event_embedding ON rag_event
    USING hnsw (embedding vector_cosine_ops) WITH (m = 32, ef_construction = 128);
CREATE INDEX idx_event_user_team ON rag_event (user_id, team_id);

-- 4. rag_entity_cooccurrence
CREATE TABLE rag_entity_cooccurrence (
    id          BIGSERIAL PRIMARY KEY,           -- 代理主键
    entity_a    BIGINT NOT NULL,
    entity_b    BIGINT NOT NULL,
    co_count    INTEGER NOT NULL,
    user_id     BIGINT NOT NULL,
    team_id     BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_cocur_scope_pair ON rag_entity_cooccurrence
    (user_id, COALESCE(team_id, -1), LEAST(entity_a, entity_b), GREATEST(entity_a, entity_b));
CREATE INDEX idx_cocur_pair ON rag_entity_cooccurrence (entity_a, entity_b);
CREATE INDEX idx_cocur_user ON rag_entity_cooccurrence (user_id, team_id);

-- 5. 邻居视图（结构分计算用）
CREATE OR REPLACE VIEW v_entity_neighbors AS
SELECT entity_a AS entity_id, entity_b AS neighbor_id, user_id, team_id
FROM rag_entity_cooccurrence
UNION ALL
SELECT entity_b AS entity_id, entity_a AS neighbor_id, user_id, team_id
FROM rag_entity_cooccurrence;

-- ============================================================
-- 表 / 列注释（V20 trace_event 风格，便于 \d+ 与 DB 工具自省）
-- ============================================================

COMMENT ON TABLE rag_entity IS '实体规范化主表 -- name_norm 为业务唯一键，含离线结构分缓存列（weak_tie/bridge/community）';
COMMENT ON COLUMN rag_entity.name_norm IS '规范化实体名（小写/去噪），同用户/团队下唯一（表达式唯一索引 uk_entity_norm_user_team）';
COMMENT ON COLUMN rag_entity.embedding IS '实体摘要向量 vector(1024)，与 vector_store 同维，HNSW cosine';
COMMENT ON COLUMN rag_entity.user_id IS '所属用户（BIGINT），安全隔离双轨的 entity 层绑定';
COMMENT ON COLUMN rag_entity.team_id IS '所属团队（可空，个人文档为 NULL；唯一索引 COALESCE(team_id,-1) 兜底）';
COMMENT ON COLUMN rag_entity.degree IS '实体度数（派生列，由 extraction-pipeline 增量维护/清理时全量重算；仅 WHERE degree<100 性能预算用，不参与打分）';
COMMENT ON COLUMN rag_entity.weak_tie_score IS 'P0 弱联系分（离线计算缓存，默认 0.5；在线只读，§5.1）';
COMMENT ON COLUMN rag_entity.bridge_score IS 'P1 桥接分（离线计算缓存，默认 0；在线只读，§5.4）';
COMMENT ON COLUMN rag_entity.community_id IS 'Louvain 社区编号（离线计算缓存；§5.2）';
COMMENT ON COLUMN rag_entity.community_stale IS '社区归属是否过期（TRUE=需重算；CommunityDetectionJob 写 FALSE，clearStaleFlag 全量重置）';

COMMENT ON TABLE rag_chunk_entity IS 'chunk ↔ entity 多对多关联表 -- 回链 vector_store.id（chunk_id），应用层保证一致性，无 FK 约束';
COMMENT ON COLUMN rag_chunk_entity.chunk_id IS '关联 vector_store.id（UUID），chunk 回链层安全隔离靠 metadata->>userId 字符串绑定';
COMMENT ON COLUMN rag_chunk_entity.entity_id IS '关联 rag_entity.id';

COMMENT ON TABLE rag_event IS 'SAG event-entity 超边的 event 端 -- chunk 级事件摘要 + embedding，chunk_id 全局唯一';
COMMENT ON COLUMN rag_event.chunk_id IS '事件来源 chunk（UUID，UNIQUE -- 一个 chunk 对应一个 event 摘要）';
COMMENT ON COLUMN rag_event.summary IS 'chunk 事件的自然语言摘要（LLM 抽取，§4）';
COMMENT ON COLUMN rag_event.embedding IS '事件摘要向量 vector(1024)，HNSW cosine';
COMMENT ON COLUMN rag_event.document_id IS '所属文档 ID（BIGINT，关联 rag_document）';

COMMENT ON TABLE rag_entity_cooccurrence IS '实体共现无向边表 -- 结构分计算输入；uk_cocur_scope_pair 用 LEAST/GREATEST 保证 (a,b) 与 (b,a) 同键去重';
COMMENT ON COLUMN rag_entity_cooccurrence.entity_a IS '共现实体 A（无方向，唯一索引按 LEAST/GREATEST 归一）';
COMMENT ON COLUMN rag_entity_cooccurrence.entity_b IS '共现实体 B（无方向）';
COMMENT ON COLUMN rag_entity_cooccurrence.co_count IS '共现次数（同 chunk 内两实体共同出现计数）';
COMMENT ON COLUMN rag_entity_cooccurrence.user_id IS '所属用户（共现图严格按 user/team 隔离，§3.2/§10.1）';

COMMENT ON VIEW v_entity_neighbors IS '共现图双向邻接视图 -- UNION ALL 展开 (a,b)+(b,a)，供 CooccurrenceGraphLoader 与 weak_tie SQL 使用';

-- ============================================================
-- Down SQL（Flyway 不执行，仅供人工回滚验证 -- AC9）
-- 依赖顺序：先删视图，再按被引用顺序删表。
-- 回滚后 flyway_schema_history 仍记录 V21（项目未启用 flyway.undo）；
-- 若需重新应用，先手动 DROP 后 flyway:repair。
-- ============================================================
-- DROP VIEW IF EXISTS v_entity_neighbors;
-- DROP TABLE IF EXISTS rag_entity_cooccurrence;
-- DROP TABLE IF EXISTS rag_event;
-- DROP TABLE IF EXISTS rag_chunk_entity;
-- DROP TABLE IF EXISTS rag_entity;
