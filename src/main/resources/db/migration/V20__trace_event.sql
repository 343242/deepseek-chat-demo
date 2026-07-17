-- 链路追踪事件表
-- 记录请求各步骤的明细（查询改写/向量检索/BM25检索/RRF融合/重排/上下文组装等），
-- 供事后排障与质量分析。横切关注点，可服务于 RAG / Agent / Chat 任意链路。
--
-- 设计要点：
--   1. 永久保留（不自动清理）—— 当前 QPS 量级下表增长可控；
--      若未来膨胀，可按 created_at 月度分区（pg_partman / 手工 ATTACH PARTITION）。
--   2. documents JSONB 仅存元数据（docId/fileName/score/source/page），不存正文——避免膨胀。
--   3. session_id 复用 conversationId（Chat/Agent 路径统一），便于跨表关联 agent_session_event。
--   4. trace_id 关联 micrometer 注入的日志 traceId，可 grep 日志还原完整请求上下文。

CREATE TABLE trace_event (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(64),                   -- micrometer 注入的 traceId（日志串联；可空，无 tracing 环境时为 null）
    session_id      VARCHAR(64) NOT NULL,          -- conversationId（Chat/Agent 统一会话标识）
    user_id         BIGINT NOT NULL,
    step_type       VARCHAR(32) NOT NULL,          -- QUERY_REWRITE / VECTOR_SEARCH / BM25_SEARCH / RRF_FUSION / RERANK / CONTEXT_ASSEMBLY / HYBRID_SEARCH
    tool_name       VARCHAR(64),                   -- 工具/服务名（Agent 路径的 Tool 名；Chat 路径为 Service 名）
    success         BOOLEAN NOT NULL,
    duration_ms     BIGINT,                        -- 步骤耗时（ms）
    input_summary   VARCHAR(1000),                 -- 输入摘要（如原始 query；超出截断）
    output_summary  VARCHAR(1000),                 -- 输出摘要（如改写后 query；超出截断）
    doc_count       INT,                           -- 召回/处理后文档数
    top_score       DOUBLE PRECISION,              -- 最高相关性得分（向量相似度/RRF/rerank）
    documents       JSONB,                         -- 文档明细数组（仅元数据，不含正文）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 按会话 + 时间查询（还原一次请求的完整链路；主查询路径）
CREATE INDEX idx_trace_session ON trace_event(session_id, created_at);

-- 按用户 + 时间查询（用户级排障）
CREATE INDEX idx_trace_user ON trace_event(user_id, created_at);

-- 按步骤类型 + 时间查询（如：查最近所有 RERANK 步骤的质量）
CREATE INDEX idx_trace_step ON trace_event(step_type, created_at);

-- 表注释
COMMENT ON TABLE trace_event IS '链路追踪事件表 -- 记录每步明细（改写/检索/融合/重排/组装等），永久保留';
COMMENT ON COLUMN trace_event.trace_id IS 'micrometer 注入的日志 traceId，可 grep 日志串联完整请求';
COMMENT ON COLUMN trace_event.session_id IS '会话标识（复用 conversationId，Chat/Agent 路径统一）';
COMMENT ON COLUMN trace_event.step_type IS '步骤类型：QUERY_REWRITE/VECTOR_SEARCH/BM25_SEARCH/RRF_FUSION/RERANK/CONTEXT_ASSEMBLY/HYBRID_SEARCH';
COMMENT ON COLUMN trace_event.documents IS '文档明细 JSONB 数组（仅 docId/fileName/score/source/page 元数据，不含正文）';
COMMENT ON COLUMN trace_event.input_summary IS '输入摘要（如改写前 query），超出 1000 字截断';
COMMENT ON COLUMN trace_event.output_summary IS '输出摘要（如改写后 query），超出 1000 字截断';
