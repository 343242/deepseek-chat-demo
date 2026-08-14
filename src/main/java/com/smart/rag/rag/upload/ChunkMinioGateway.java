package com.smart.rag.rag.upload;

import com.smart.rag.common.util.ChecksumUtils;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.rag.service.impl.DocumentValidator;
import io.minio.ComposeObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.SourceObject;
import io.minio.messages.DeleteRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分片上传 MinIO 网关。
 * <p>
 * 拥有分片写入、composeObject 合并、校验和流式读取、对象删除与临时分片批量清理。
 * <p>
 * 异常分类：MinIO/网络故障属于服务端存储故障，统一抛
 * {@link ServiceException}（INTERNAL_ERROR，中文消息）并携带原始异常为 cause，
 * 不再误分类为客户端错误（UPLOAD_FAILED）。
 */
public class ChunkMinioGateway {

    private static final Logger log = LoggerFactory.getLogger(ChunkMinioGateway.class);

    private final MinioClient minioClient;
    private final DocumentValidator documentValidator;

    public ChunkMinioGateway(MinioClient minioClient, DocumentValidator documentValidator) {
        this.minioClient = minioClient;
        this.documentValidator = documentValidator;
    }

    /**
     * 上传单个分片到 MinIO。
     */
    public void putObject(String bucket, String objectKey, byte[] data, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(data), (long) data.length, -1L)
                            .contentType(contentType)
                            .build()
            );
            log.debug("Uploaded chunk to MinIO: {}/{}", bucket, objectKey);
        } catch (Exception e) {
            log.error("MinIO putObject error: bucket={}, object={}", bucket, objectKey, e);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "存储服务写入分片失败", e);
        }
    }

    /**
     * composeObject 合并分片为目标对象（携带 Content-Type）。
     */
    public void composeObjects(String bucket, String targetObjectKey, List<SourceObject> sources, String contentType) {
        try {
            minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(bucket)
                            .object(targetObjectKey)
                            .sources(sources)
                            .headers(Map.of("Content-Type", contentType))
                            .build()
            );
            log.info("Composed object: {}/{} from {} parts, contentType={}", bucket, targetObjectKey, sources.size(), contentType);
        } catch (Exception e) {
            log.error("MinIO composeObject error: bucket={}, target={}", bucket, targetObjectKey, e);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "存储服务合并分片失败", e);
        }
    }

    /**
     * 流式读取对象并计算 SHA-256 校验和。
     */
    public String computeFileChecksum(String bucket, String objectName) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
            return ChecksumUtils.sha256Hex(is);
        } catch (Exception e) {
            log.error("Failed to compute file checksum from MinIO: bucket={}, object={}", bucket, objectName, e);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "读取合并结果计算校验和失败", e);
        }
    }

    /**
     * R2-H1: 下载对象头部并对真实 MIME 做魔数探测。
     * <p>
     * 仅消费流头部（detectMimeType 内部读 8 字节），用于纠正/验证客户端声明的类型。
     * 探测失败或 IO 异常时返回 null（由调用方决定拒绝策略）。
     *
     * @return 探测到的真实 MIME；失败返回 null
     */
    public @Nullable String detectObjectMimeType(String bucket, String objectName, String fileName) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
            return documentValidator.detectMimeType(is, fileName);
        } catch (Exception e) {
            log.warn("Failed to detect MIME on merged object: bucket={}, object={}, err={}",
                    bucket, objectName, e.getMessage());
            return null;
        }
    }

    /**
     * 尽力删除单个对象；失败仅记录日志，不影响主流程。
     */
    public void deleteObjectBestEffort(String bucket, String objectName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
        } catch (Exception e) {
            log.error("Failed to delete from MinIO: bucket={}, object={}", bucket, objectName, e);
        }
    }

    /**
     * 批量删除对象（removeObjects）；单个失败仅记录日志。
     */
    public void removeObjects(String bucket, List<DeleteRequest.Object> objects) {
        if (objects.isEmpty()) {
            return;
        }
        Iterable<io.minio.Result<io.minio.messages.DeleteResult.Error>> results = minioClient.removeObjects(
                RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build());
        for (io.minio.Result<io.minio.messages.DeleteResult.Error> r : results) {
            try {
                io.minio.messages.DeleteResult.Error err = r.get();
                log.warn("Failed to delete {} in bucket {}: {}", err.objectName(), bucket, err.message());
            } catch (Exception e) {
                log.debug("removeObjects result iteration ended: bucket={}, err={}", bucket, e.getMessage());
            }
        }
    }

    /**
     * 批量清理 {basePath}/part-0 .. part-{totalChunks-1} 临时分片。
     */
    public void cleanupTempChunks(String bucket, String basePath, int totalChunks) {
        List<DeleteRequest.Object> objects = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            objects.add(new DeleteRequest.Object(UploadObjectKeys.chunkObjectKey(basePath, i)));
        }
        removeObjects(bucket, objects);
        log.debug("Cleaned up {} temp chunks under {}/{}", totalChunks, bucket, basePath);
    }
}
