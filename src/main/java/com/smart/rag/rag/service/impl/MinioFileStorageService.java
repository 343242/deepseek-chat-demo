package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.MinioProperties;
import com.smart.rag.rag.service.FileStorageService;
import io.minio.Http;
import io.minio.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
public class MinioFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorageService.class);

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioFileStorageService(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            throw new FileStorageException("Failed to check/create bucket: " + bucket, e);
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
            log.debug("Uploaded file to MinIO: {}/{}", bucket, objectKey);
        } catch (Exception e) {
            throw new FileStorageException(
                    String.format("Failed to upload file: %s/%s", bucket, objectKey), e);
        }
    }

    @Override
    public Resource download(String bucket, String objectKey) {
        try {
            GetObjectResponse response = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
            // 返回流式 Resource，避免大文件全量加载到 JVM 堆内存
            // InputStreamResource 不关闭底层流，由调用方负责
            return new MinioStreamResource(response, bucket, objectKey);
        } catch (Exception e) {
            throw new FileStorageException(
                    String.format("Failed to download file: %s/%s", bucket, objectKey), e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            log.debug("Deleted file from MinIO: {}/{}", bucket, objectKey);
        } catch (Exception e) {
            throw new FileStorageException(
                    String.format("Failed to delete file: %s/%s", bucket, objectKey), e);
        }
    }

    @Override
    public String presignedUrl(String bucket, String objectKey, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Http.Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            throw new FileStorageException(
                    String.format("Failed to generate presigned URL: %s/%s", bucket, objectKey), e);
        }
    }

    /**
     * MinIO 流式 Resource，包装 GetObjectResponse。
     * <p>
     * 解决 MinioFileStorageService.download() 全量加载大文件导致 OOM 的问题。
     * InputStream 在被消费后（如 parser 解析完毕）自动关闭，释放 MinIO 连接。
     * </p>
     */
    static class MinioStreamResource extends InputStreamResource implements java.io.Closeable {

        private final String filename;
        private final GetObjectResponse response;

        MinioStreamResource(GetObjectResponse response, String bucket, String objectKey) {
            super(response);
            this.response = response;
            this.filename = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long contentLength() {
            return -1L; // 未知长度，避免提前读取
        }

        /**
         * R1-M5: 关闭底层 MinIO {@link GetObjectResponse}，释放 HTTP 连接。
         * 供 {@code DocumentExtractor} 在 try-finally 中调用，确保 parser 抛异常时不泄漏连接。
         */
        @Override
        public void close() throws java.io.IOException {
            response.close();
        }
    }

    public static class FileStorageException extends RuntimeException {
        public FileStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
