package com.demo.chat.rag.upload;

import java.util.List;

/**
 * 上传状态查询响应。
 */
public record ChunkUploadStatusResponse(

    /** 上传会话 ID */
    String uploadId,

    /** 文件名 */
    String fileName,

    /** 总分片数 */
    int totalChunks,

    /** 已上传的分片索引列表（已排序） */
    List<Integer> uploadedChunks,

    /** 所有分片是否已上传完毕 */
    boolean completed,

    /** 是否正在合并中 */
    boolean merging,

    /** 合并完成后的文档 ID */
    Long documentId
) {}
