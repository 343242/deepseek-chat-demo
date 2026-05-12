package com.demo.chat.rag.upload;

/**
 * 分片上传响应。
 */
public record ChunkUploadResponse(

    /** 上传会话 ID */
    String uploadId,

    /** 本次上传的分片索引 */
    int chunkIndex,

    /** 所有分片是否已上传完毕 */
    boolean completed,

    /** 是否正在异步合并（completed=true 时有意义） */
    Boolean merging
) {

    /** 普通分片上传成功 */
    public static ChunkUploadResponse uploaded(String uploadId, int chunkIndex) {
        return new ChunkUploadResponse(uploadId, chunkIndex, false, null);
    }

    /** 最后一个分片，触发合并 */
    public static ChunkUploadResponse merging(String uploadId, int chunkIndex) {
        return new ChunkUploadResponse(uploadId, chunkIndex, true, true);
    }
}
