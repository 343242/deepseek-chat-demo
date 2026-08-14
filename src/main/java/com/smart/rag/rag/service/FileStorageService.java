package com.smart.rag.rag.service;

import org.springframework.core.io.Resource;

/**
 * 文件存储服务抽象接口
 * <p>
 * 封装底层对象存储细节，上层业务不应感知具体实现（MinIO / S3 / 本地）。
 * 所有对象读取都必须经 {@link #open(String, String)} 取得句柄后按范围读取，
 * 代码库中只有这一条对象读取路径（设计 §6）。
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
     * 打开对象：执行一次 stat 并返回携带准确元数据的句柄。
     * <p>
     * stat 失败（对象不存在、存储不可用）抛 {@code RemoteException}
     * （FILE_STORAGE_UNAVAILABLE，脱敏，不含 bucket / objectKey）。
     */
    StoredObjectHandle open(String bucket, String objectKey);

    /**
     * 删除文件
     *
     * @param bucket    存储桶
     * @param objectKey 对象 key
     */
    void delete(String bucket, String objectKey);
}
