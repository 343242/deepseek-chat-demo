package com.smart.rag.rag.etl;

import org.springframework.ai.document.Document;

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

    /**
     * manifest 载体（design §6.3 中-2；v1.6 L4 命名避开策略类私有 record ExtractOutput）：
     * 文档列表 + 前台编号的图片清单。manifest 不进 Document.metadata（避免向量库
     * 元数据污染），经本载体透传给策略层在短事务内落库。
     */
    record ExtractWithManifest(List<Document> documents, List<com.smart.rag.rag.parser.odl.ImageManifest.ImageEntry> imageManifest) {}

    /**
     * 携带文档身份的提取（design §6.3）。默认委托旧签名（manifest 为空，兼容非 PDF 链路）。
     */
    default ExtractWithManifest extractWithManifest(String bucket, String objectKey, String mimeType, Long documentId) {
        return new ExtractWithManifest(extract(bucket, objectKey, mimeType), List.of());
    }
}
