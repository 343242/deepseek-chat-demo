-- ============================================================
-- V17: MCP Admin Management — DB 驱动 + 通用审计基础设施
-- Task: mcp-admin-management (design.md v4)
-- ============================================================

-- 1. MCP Server 配置表（含 Bearer Token 加密存储 + init_error 软失败 + 乐观锁）
CREATE TABLE mcp_server_config (
    id                       BIGSERIAL PRIMARY KEY,
    server_id                VARCHAR(128) UNIQUE,                  -- 系统派生（握手后回填），握手前 NULL
    url                      TEXT NOT NULL,
    name                     VARCHAR(256),                          -- ADMIN 可改的展示名（不影响 serverId / 工具前缀）
    description              VARCHAR(512),
    enabled                  BOOLEAN NOT NULL DEFAULT TRUE,
    auto_connect             BOOLEAN NOT NULL DEFAULT TRUE,
    bearer_token_encrypted   TEXT,                                  -- 加密 Bearer Token（SecretCipher）
    init_error               TEXT,                                  -- 软失败：client 创建/握手失败原因
    last_connected_at        TIMESTAMPTZ,
    version                  BIGINT NOT NULL DEFAULT 0,             -- 乐观锁
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- v4 B4：要么握手成功有 serverId，要么握手失败有 init_error 占位
    CONSTRAINT mcp_server_config_state CHECK (
        server_id IS NOT NULL OR init_error IS NOT NULL
    )
);

-- 2. MCP 工具配置表（per-tool 启用/风险/意图/描述覆盖）
CREATE TABLE mcp_tool_config (
    id                   BIGSERIAL PRIMARY KEY,
    server_id            VARCHAR(128) NOT NULL,
    tool_name            VARCHAR(256) NOT NULL,
    prefixed_tool_name   VARCHAR(512) NOT NULL UNIQUE,             -- DatabaseToolFilter 主查键
    description          TEXT,
    enabled              BOOLEAN NOT NULL DEFAULT FALSE,           -- v4 1.4：默认 false（DB-driven 默认 deny）
    intent               VARCHAR(64),                              -- McpIntent: GENERAL_TOOL / RETRIEVAL / DEEP_RETRIEVAL / DIRECT_ANSWER
    risk                 VARCHAR(32) DEFAULT 'low',                -- low / high
    description_override TEXT,                                      -- ADMIN 可信描述覆盖（替代远端不可信 description）
    version              BIGINT NOT NULL DEFAULT 0,                 -- 乐观锁
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mcp_tool_config_server_id ON mcp_tool_config(server_id);

-- 3. MCP 安全配置（jsonb 单行表；v4 修订：从 EAV 改为 jsonb 整体读写）
CREATE TABLE mcp_security_config (
    id              BIGINT PRIMARY KEY DEFAULT 1,
    config_json     JSONB NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT mcp_security_config_single_row CHECK (id = 1)
);

INSERT INTO mcp_security_config (id, config_json) VALUES
(1, '{"sensitiveArgPatterns":[],"defaultOutputCapChars":4000,"highRiskOutputCapChars":2000,"toolDescCharLimit":500}');

-- 4. 通用审计日志表（不绑定 MCP，未来 LLM/RAG 等模块复用）
CREATE TABLE admin_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    operator_id     BIGINT,
    operator_name   VARCHAR(128),
    operator_role   VARCHAR(64),
    resource_type   VARCHAR(64) NOT NULL,
    resource_id     VARCHAR(128),
    action          VARCHAR(64) NOT NULL,
    request_payload JSONB,
    result_status   VARCHAR(16) NOT NULL,
    error_code      VARCHAR(64),
    error_message   TEXT,
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(256),
    duration_ms     INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_audit_log_operator   ON admin_audit_log(operator_id, created_at DESC);
CREATE INDEX idx_admin_audit_log_resource   ON admin_audit_log(resource_type, resource_id, created_at DESC);
CREATE INDEX idx_admin_audit_log_action     ON admin_audit_log(action, created_at DESC);
CREATE INDEX idx_admin_audit_log_created_at ON admin_audit_log(created_at DESC);
