-- V26: 秒传校验字段由 MD5(32) 迁移为 SHA-256(64)，中性命名 file_checksum
-- 上线前无存量数据：直接删旧列 + 旧索引，建新列 + 新索引（无兼容层、无回填）。
-- file_checksum 由服务端在合并时独立计算（MinIO 流式读取），不信任前端声明。

DROP INDEX IF EXISTS idx_rag_document_file_md5;

ALTER TABLE rag_document DROP COLUMN IF EXISTS file_md5;

ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS file_checksum VARCHAR(64);

COMMENT ON COLUMN rag_document.file_checksum IS '文件校验和（SHA-256，服务端合并时计算），用于秒传校验';

CREATE INDEX IF NOT EXISTS idx_rag_document_file_checksum
    ON rag_document (file_checksum)
    WHERE file_checksum IS NOT NULL AND deleted = 0;
