package com.smart.rag.rag.health;

import com.smart.rag.rag.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储健康指标——检查 bucket 可达性。供 actuator {@code /health/minio} 消费。
 * <p>
 * <b>永不标应用 DOWN</b>（与 {@code McpHealthIndicator}/{@code LlmHealthIndicator} 同构）：
 * MinIO 是可选文档存储（上传/原文档访问），不可用不应击穿应用 liveness——
 * 即使 unreachable，应用仍 UP + {@code reachable=false} detail。
 * <p>
 * <b>探针</b>：调用 {@code bucketExists}（HEAD bucket 轻量操作），验证 endpoint/凭证/bucket 三者连通。
 */
@Component
public class MinioHealthIndicator extends AbstractHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(MinioHealthIndicator.class);

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioHealthIndicator(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        String bucket = properties.getBucket();
        try {
            boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build());
            // MinIO 可选：永不标 DOWN——reachable=false 仅入 detail
            builder.up()
                .withDetail("endpoint", properties.getEndpoint())
                .withDetail("bucket", bucket)
                .withDetail("bucketExists", exists);
            if (!exists) {
                builder.withDetail("warning",
                    "configured bucket does not exist — uploads will fail until created");
            }
        } catch (Exception e) {
            log.warn("MinIO health check failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            builder.up()
                .withDetail("endpoint", properties.getEndpoint())
                .withDetail("bucket", bucket)
                .withDetail("reachable", false)
                .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                .withDetail("reason", "MinIO is optional (app stays UP: document uploads degraded)")
                .withDetail("action", "Check MinIO endpoint/credentials/bucket existence");
        }
    }
}
