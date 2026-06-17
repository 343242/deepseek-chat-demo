-- ============================================================
-- V5__conversation_and_message.sql — 会话 + 消息表
--
-- 建立 Session → Messages 两层结构：
--   conversation: 用户的对话容器，有标题、置顶、状态等元数据
--   message: 会话内的每一条 user/assistant 消息，支持树结构（parent_id）
--
-- 和 spring_ai_chat_memory 的关系：
--   conversation.conversation_id = spring_ai_chat_memory.conversation_id
--   Spring AI 继续管理 memory 表，我们用 message 表做业务层记录
-- ============================================================

-- ======================== 会话表 ========================

CREATE TABLE IF NOT EXISTS conversation (
    id              BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    conversation_id VARCHAR(100) NOT NULL,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(200),
    title_source    VARCHAR(20)  DEFAULT 'SYSTEM',
    model_id        VARCHAR(100),
    pinned          BOOLEAN      DEFAULT FALSE,
    status          VARCHAR(20)  DEFAULT 'ACTIVE',
    message_count   INT          DEFAULT 0,
    last_message_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_conversation_cid UNIQUE (conversation_id)
);

CREATE INDEX idx_conv_user_status ON conversation (user_id, status, last_message_at DESC);

-- ======================== 消息表 ========================

CREATE TABLE IF NOT EXISTS message (
    id                BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    conversation_id   VARCHAR(100) NOT NULL,
    parent_id         BIGINT,
    role              VARCHAR(20)  NOT NULL,
    content           TEXT,
    status            VARCHAR(20)  DEFAULT 'FINISHED',
    model_id          VARCHAR(100),
    thinking_enabled  BOOLEAN      DEFAULT FALSE,
    token_usage       INT,
    duration_ms       BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_message_parent FOREIGN KEY (parent_id) REFERENCES message(id) ON DELETE SET NULL
);

CREATE INDEX idx_msg_conv_id ON message (conversation_id, created_at ASC);
CREATE INDEX idx_msg_parent  ON message (parent_id);
