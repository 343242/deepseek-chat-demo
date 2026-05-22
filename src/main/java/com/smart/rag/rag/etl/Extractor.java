package com.smart.rag.rag.etl;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * ETL Extract 阶段：从文件存储下载文档并解析为结构化文本
 */
public interface Extractor {

    /**
     * 从存储中提取文档内容
     *
     * @param bucket    MinIO bucket
     * @param objectKey MinIO 对象 key
     * @param mimeType  MIME 类型
     * @return 解析后的文档列表
     */
    List<Document> extract(String bucket, String objectKey, String mimeType);
}
