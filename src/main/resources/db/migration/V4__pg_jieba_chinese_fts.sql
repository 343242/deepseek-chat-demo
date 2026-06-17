-- ============================================================
-- V4__pg_jieba_chinese_fts.sql — 启用 pg_jieba 中文分词
--
-- 将 vector_store 表的全文检索从 simple（逐字切分）升级为
-- jiebacfg（结巴中文分词），提升 BM25 检索召回率。
--
-- 幂等安全：所有语句使用 IF NOT EXISTS / CREATE OR REPLACE
-- ============================================================

-- 1. 启用 pg_jieba 扩展
CREATE EXTENSION IF NOT EXISTS pg_jieba;

-- 2. 更新触发器函数：使用 jiebacfg 分词替代 simple
CREATE OR REPLACE FUNCTION vector_store_content_tsv_trigger()
RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('jiebacfg', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3. 回填已有数据（使用分页 UPDATE 避免长事务锁表）
--    直接将所有行用 jiebacfg 重新分词，不依赖旧配置判断
DO $$
DECLARE
    batch_count INT := 1;
BEGIN
    WHILE batch_count > 0 LOOP
        UPDATE vector_store
        SET content_tsv = to_tsvector('jiebacfg', COALESCE(content, ''))
        WHERE id IN (
            SELECT id FROM vector_store
            LIMIT 5000 OFFSET 0
        )
        AND content_tsv IS DISTINCT FROM to_tsvector('jiebacfg', COALESCE(content, ''));

        GET DIAGNOSTICS batch_count = ROW_COUNT;
    END LOOP;
END $$;

-- 4. 重建 GIN 索引（确保使用新的分词配置）
DROP INDEX IF EXISTS idx_vector_store_content_tsv;
CREATE INDEX idx_vector_store_content_tsv
    ON vector_store USING GIN (content_tsv);
