package com.smart.rag.rag.upload;

/**
 * 分片上传合并完成响应。
 */
public record ChunkUploadCompleteResult(

    /** 合并后的文档 ID */
    Long documentId
) {}
