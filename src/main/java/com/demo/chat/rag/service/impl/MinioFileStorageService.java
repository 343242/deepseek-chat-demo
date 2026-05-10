package com.demo.chat.rag.service.impl;

import com.demo.chat.rag.config.MinioProperties;
import com.demo.chat.rag.service.FileStorageService;
import io.minio.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
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
                            .stream(is, resource.contentLength(), -1)
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
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            byte[] bytes = is.readAllBytes();
            return new ByteArrayResource(bytes);
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
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(expirySeconds, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            throw new FileStorageException(
                    String.format("Failed to generate presigned URL: %s/%s", bucket, objectKey), e);
        }
    }

    public static class FileStorageException extends RuntimeException {
        public FileStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
