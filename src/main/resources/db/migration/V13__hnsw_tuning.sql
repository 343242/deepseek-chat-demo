-- ============================================================
-- V13__hnsw_tuning.sql — 优化 HNSW 索引参数 + iterative scan
--
-- Spring AI PgVectorStore 默认使用 PGvector 默认参数创建 HNSW 索引
-- (m=16, ef_construction=64, ef_search=40)。
-- 本迁移重建索引并调整参数以提升召回率：
--   - m=32: 每层最大连接数翻倍，提升图连通性
--   - ef_construction=128: 构建时搜索更充分，索引质量更高
--   - hnsw.ef_search=64: 查询时搜索宽度提升，配合 Rerank 精排
--   - iterative_scan=on: pgvector 0.8+ 特性，解决 metadata 过滤后召回不足
--   - max_scan_tuples=20000: 限制 iterative scan 最大扫描行数
--
-- 注意：重建索引会锁表，生产环境应在低峰期执行。
-- 数据量小时（<10万行）秒级完成。
-- ============================================================

-- 1. 确保必要的扩展（Spring AI 依赖 hstore 和 uuid-ossp）
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. 删除 Spring AI 自动创建的默认 HNSW 索引（无 WITH 参数）
--    索引名称格式：{tableName}_embedding_{indexType}_index
DROP INDEX IF EXISTS vector_store_embedding_hnsw_index;
-- 兼容可能的旧名称
DROP INDEX IF EXISTS vector_store_embedding_idx;

-- 3. 使用自定义参数重建 HNSW 索引
--    m=32: 图连通性（默认 16，翻倍提升召回）
--    ef_construction=128: 构建质量（默认 64，翻倍，仅影响写入性能）
CREATE INDEX vector_store_embedding_hnsw_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops)
    WITH (m = 32, ef_construction = 128);

-- 4. 设置查询时搜索宽度（数据库级默认值，所有会话生效）
--    ef_search=64: 查询时探查更多邻居（默认 40）
--    配合百炼 Rerank 精排，多搜一些再精排是合理的策略
ALTER DATABASE chat_demo SET hnsw.ef_search = 64;

-- 5. 开启 iterative scan（pgvector 0.8+ 特性）
--    解决 HNSW + metadata 过滤后的召回不足问题：
--    当初始 ef_search 返回的结果经过 WHERE 过滤后不足时，
--    pgvector 会自动扩大搜索范围重试，直到满足条件或达到 max_scan_tuples
ALTER DATABASE chat_demo SET hnsw.iterative_scan = on;

-- 6. 限制 iterative scan 最大扫描行数
--    防止单次查询扫描过多行导致延迟飙升
--    20000 行在 1024 维向量下约 80MB 内存扫描，可接受
ALTER DATABASE chat_demo SET hnsw.max_scan_tuples = 20000;
