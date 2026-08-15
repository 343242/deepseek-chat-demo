-- ============================================================
-- V27__vector_dim_1536.sql — 向量维度 1024 → 1536
--
-- 背景：embedding 候选切换到 qwen3.7-text-embedding（声明 2048 维）后，
--   2048 超出 pgvector HNSW/IVFFlat 索引在 vector 类型上的 2000 维硬上限
--   （可存储、不可索引），故统一为 1536：
--   text-embedding-v4 / Qwen3-Embedding 系列均支持 dimensions 参数输出 1536。
--
-- 影响范围（三处同维，见 V21 注释"与 vector_store 同维"）：
--   1. vector_store.embedding — chunk 主向量库（V2 建表 1024）
--   2. rag_entity.embedding   — 实体中心索引（V21）
--   3. rag_event.embedding    — 事件摘要向量（V21）
--
-- 存量数据处理：
--   - 旧 1024 维向量无法 cast 到 1536，且与新模型的语义空间不兼容（跨模型
--     向量不可混检），清空为 NULL 等待重嵌入
--   - vector_store：向量召回在重嵌入前为空，BM25 全文召回不受影响；
--     恢复方式 = 文档重新入库（ETL 重跑 chunk 嵌入）
--   - rag_entity / rag_event：实体索引构建流程（EntityIndexService 聚合 +
--     EntityEmbeddingService）下次重建时恢复
--
-- 索引：ALTER COLUMN TYPE 时 PostgreSQL 自动重建依赖的 HNSW 索引
--   （vector_store_embedding_hnsw_idx / rag_entity 与 rag_event 的 hnsw 索引），
--   清空后索引为空，重建秒级完成。
--   注意：V13 设置的 hnsw.max_scan_tuples=20000 在 1536 维下扫描内存约
--   由 80MB 升至 120MB，仍在可接受范围，无需调整。
--
-- 幂等性：Flyway 版本号保证仅执行一次；类型相同时重复 ALTER 无副作用。
-- ============================================================

-- 1. 清空旧向量（1024 维跨模型不可迁移）
UPDATE vector_store SET embedding = NULL;
UPDATE rag_entity  SET embedding = NULL;
UPDATE rag_event   SET embedding = NULL;

-- 2. 变更列类型（同类型仅改 typmod，无需 USING；依赖索引自动重建）
ALTER TABLE vector_store ALTER COLUMN embedding TYPE vector(1536);
ALTER TABLE rag_entity   ALTER COLUMN embedding TYPE vector(1536);
ALTER TABLE rag_event    ALTER COLUMN embedding TYPE vector(1536);

-- 3. 刷新列注释（同步 V21 的维度描述）
COMMENT ON COLUMN rag_entity.embedding IS '实体摘要向量 vector(1536)，与 vector_store 同维，HNSW cosine';
COMMENT ON COLUMN rag_event.embedding  IS '事件摘要向量 vector(1536)，HNSW cosine';
