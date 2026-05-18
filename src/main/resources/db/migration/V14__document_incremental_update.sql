-- V14__document_incremental_update.sql — 文档增量更新

-- 文档版本号（替换时自增）
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

-- 被哪个文档替代（NULL = 当前版本）
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS superseded_by BIGINT;

-- 文档逻辑标识（同一文档不同版本共享）
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS document_group_id VARCHAR(36);

-- 唯一约束：同一文档组内版本号不重复
CREATE UNIQUE INDEX IF NOT EXISTS uq_rag_document_group_version
    ON rag_document (document_group_id, version)
    WHERE deleted = 0 AND document_group_id IS NOT NULL;

-- 辅助索引
CREATE INDEX IF NOT EXISTS idx_rag_document_group
    ON rag_document (document_group_id)
    WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_rag_document_superseded_by
    ON rag_document (superseded_by)
    WHERE superseded_by IS NOT NULL;

COMMENT ON COLUMN rag_document.version IS '文档版本号，替换时自增';
COMMENT ON COLUMN rag_document.superseded_by IS '被替代为哪个文档ID，NULL表示当前版本';
COMMENT ON COLUMN rag_document.document_group_id IS '文档逻辑标识，同一文档的不同版本共享（UUIDv7）';

-- 存量数据回填：每个已有文档分配独立 groupId
UPDATE rag_document
SET document_group_id = id::TEXT
WHERE document_group_id IS NULL AND deleted = 0;
