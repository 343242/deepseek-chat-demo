-- ============================================================
-- V1__init_schema.sql — 初始表结构
-- ============================================================

-- ======================== 用户权限体系 ========================

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(256) NOT NULL,
    nickname    VARCHAR(64),
    email       VARCHAR(128),
    phone       VARCHAR(32),
    avatar      VARCHAR(512),
    status      INT          NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    role_name   VARCHAR(64)  NOT NULL UNIQUE,
    role_desc   VARCHAR(256),
    status      INT          NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_permission (
    id               BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    permission_name  VARCHAR(128) NOT NULL,
    permission_desc  VARCHAR(256),
    resource_type    VARCHAR(32)  NOT NULL,
    resource_key     VARCHAR(256) NOT NULL,
    parent_id        BIGINT,
    status           INT          NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted          INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id  BIGINT NOT NULL REFERENCES sys_user(id),
    role_id  BIGINT NOT NULL REFERENCES sys_role(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id       BIGINT NOT NULL REFERENCES sys_role(id),
    permission_id BIGINT NOT NULL REFERENCES sys_permission(id),
    PRIMARY KEY (role_id, permission_id)
);

-- ======================== 聊天模块 ========================

CREATE TABLE IF NOT EXISTS system_prompt (
    id          BIGINT        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    model_id    VARCHAR(128)  NOT NULL UNIQUE,
    prompt_text TEXT          NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS model_params (
    id                 BIGINT        PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    model_id           VARCHAR(128)  NOT NULL UNIQUE,
    temperature        DOUBLE PRECISION,
    max_tokens         INT,
    top_p              DOUBLE PRECISION,
    frequency_penalty  DOUBLE PRECISION,
    presence_penalty   DOUBLE PRECISION,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS token_usage (
    id                BIGINT      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    conversation_id   VARCHAR(128) NOT NULL,
    model_id          VARCHAR(128) NOT NULL,
    prompt_tokens     BIGINT,
    completion_tokens BIGINT,
    total_tokens      BIGINT,
    duration_ms       BIGINT,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_token_usage_conversation ON token_usage (conversation_id);
CREATE INDEX IF NOT EXISTS idx_token_usage_model ON token_usage (model_id);
CREATE INDEX IF NOT EXISTS idx_token_usage_created ON token_usage (created_at);

-- ======================== RAG 文档模块 ========================

CREATE TABLE IF NOT EXISTS rag_document (
    id            BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    file_name     VARCHAR(512) NOT NULL,
    file_size     BIGINT,
    mime_type     VARCHAR(128),
    storage_key   VARCHAR(256) NOT NULL,
    bucket        VARCHAR(128) NOT NULL,
    user_id       BIGINT,
    chunk_count   INT,
    status        VARCHAR(32)  NOT NULL DEFAULT 'UPLOADED',
    error_message TEXT,
    create_time   TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time   TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted       INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_rag_document_user ON rag_document (user_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_status ON rag_document (status);
