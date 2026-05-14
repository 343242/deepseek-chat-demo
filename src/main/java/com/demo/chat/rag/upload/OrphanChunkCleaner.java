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
 * 定期扫描 MinIO 中所有 bucket 的临时分片对象（{@code chunks/} 前缀），
 * 清理因客户端异常断开或合并失败而遗留的资源。
 * <p>
 * 清理规则：
 * <ol>
 *   <li>MinIO chunks/ 前缀下超过 48h 的临时对象视为孤儿</li>
 *   <li>仍有活跃 Redis session 的分片跳过</li>
 * </ol>
 * <p>
 * 团队 Bucket 的生命周期管理由 {@link com.demo.chat.team.upload.TeamBucketCleaner} 负责。
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

        try {
            List<String> allBuckets = listManagedBuckets();

            int totalCleaned = 0;
            int totalErrors = 0;

            for (String bucket : allBuckets) {
                int[] result = cleanOrphansInBucket(bucket);
                totalCleaned += result[0];
                totalErrors += result[1];
            }

            if (totalCleaned > 0 || totalErrors > 0) {
                log.info("Orphan chunk cleanup finished: chunks={}, errors={}", totalCleaned, totalErrors);
            } else {
                log.debug("Orphan chunk cleanup finished: no orphans found");
            }
        } catch (Exception e) {
            log.error("Orphan chunk cleanup failed", e);
        }
    }

    /**
     * 获取所有需要扫描的 bucket（默认 + 所有 rag-team-*）。
     */
    private List<String> listManagedBuckets() throws Exception {
        return minioClient.listBuckets().stream()
                .map(ListAllMyBucketsResult.Bucket::name)
                .filter(name -> name.equals(bucketResolver.defaultBucket()) || bucketResolver.isTeamBucket(name))
                .toList();
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
                        // 成功删除不会产生 Error，忽略
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
     * 检查分片对象是否仍有活跃的 Redis session。
     * <p>
     * 路径格式: chunks/{userId}/{uploadId}/part-{chunkIndex}
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
