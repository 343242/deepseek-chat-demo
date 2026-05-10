-- vector_store 表 BM25 全文检索支持
-- 在 Spring AI pgvector 自动建表后执行

-- 1. 添加 tsvector 列（用于全文检索）
ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS content_tsv tsvector;

-- 2. 创建触发器函数：自动更新 content_tsv
CREATE OR REPLACE FUNCTION vector_store_content_tsv_trigger()
RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3. 创建触发器
DROP TRIGGER IF EXISTS trg_vector_store_content_tsv ON vector_store;
CREATE TRIGGER trg_vector_store_content_tsv
    BEFORE INSERT OR UPDATE OF content ON vector_store
    FOR EACH ROW
    EXECUTE FUNCTION vector_store_content_tsv_trigger();

-- 4. 回填已有数据
UPDATE vector_store SET content_tsv = to_tsvector('simple', COALESCE(content, ''))
WHERE content_tsv IS NULL;

-- 5. 创建 GIN 索引（加速全文检索）
CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv ON vector_store USING GIN (content_tsv);
