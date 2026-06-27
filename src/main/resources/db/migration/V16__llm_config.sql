-- ============================================================
-- V16__llm_config.sql — BYOK 模型配置表
--
-- 用户级 BYOK（Bring Your Own Key）配置：每个用户自带 LLM provider + api_key。
-- 系统默认仍由 application-*.yml 驱动（无系统级 DB 层，见 design §7）。
--
-- 关键设计（见 .trellis/tasks/06-25-byok-llm-config/design.md §2.1）：
--   - id: snowflake（应用层 MyBatis-Plus IdType.INPUT 填充，非 DB 自增）
--   - api_key_cipher/iv: AES/GCM/NoPadding 密文 + 每行独立 12B IV（§3）
--   - user_id NOT NULL: admin 也是用户（如 userId=1），无"系统级"NULL 行（§7）
--   - 唯一索引带 WHERE deleted = 0（部分索引，支持逻辑删除后重建）
--   - is_default 部分唯一索引：并发安全（对抗审查 P0-2）
-- ============================================================

CREATE TABLE IF NOT EXISTS llm_config (
    id                  BIGINT       PRIMARY KEY,                    -- snowflake（IdType.INPUT，应用填充，非 DB 自增）
    user_id             BIGINT       NOT NULL,                       -- 所属用户；admin 也是用户，无系统级 NULL 行
    capability_type     VARCHAR(16)  NOT NULL,                       -- CHAT / EMBEDDING / RERANKING（对齐 LlmCapability 枚举名）
    provider_code       VARCHAR(64)  NOT NULL,                       -- bailian / deepseek / 用户自定义
    base_url            VARCHAR(512) NOT NULL,                       -- 经 BaseUrlValidator SSRF 校验（design §13）
    api_key_cipher      BYTEA        NOT NULL,                       -- AES/GCM/NoPadding 密文（含 16B auth tag）
    api_key_iv          BYTEA        NOT NULL,                       -- 每行独立 12B IV
    model_name          VARCHAR(128) NOT NULL,                       -- 实际调用名（ModelCandidate.model）
    display_name        VARCHAR(128),
    endpoints           JSONB,                                       -- {"chat":..,"embedding":..,"rerank":..} nullable
    dimension           INT,                                         -- 仅 EMBEDDING
    supports_streaming  BOOLEAN      NOT NULL DEFAULT FALSE,
    supports_thinking   BOOLEAN      NOT NULL DEFAULT FALSE,
    priority            INT          NOT NULL DEFAULT 100,
    is_default          BOOLEAN      NOT NULL DEFAULT FALSE,         -- (user_id, capability_type) 唯一；DB 部分索引强制（P0-2）
    status              SMALLINT     NOT NULL DEFAULT 1,             -- 1=enabled 0=disabled
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    deleted             SMALLINT     NOT NULL DEFAULT 0              -- MyBatis-Plus 逻辑删除
);

-- 查询索引：按用户 + capability + 状态查启用配置（resolveUserChain / selectAll）
CREATE INDEX idx_llm_config_user_cap ON llm_config (user_id, capability_type, status, deleted);

-- 幂等 upsert 唯一键（ON CONFLICT 目标）；user_id NOT NULL 无需 COALESCE。
-- ⚠ 部分索引 ON CONFLICT 谓词（对抗审查 R4）：upsert SQL 必须显式
--   ON CONFLICT (user_id, capability_type, provider_code, model_name) WHERE deleted = 0 DO UPDATE ...
-- 谓词必须与索引 WHERE 一致，否则 PG 报 "no unique constraint matching"。
-- MyBatis-Plus 默认 insertOrUpdate 不支持部分索引，禁用，必须自定义 XML。
CREATE UNIQUE INDEX uk_llm_config_user_model
    ON llm_config (user_id, capability_type, provider_code, model_name)
    WHERE deleted = 0;

-- is_default 互斥（并发安全，对抗审查 P0-2）：同 (user_id, capability_type) 至多一行 default。
-- 应用层"写前清旧"无法防并发双写（两请求都清旧→都置 default），必须 DB 部分唯一索引兜底。
CREATE UNIQUE INDEX uk_llm_config_default
    ON llm_config (user_id, capability_type)
    WHERE is_default = TRUE AND deleted = 0;
