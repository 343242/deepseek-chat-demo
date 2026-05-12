package com.demo.chat.rag.upload;

/**
 * 分片上传服务接口。
 * <p>
 * 编排 Redis（会话状态）+ MinIO（Multipart Upload）+ DB（文档元数据）。
 */
public interface ChunkUploadService {

    /**
     * 初始化上传会话 / 秒传检查 / 续传恢复
     *
     * @param request 初始化请求（含 fileMd5, fileName, fileSize, mimeType）
     * @return 初始化结果（秒传 / 新建 / 续传）
     */
    ChunkUploadResult init(ChunkUploadInitRequest request);

    /**
     * 上传单个分片
     *
     * @param uploadId   上传会话 ID
     * @param chunkIndex 分片索引（0-based）
     * @param chunkMd5   分片 MD5（前端计算）
     * @param chunkData  分片二进制数据
     * @return 上传响应（含是否触发自动合并）
     */
    ChunkUploadResponse uploadChunk(String uploadId, int chunkIndex, String chunkMd5, byte[] chunkData);

    /**
     * 查询上传状态
     *
     * @param uploadId 上传会话 ID
     * @return 状态响应
     */
    ChunkUploadStatusResponse status(String uploadId);

    /**
     * 手动触发合并
     *
     * @param uploadId 上传会话 ID
     * @param fileMd5  前端声明的文件总 MD5
     * @return 合并后的文档 ID
     */
    Long complete(String uploadId, String fileMd5);

    /**
     * 取消上传（清理 MinIO + Redis）
     *
     * @param uploadId 上传会话 ID
     */
    void abort(String uploadId);
}
