package com.demo.chat.rag.service;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * ETL Pipeline 服务接口
 * <p>
 * 编排 Extract → Transform(Split) → Load(向量入库) 全流程。
 * 当前 Phase 1 Load 预留接口，Phase 2 接入 PGvector 后实现。
 * </p>
 */
public interface EtlPipelineService {

    /**
     * 执行完整的 ETL 流程
     *
     * @param documentId 文档记录 ID
     * @param bucket     MinIO bucket
     * @param objectKey  MinIO 对象 key
     * @param fileName   原始文件名
     * @param mimeType   MIME 类型
     * @return 分块后的文档数量
     */
    int execute(Long documentId, String bucket, String objectKey, String fileName, String mimeType);
}
