package com.smart.rag.rag.upload;

import java.util.List;

/**
 * 分片上传初始化响应（秒传 / 新建 / 续传统一）。
 */
public record ChunkUploadResult(

    /** 是否秒传命中 */
    boolean uploaded,

    /** 上传会话 ID（新建/续传时有值） */
    String uploadId,

    /** 实际分片大小（后端确定） */
    Integer chunkSize,

    /** 总分片数 */
    Integer totalChunks,

    /** 已上传的分片索引列表（续传时有值） */
    List<Integer> uploadedChunks,

    /** 秒传命中的文档 ID */
    Long documentId,

    /** 秒传命中的文件名 */
    String fileName
) {

    /** 秒传命中 */
    public static ChunkUploadResult quickUploaded(Long documentId, String fileName) {
        return new ChunkUploadResult(true, null, null, null, null, documentId, fileName);
    }

    /** 新建上传会话 */
    public static ChunkUploadResult newSession(String uploadId, int chunkSize, int totalChunks) {
        return new ChunkUploadResult(false, uploadId, chunkSize, totalChunks, List.of(), null, null);
    }

    /** 续传 */
    public static ChunkUploadResult resumeSession(String uploadId, int chunkSize, int totalChunks,
                                                   List<Integer> uploadedChunks) {
        return new ChunkUploadResult(false, uploadId, chunkSize, totalChunks, uploadedChunks, null, null);
    }
}
