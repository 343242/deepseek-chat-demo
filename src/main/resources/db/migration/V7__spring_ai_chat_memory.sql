-- ============================================================
-- V7__spring_ai_chat_memory.sql
--
-- Spring AI JDBC Chat Memory 内置表。
-- 原本依赖 spring.ai.chat.memory.repository.jdbc.initialize-schema=always
-- 自动创建，但项目已将该配置设为 never（避免启动时自动 DDL）。
-- 改用 Flyway 管理以确保幂等和版本可控。
-- ============================================================

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content         TEXT        NOT NULL,
    type            VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, "timestamp");
