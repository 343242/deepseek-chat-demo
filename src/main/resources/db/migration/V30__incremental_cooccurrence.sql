-- ============================================================
-- V30__incremental_cooccurrence.sql — 共现图增量维护
-- 设计文档：docs/design/incremental-cooccurrence-maintenance.md §2
--
-- 1) rag_chunk_entity 增加权威 document_id（删除路径直查，废除 rag_event 桥接）
-- 2) rag_document 增加 entity_extracted_at（抽取完成标记，§6.2 重链接检测）
-- 3) 存量数据清空重建（用户批准，不做兼容迁移）
--    注意：rag_event 同时是 SAG 检索层 expandChunks 的输入，TRUNCATE 后同样为空，
--    存量文档经首轮对账的重链接检测自动全量重抽（§2 重建路径已内建）。
-- ============================================================

-- 清空存量（V21 四张实体索引表；表间无外键，TRUNCATE 单事务原子）
TRUNCATE rag_chunk_entity, rag_event, rag_entity, rag_entity_cooccurrence;

-- 权威文档归属列
ALTER TABLE rag_chunk_entity ADD COLUMN document_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_chunk_entity ALTER COLUMN document_id DROP DEFAULT;
CREATE INDEX idx_ce_document ON rag_chunk_entity (document_id);

-- 抽取完成标记（§6.2 重链接检测：NULL = 抽取未完成/未尝试，对账据此重触发；
-- 存量行 NULL 即首轮对账自动重建的驱动源）。rag_document 为应用自有表
-- （MyBatis-Plus 管理），不涉 Spring AI 契约。
ALTER TABLE rag_document ADD COLUMN entity_extracted_at TIMESTAMPTZ NULL;
CREATE INDEX idx_doc_entity_extraction_pending ON rag_document (update_time)
    WHERE entity_extracted_at IS NULL AND deleted = 0;

COMMENT ON COLUMN rag_chunk_entity.document_id IS '权威文档归属（V30：删除路径直查，废除 rag_event 桥接）';
COMMENT ON COLUMN rag_document.entity_extracted_at IS '实体抽取完成标记（NULL = 未完成，对账重链接检测据此重触发）';

-- ============================================================
-- Down SQL（Flyway 不执行，人工回滚用——V21 惯例；数据不恢复，回滚即接受清空重建）：
--   DROP INDEX IF EXISTS idx_doc_entity_extraction_pending;
--   ALTER TABLE rag_document DROP COLUMN IF EXISTS entity_extracted_at;
--   DROP INDEX IF EXISTS idx_ce_document;
--   ALTER TABLE rag_chunk_entity DROP COLUMN IF EXISTS document_id;
-- ============================================================
