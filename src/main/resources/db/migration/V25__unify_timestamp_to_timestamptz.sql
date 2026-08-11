-- V25__unify_timestamp_to_timestamptz.sql — 收口 V1 残留的 TIMESTAMP（无时区）列为 TIMESTAMPTZ
-- ============================================================================
-- 设计文档 §7：遵守 Flyway 不可变迁移契约——V1/V9/V10 原样保留，不回溯改写、不删文件。
-- 按最大序号往后递增新增本迁移，收口 V1 残留的 3 张 TIMESTAMP 表。
-- rag_document 已由 V10 统一，本迁移不重复。
--
-- AT TIME ZONE 'Asia/Shanghai' 把无时区列值按东八区解释为绝对时刻——与本次确立的展示时区一致。
-- 无存量数据，该解释的实际影响为零（部署时 clean 重建后表为空），但语义上必须正确声明。
-- 运行时时区仍由 APP_TIME_ZONE 单点控制，不进入运行时。
-- ============================================================================

ALTER TABLE system_prompt
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Shanghai';

ALTER TABLE model_params
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Shanghai';

ALTER TABLE token_usage
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Shanghai';
