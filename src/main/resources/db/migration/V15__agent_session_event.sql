-- Agent 会话事件表（P2 优化：会话连续性）
-- 存储 Agent 每步事件的详细信息，供会话恢复和可观测性使用。
-- V1: PG-only，后续迭代可引入 Redis 缓存层。

CREATE TABLE agent_session_event (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,
    user_id         BIGINT NOT NULL,
    event_type      VARCHAR(32) NOT NULL,
    priority        SMALLINT NOT NULL DEFAULT 3,
    data            JSONB NOT NULL,
    tool_name       VARCHAR(64),
    success         BOOLEAN,
    duration_ms     BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 索引：按会话 + 时间查询（恢复快照主要查询路径）
CREATE INDEX idx_agent_event_session ON agent_session_event(session_id, created_at);

-- 索引：按会话 + 事件类型查询（按类型筛选事件）
CREATE INDEX idx_agent_event_type ON agent_session_event(session_id, event_type);

-- 索引：按用户 + 会话查询（多租户隔离）
CREATE INDEX idx_agent_event_user ON agent_session_event(user_id, session_id);

-- 添加表注释
COMMENT ON TABLE agent_session_event IS 'Agent 会话事件表 -- 记录每步事件的详细信息，供会话连续性恢复';
COMMENT ON COLUMN agent_session_event.session_id IS '会话 ID（UUIDv7），复用现有会话 ID';
COMMENT ON COLUMN agent_session_event.user_id IS '用户 ID，多租户隔离';
COMMENT ON COLUMN agent_session_event.event_type IS '事件类型：INTENT_CLASSIFIED / INTERMEDIATE_ANSWER / SELF_REFLECTION / RETRIEVAL_STRATEGY / TOOL_CALLED / GUARDRAIL_TRIGGERED';
COMMENT ON COLUMN agent_session_event.priority IS '优先级：1=Critical（意图/答案/护栏）, 2=High（自省/策略）, 3=Normal（Tool 调用）';
COMMENT ON COLUMN agent_session_event.data IS '结构化事件数据（JSONB）';
COMMENT ON COLUMN agent_session_event.tool_name IS 'Tool 名称（仅 TOOL_CALLED 事件有值）';
COMMENT ON COLUMN agent_session_event.success IS '是否成功（仅 TOOL_CALLED 事件有值）';
COMMENT ON COLUMN agent_session_event.duration_ms IS 'Tool 耗时 ms（仅 TOOL_CALLED 事件有值）';
