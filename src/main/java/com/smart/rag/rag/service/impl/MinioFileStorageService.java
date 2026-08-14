package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.rag.service.FileStorageService;
import com.smart.rag.rag.service.ObjectReadRange;
import com.smart.rag.rag.service.StoredObjectContent;
import com.smart.rag.rag.service.StoredObjectHandle;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * MinIO 实现的统一存储契约（设计 §6）。
 * <p>
 * {@link #open} 执行一次 statObject；content 返回的 Resource 在被真正读取时才调用
 * getObject，对象内容不进 JVM 完整数组。存储故障统一翻译为脱敏
 * {@code RemoteException}（FILE_STORAGE_UNAVAILABLE）：对外消息固定，日志只记录
 * 操作类型与错误分类，不含 bucket / objectKey / endpoint。
 */
@Service
public class MinioFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorageService.class);

    private static final String STORAGE_UNAVAILABLE_MESSAGE = "文件存储暂不可用";

    private final MinioClient minioClient;

    public MinioFileStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket");
            }
        } catch (Exception e) {
            throw storageFailure("ensureBucket", e);
        }
    }

    @Override
    public void upload(String bucket, String objectKey, Resource resource, String mimeType) {
        try (InputStream is = resource.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(is, resource.contentLength(), -1L)
                            .contentType(mimeType)
                            .build());
            log.debug("Uploaded object to MinIO");
        } catch (Exception e) {
            throw storageFailure("upload", e);
        }
    }

    @Override
    public StoredObjectHandle open(String bucket, String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return new MinioStoredObjectHandle(bucket, objectKey, stat.size());
        } catch (Exception e) {
            throw storageFailure("stat", e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            log.debug("Deleted object from MinIO");
        } catch (Exception e) {
            throw storageFailure("delete", e);
        }
    }

    // ==================== 句柄与惰性内容 ====================

    private final class MinioStoredObjectHandle implements StoredObjectHandle {

        private final String bucket;
        private final String objectKey;
        private final long totalSize;

        MinioStoredObjectHandle(String bucket, String objectKey, long totalSize) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.totalSize = totalSize;
        }

        @Override
        public long totalSize() {
            return totalSize;
        }

        @Override
        public StoredObjectContent content(ObjectReadRange range) {
            if (range instanceof ObjectReadRange.Full) {
                return new StoredObjectContent(
                        new LazyMinioResource(bucket, objectKey, null, null, totalSize), 0, totalSize);
            }
            ObjectReadRange.Bytes bytes = (ObjectReadRange.Bytes) range;
            // 越界与加法溢出防护（设计 §6）
            if (bytes.offset() < 0 || bytes.length() <= 0
                    || bytes.offset() >= totalSize || bytes.length() > totalSize - bytes.offset()) {
                throw new IllegalArgumentException("Invalid read range: offset=" + bytes.offset()
                        + ", length=" + bytes.length() + ", totalSize=" + totalSize);
            }
            return new StoredObjectContent(
                    new LazyMinioResource(bucket, objectKey, bytes.offset(), bytes.length(), bytes.length()),
                    bytes.offset(), bytes.length());
        }
    }

    /**
     * 惰性 Resource：{@link #getInputStream()} 被响应写出器/解析器真正读取时才调用
     * MinIO getObject。contentLength 覆盖为已知值，禁止继承
     * {@code AbstractResource} 的全量读流计数。流只能打开一次；实现
     * {@code Closeable} 供调用方在异常/中断路径释放底层连接。
     */
    private final class LazyMinioResource extends InputStreamResource implements java.io.Closeable {

        private final GetObjectArgs.Builder getArgs;
        private final long knownContentLength;
        private GetObjectResponse response;

        LazyMinioResource(String bucket, String objectKey, Long offset, Long length, long knownContentLength) {
            super(InputStream.nullInputStream());
            this.getArgs = GetObjectArgs.builder().bucket(bucket).object(objectKey);
            if (offset != null) {
                this.getArgs.offset(offset);
            }
            if (length != null) {
                this.getArgs.length(length);
            }
            this.knownContentLength = knownContentLength;
        }

        @Override
        public synchronized InputStream getInputStream() throws IOException {
            if (response != null) {
                throw new IllegalStateException("MinIO object stream already opened");
            }
            try {
                response = minioClient.getObject(getArgs.build());
                return response;
            } catch (Exception e) {
                throw storageFailure("getObject", e);
            }
        }

        @Override
        public long contentLength() {
            return knownContentLength;
        }

        @Override
        public synchronized void close() throws IOException {
            if (response != null) {
                response.close();
            }
        }
    }

    // ==================== 异常翻译 ====================

    private static RemoteException storageFailure(String operation, Exception cause) {
        log.error("MinIO operation failed: op={}, errClass={}, traceId={}",
                operation, cause.getClass().getSimpleName(), MDC.get("traceId"), cause);
        return new RemoteException(RemoteErrorCode.FILE_STORAGE_UNAVAILABLE,
                STORAGE_UNAVAILABLE_MESSAGE, cause);
    }
}
