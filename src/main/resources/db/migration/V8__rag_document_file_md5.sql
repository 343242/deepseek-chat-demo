-- V8: 为 rag_document 新增 file_md5 字段，用于秒传校验
-- file_md5 由服务端在合并时独立计算（MinIO 流式读取），不信任前端声明

ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS file_md5 VARCHAR(32);

COMMENT ON COLUMN rag_document.file_md5 IS '文件 MD5（服务端合并时计算），用于秒传校验';

CREATE INDEX IF NOT EXISTS idx_rag_document_file_md5
    ON rag_document (file_md5)
    WHERE file_md5 IS NOT NULL AND deleted = 0;
