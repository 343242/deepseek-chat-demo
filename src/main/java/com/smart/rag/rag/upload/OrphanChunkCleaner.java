package com.smart.rag.rag.upload;

import com.smart.rag.team.upload.TeamBucketCleaner;
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
 * 团队 Bucket 的生命周期管理由 {@link TeamBucketCleaner} 负责。
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
     * 分片对象路径格式: chunks/{userId}/{uploadId}/part-{chunkIndex}；
     * uploadId 为 36 位 UUID（hex + 连字符）。R1-M4: 用正则稳健提取，避免依赖固定路径层数。
     */
    private static final java.util.regex.Pattern CHUNK_PATH_PATTERN =
            java.util.regex.Pattern.compile("^chunks/[^/]+/([0-9a-f-]{36})/part-\\d+$");

    /**
     * 从分片对象名提取 uploadId；不匹配预期格式时返回 empty（R1-M4）。
     * package-private 以便单测，纯函数不依赖 Redis。
     */
    static java.util.Optional<String> extractUploadId(String objectName) {
        java.util.regex.Matcher matcher = CHUNK_PATH_PATTERN.matcher(objectName);
        return matcher.matches() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
    }

    /**
     * 检查分片对象是否仍有活跃的 Redis session。
     * <p>
     * R1-M4: 路径不匹配预期格式时返回 {@code true}（视为活跃），
     * 绝不因解析失败而删除存活分片。
     */
    private boolean hasActiveSession(String objectName) {
        java.util.Optional<String> uploadIdOpt = extractUploadId(objectName);
        if (uploadIdOpt.isEmpty()) {
            // 路径结构与预期不符 —— 保守跳过，不删除
            return true;
        }
        String sessionKey = UploadRedisConstants.sessionKey(uploadIdOpt.get());
        Long ttl = redisTemplate.getExpire(sessionKey, TimeUnit.SECONDS);
        return ttl != null && ttl > 0;
    }
}
