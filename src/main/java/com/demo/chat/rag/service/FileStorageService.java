package com.demo.chat.rag.service;

import org.springframework.core.io.Resource;

/**
 * 文件存储服务抽象接口
 * <p>
 * 封装底层对象存储细节，上层业务不应感知具体实现（MinIO / S3 / 本地）。
 * </p>
 */
public interface FileStorageService {

    /**
     * 确保存储桶存在（幂等）
     */
    void ensureBucketExists(String bucket);

    /**
     * 上传文件
     *
     * @param bucket     存储桶
     * @param objectKey  对象 key
     * @param resource   文件资源
     * @param mimeType   MIME 类型
     */
    void upload(String bucket, String objectKey, Resource resource, String mimeType);

    /**
     * 下载文件为 Resource
     *
     * @param bucket    存储桶
     * @param objectKey 对象 key
     * @return 文件资源
     */
    Resource download(String bucket, String objectKey);

    /**
     * 删除文件
     *
     * @param bucket    存储桶
     * @param objectKey 对象 key
     */
    void delete(String bucket, String objectKey);

    /**
     * 生成预签名下载 URL
     *
     * @param bucket    存储桶
     * @param objectKey 对象 key
     * @param expirySeconds 有效期（秒）
     * @return 预签名 URL 字符串
     */
    String presignedUrl(String bucket, String objectKey, int expirySeconds);
}
