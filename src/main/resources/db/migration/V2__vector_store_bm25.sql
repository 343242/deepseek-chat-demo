-- ============================================================
-- V2__vector_store_bm25.sql — 为 Spring AI 管理的 vector_store 表
--   追加 BM25 全文检索支持
--
-- 注意：
--   - vector_store 表由 Spring AI pgvector 在 afterPropertiesSet() 中创建
--   - Flyway 在 Spring AI 初始化之前执行
--   - 因此本脚本需要自行确保 pgvector 扩展和基础表存在
--   - 所有 DDL 使用 IF NOT EXISTS，确保幂等安全
-- ============================================================

-- 0. 确保 pgvector 扩展可用
CREATE EXTENSION IF NOT EXISTS vector;

-- 1. 确保 vector_store 基础表存在（如果 Spring AI 还没创建的话）
--    使用与 Spring AI PgVectorStore 相同的 DDL
CREATE TABLE IF NOT EXISTS vector_store (
    id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content     TEXT,
    metadata    JSON,
    embedding   VECTOR(1024)
);

-- 2. 添加 tsvector 列（用于全文检索）
ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS content_tsv tsvector;

-- 3. 创建触发器函数：content 变更时自动更新 content_tsv
CREATE OR REPLACE FUNCTION vector_store_content_tsv_trigger()
RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. 创建触发器
DROP TRIGGER IF EXISTS trg_vector_store_content_tsv ON vector_store;
CREATE TRIGGER trg_vector_store_content_tsv
    BEFORE INSERT OR UPDATE OF content ON vector_store
    FOR EACH ROW
    EXECUTE FUNCTION vector_store_content_tsv_trigger();

-- 5. 回填已有数据（安全操作，空表也无影响）
UPDATE vector_store SET content_tsv = to_tsvector('simple', COALESCE(content, ''))
WHERE content_tsv IS NULL;

-- 6. 创建 GIN 索引（加速全文检索）
CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv
    ON vector_store USING GIN (content_tsv);
