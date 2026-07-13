-- ============================================================
-- V19: DB-Driven MCP Runtime — destructive pre-release cutover
-- Task: mcp-db-source-of-truth (design.md §3)
--
-- PostgreSQL is now the sole connection source. Existing MCP
-- Server/tool rows are deleted; schema is recreated with local
-- identity, desired/observed hashes, retry fields, and idempotency.
-- mcp_security_config and admin_audit_log are untouched.
-- ============================================================

-- Drop tool rows before server rows (index dependency).
DROP TABLE IF EXISTS mcp_tool_config;
DROP TABLE IF EXISTS mcp_server_config;

-- ============================================================
-- 1. mcp_server_config — desired/observed facts, no persisted status
-- ============================================================
CREATE TABLE mcp_server_config (
    id                       BIGSERIAL PRIMARY KEY,
    server_id                VARCHAR(48)  NOT NULL UNIQUE,       -- mcp_<row-id>
    remote_server_name       VARCHAR(255),                        -- informational MCP initialize result
    url                      TEXT         NOT NULL,
    name                     VARCHAR(256),                        -- ADMIN display name
    description              VARCHAR(512),
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    auto_connect             BOOLEAN      NOT NULL DEFAULT TRUE,
    bearer_token_encrypted   TEXT,                                 -- v2:<keyId>:<cipher>:<iv> envelope
    desired_state_hash       CHAR(64)     NOT NULL,               -- SHA-256 of canonical URL + envelope/null + enabled
    observed_state_hash      CHAR(64),                             -- hash of currently published client; null = no trusted observation
    catalog_synced           BOOLEAN      NOT NULL DEFAULT FALSE,  -- complete tool catalog committed for desired state
    error_code               VARCHAR(64),                          -- stable allowlisted current failure code
    error_message            VARCHAR(256),                         -- safe Chinese message from allowlist
    consecutive_failures     INTEGER      NOT NULL DEFAULT 0,     -- recovery backoff input
    next_reconcile_at        TIMESTAMPTZ,                          -- durable due time for reconciliation
    last_attempt_at          TIMESTAMPTZ,                          -- latest background attempt
    last_connected_at        TIMESTAMPTZ,                          -- latest applied connection
    create_request_key       VARCHAR(128) NOT NULL UNIQUE,         -- create idempotency key; removed with row
    version                  BIGINT       NOT NULL DEFAULT 0,      -- optimistic lock (desired writes bump; observed do not)
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- server_id pattern: mcp_<digits>
    CONSTRAINT mcp_server_config_server_id_fmt
        CHECK (server_id ~ '^mcp_[0-9]+$'),
    -- hashes are lowercase 64-char hex
    CONSTRAINT mcp_server_config_desired_hash_fmt
        CHECK (desired_state_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT mcp_server_config_observed_hash_fmt
        CHECK (observed_state_hash IS NULL OR observed_state_hash ~ '^[0-9a-f]{64}$'),
    -- nonnegative failures
    CONSTRAINT mcp_server_config_failures_nonneg
        CHECK (consecutive_failures >= 0),
    -- error code/message: both null or both present
    CONSTRAINT mcp_server_config_error_pair
        CHECK ((error_code IS NULL AND error_message IS NULL)
            OR (error_code IS NOT NULL AND error_message IS NOT NULL)),
    -- catalog_synced requires matching observed hash
    CONSTRAINT mcp_server_config_catalog_requires_observed
        CHECK (NOT catalog_synced OR observed_state_hash = desired_state_hash)
);

-- Due-work scan index
CREATE INDEX idx_mcp_server_config_due
    ON mcp_server_config (enabled, next_reconcile_at)
    WHERE next_reconcile_at IS NOT NULL;

-- Idempotency key lookup (UNIQUE constraint already creates an index)
-- Paginated listing uses id ordering via PK.

-- ============================================================
-- 2. mcp_tool_config — presence, schema, complete-set catalog
-- ============================================================
CREATE TABLE mcp_tool_config (
    id                       BIGSERIAL PRIMARY KEY,
    server_id                VARCHAR(48)  NOT NULL,
    tool_name                VARCHAR(256) NOT NULL,               -- exact remote name
    prefixed_tool_name       VARCHAR(512) NOT NULL,               -- <localServerId>_<rawName>
    description              TEXT,
    enabled                  BOOLEAN      NOT NULL DEFAULT FALSE,  -- DB-driven default deny
    intent                   VARCHAR(64)  NOT NULL DEFAULT 'GENERAL_TOOL',
    risk                     VARCHAR(32)  NOT NULL DEFAULT 'low',
    description_override     TEXT,                                 -- ADMIN trusted override
    present                  BOOLEAN      NOT NULL DEFAULT TRUE,   -- false = seen before, now missing
    last_seen_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    input_schema             JSONB        NOT NULL DEFAULT '{}',   -- MCP inputSchema (JSON object)
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT mcp_tool_config_intent
        CHECK (intent IN ('DIRECT_ANSWER', 'RETRIEVAL', 'DEEP_RETRIEVAL', 'GENERAL_TOOL')),
    CONSTRAINT mcp_tool_config_risk
        CHECK (risk IN ('low', 'high')),
    CONSTRAINT mcp_tool_config_schema_object
        CHECK (jsonb_typeof(input_schema) = 'object')
);

CREATE UNIQUE INDEX uk_mcp_tool_server_tool
    ON mcp_tool_config (server_id, tool_name);

CREATE UNIQUE INDEX uk_mcp_tool_prefixed
    ON mcp_tool_config (prefixed_tool_name);

-- Server tool listing
CREATE INDEX idx_mcp_tool_config_server_id
    ON mcp_tool_config (server_id, prefixed_tool_name);
