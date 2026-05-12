package com.demo.chat.rag.upload;

import io.minio.*;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 孤儿分片清理定时任务。
 * <p>
 * 定期扫描 Redis 中过期的上传会话和 MinIO 中的临时分片对象，
 * 清理因客户端异常断开或合并失败而遗留的资源。
 * <p>
 * 清理规则：
 * <ol>
 *   <li>Redis session TTL 24h 自动过期，无需手动清理</li>
 *   <li>MinIO chunks/ 前缀下超过 48h 的临时对象视为孤儿</li>
 *   <li>正在合并的 session（含 __merging 标记）跳过</li>
 * </ol>
 */
@Component
public class OrphanChunkCleaner {

    private static final Logger log = LoggerFactory.getLogger(OrphanChunkCleaner.class);

    /** 孤儿判定阈值：创建超过此时间的临时对象视为孤儿 */
    private static final long ORPHAN_AGE_HOURS = 48;

    private final StringRedisTemplate redisTemplate;
    private final MinioClient minioClient;
    private final String bucket;

    public OrphanChunkCleaner(StringRedisTemplate redisTemplate,
                              MinioClient minioClient,
                              com.demo.chat.rag.config.MinioProperties minioProperties) {
        this.redisTemplate = redisTemplate;
        this.minioClient = minioClient;
        this.bucket = minioProperties.getBucket();
    }

    /**
     * 每 6 小时清理一次孤儿分片。
     * <p>
     * 延迟 5 分钟启动，避免应用启动时立即执行。
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void cleanOrphanChunks() {
        log.info("Orphan chunk cleanup started");
        int cleaned = 0;
        int errors = 0;

        try {
            Instant threshold = Instant.now().minus(Duration.ofHours(ORPHAN_AGE_HOURS));

            // 扫描 MinIO chunks/ 前缀下所有对象
            Iterable<io.minio.Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix("chunks/")
                            .recursive(true)
                            .build()
            );

            for (io.minio.Result<Item> result : results) {
                try {
                    Item item = result.get();
                    // 超过阈值的对象视为孤儿
                    if (item.lastModified() != null && item.lastModified().toInstant().isBefore(threshold)) {
                        // 检查是否仍有活跃 session（从路径提取 uploadId）
                        String objectName = item.objectName();
                        if (!hasActiveSession(objectName)) {
                            minioClient.removeObject(
                                    RemoveObjectArgs.builder()
                                            .bucket(bucket)
                                            .object(objectName)
                                            .build()
                            );
                            cleaned++;
                            log.debug("Cleaned orphan chunk: {}/{}", bucket, objectName);
                        }
                    }
                } catch (Exception e) {
                    errors++;
                    log.warn("Failed to process chunk object: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Orphan chunk cleanup failed", e);
        }

        if (cleaned > 0 || errors > 0) {
            log.info("Orphan chunk cleanup finished: cleaned={}, errors={}", cleaned, errors);
        } else {
            log.debug("Orphan chunk cleanup finished: no orphans found");
        }
    }

    /**
     * 检查分片对象是否仍有活跃的 Redis session。
     * <p>
     * 路径格式: chunks/{userId}/{uploadId}/part-{chunkIndex}
     * 从路径提取 uploadId，检查 Redis session 是否存在。
     */
    private boolean hasActiveSession(String objectName) {
        // chunks/{userId}/{uploadId}/part-0 → 提取 uploadId
        String[] parts = objectName.split("/");
        if (parts.length >= 3) {
            String uploadId = parts[2];
            String sessionKey = UploadRedisConstants.sessionKey(uploadId);
            Long ttl = redisTemplate.getExpire(sessionKey, TimeUnit.SECONDS);
            // session 存在且未过期
            return ttl != null && ttl > 0;
        }
        return false;
    }
}
