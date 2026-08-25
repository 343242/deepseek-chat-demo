package com.smart.rag.rag.config;

import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    private final MinioProperties minioProperties;

    public MinioConfig(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
    }

    @Bean
    public MinioClient minioClient() {
        log.info("Initializing MinIO client, endpoint: {}", minioProperties.getEndpoint());
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }

    /**
     * 服务端自用的异步 client（内网 endpoint），承载 S3 Multipart Upload 原语
     * （createMultipartUpload / completeMultipartUpload / abortMultipartUpload /
     * listMultipartUploads，SDK 8.5.15 起 public）。与 {@link #minioClient()} 同址，
     * 仅供 {@code S3MultipartGateway} 使用，勿注入其他组件。
     */
    @Bean
    public MinioAsyncClient minioAsyncClient() {
        return MinioAsyncClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .region(minioProperties.getRegion())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }

    /**
     * presign 专用 client：presigned URL 必须以浏览器可达主机名签发（Host 参与签名），
     * 故 endpoint 取 external-endpoint（缺省回退内网 endpoint，dev 同址零配置）。
     * 仅用于 {@code getPresignedObjectUrl}，不做数据面读写。
     */
    @Bean
    public MinioClient presignMinioClient() {
        String externalEndpoint = minioProperties.getExternalEndpoint();
        log.info("Initializing MinIO presign client, endpoint: {}", externalEndpoint);
        return MinioClient.builder()
                .endpoint(externalEndpoint)
                .region(minioProperties.getRegion())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }
}
