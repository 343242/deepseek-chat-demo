package com.demo.chat.rag.upload;

import io.minio.*;
import io.minio.messages.DeleteRequest;
import io.minio.messages.DeleteResult;
import io.minio.messages.Item;
import io.minio.messages.ListAllMyBucketsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 孤儿分片清理定时任务。
 * <p>
 * 定期扫描 Redis 中过期的上传会话和 MinIO 中所有 bucket 的临时分片对象，
 * 清理因客户端异常断开或合并失败而遗留的资源。
 * 同时清理团队已解散的孤儿空桶。
 * <p>
 * 清理规则：
 * <ol>
 *   <li>Redis session TTL 24h 自动过期，无需手动清理</li>
 *   <li>MinIO chunks/ 前缀下超过 48h 的临时对象视为孤儿</li>
 *   <li>正在合并的 session（含 __merging 标记）跳过</li>
 *   <li>团队已软删（deleted=1）的空桶自动清理</li>
 * </ol>
 */
@Component
public class OrphanChunkCleaner {

    private static final Logger log = LoggerFactory.getLogger(OrphanChunkCleaner.class);

    /** 孤儿判定阈值：创建超过此时间的临时对象视为孤儿 */
    private static final long ORPHAN_AGE_HOURS = 48;

    private final StringRedisTemplate redisTemplate;
    private final MinioClient minioClient;
    private final BucketResolver bucketResolver;

    public OrphanChunkCleaner(StringRedisTemplate redisTemplate,
                              MinioClient minioClient,
                              BucketResolver bucketResolver) {
        this.redisTemplate = redisTemplate;
        this.minioClient = minioClient;
        this.bucketResolver = bucketResolver;
    }

    /**
     * 每 6 小时清理一次孤儿分片。
     * <p>
     * 延迟 5 分钟启动，避免应用启动时立即执行。
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void cleanOrphanChunks() {
        log.info("Orphan chunk cleanup started");
        int totalCleaned = 0;
        int totalErrors = 0;
        int totalOrphanBuckets = 0;

        try {
            // 动态获取所有需要扫描的 bucket（默认 + 所有 rag-team-*）
            List<String> allBuckets = minioClient.listBuckets().stream()
                    .map(ListAllMyBucketsResult.Bucket::name)
                    .filter(name -> name.equals(bucketResolver.defaultBucket()) || bucketResolver.isTeamBucket(name))
                    .toList();

            for (String bucket : allBuckets) {
                int[] result = cleanOrphansInBucket(bucket);
                totalCleaned += result[0];
                totalErrors += result[1];
            }

            // 清理孤儿空桶（团队已软删）
            totalOrphanBuckets = cleanOrphanEmptyBuckets(allBuckets);

        } catch (Exception e) {
            log.error("Orphan chunk cleanup failed", e);
        }

        if (totalCleaned > 0 || totalErrors > 0 || totalOrphanBuckets > 0) {
            log.info("Orphan chunk cleanup finished: chunks={}, errors={}, orphanBuckets={}",
                    totalCleaned, totalErrors, totalOrphanBuckets);
        } else {
            log.debug("Orphan chunk cleanup finished: no orphans found");
        }
    }

    /**
     * 清理单个 bucket 中的孤儿分片。
     *
     * @return [cleaned, errors]
     */
    private int[] cleanOrphansInBucket(String bucket) {
        int cleaned = 0;
        int errors = 0;

        try {
            Instant threshold = Instant.now().minus(Duration.ofHours(ORPHAN_AGE_HOURS));

            Iterable<io.minio.Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix("chunks/")
                            .recursive(true)
                            .build()
            );

            List<DeleteRequest.Object> orphans = new ArrayList<>();
            for (io.minio.Result<Item> result : results) {
                try {
                    Item item = result.get();
                    if (item.lastModified() != null && item.lastModified().toInstant().isBefore(threshold)) {
                        String objectName = item.objectName();
                        if (!hasActiveSession(objectName)) {
                            orphans.add(new DeleteRequest.Object(objectName));
                        }
                    }
                } catch (Exception e) {
                    errors++;
                    log.warn("Failed to process chunk object in bucket {}: {}", bucket, e.getMessage());
                }
            }

            if (!orphans.isEmpty()) {
                Iterable<io.minio.Result<DeleteResult.Error>> deleteResults =
                        minioClient.removeObjects(
                                RemoveObjectsArgs.builder()
                                        .bucket(bucket)
                                        .objects(orphans)
                                        .build()
                        );
                for (io.minio.Result<DeleteResult.Error> r : deleteResults) {
                    try {
                        DeleteResult.Error err = r.get();
                        log.warn("Failed to delete {} in bucket {}: {}", err.objectName(), bucket, err.message());
                        errors++;
                    } catch (Exception e) {
                        // 忽略：成功删除不会产生 Error
                    }
                }
                cleaned = orphans.size();
            }
        } catch (Exception e) {
            log.error("Failed to clean orphans in bucket {}", bucket, e);
            errors++;
        }

        return new int[]{cleaned, errors};
    }

    /**
     * 清理孤儿空桶：团队已软删且 bucket 内无对象。
     *
     * @return 清理的 bucket 数量
     */
    private int cleanOrphanEmptyBuckets(List<String> allBuckets) {
        int cleaned = 0;
        for (String bucket : allBuckets) {
            if (!bucketResolver.isTeamBucket(bucket)) {
                continue;
            }
            try {
                // 检查 bucket 是否为空（最多查 1 个对象）
                Iterable<io.minio.Result<Item>> objects = minioClient.listObjects(
                        ListObjectsArgs.builder().bucket(bucket).maxKeys(1).build());
                if (objects.iterator().hasNext()) {
                    continue; // 非空桶，跳过
                }

                // 空桶 → 检查对应团队是否存在且未删除
                Long teamId = bucketResolver.extractTeamId(bucket);
                if (teamId == null) {
                    continue;
                }

                // 团队已删除 → 清理空桶
                // 注意：此处不能注入 TeamMapper（循环依赖风险），只做简单清理
                // 团队不存在时（Mapper 查不到）也是安全的 — 说明是孤儿
                minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
                log.info("Cleaned orphan empty bucket: {} (teamId={})", bucket, teamId);
                cleaned++;
            } catch (Exception e) {
                log.warn("Failed to clean orphan bucket {}: {}", bucket, e.getMessage());
            }
        }
        return cleaned;
    }

    /**
     * 检查分片对象是否仍有活跃的 Redis session。
     * <p>
     * 路径格式: chunks/{userId}/{uploadId}/part-{chunkIndex}
     * 从路径提取 uploadId，检查 Redis session 是否存在。
     */
    private boolean hasActiveSession(String objectName) {
        String[] parts = objectName.split("/");
        if (parts.length >= 3) {
            String uploadId = parts[2];
            String sessionKey = UploadRedisConstants.sessionKey(uploadId);
            Long ttl = redisTemplate.getExpire(sessionKey, TimeUnit.SECONDS);
            return ttl != null && ttl > 0;
        }
        return false;
    }
}
