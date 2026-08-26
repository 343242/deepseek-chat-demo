package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.rag.upload.s3.S3MultipartGateway;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectsArgs;
import io.minio.messages.DeleteRequest;
import io.minio.messages.DeleteResult;
import io.minio.messages.Item;
import io.minio.messages.ListAllMyBucketsResult;
import org.slf4j.Logger;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Presigned 直传孤儿清理定时任务（对齐 {@link OrphanChunkCleaner} 模式）。
 * <p>
 * 三层防线的第二层（第一层 Redis 会话 TTL 24h；MinIO ILM 实测不可用不计入）：
 * <ol>
 *   <li><b>可见对象</b>：扫 {@code uploads/pending/} 下无活跃会话且创建超 24h 的对象删除。
 *       key 含 sessionId 段（uploads/pending/{userId}/{sessionId}/{name}），判定 O(1)：
 *       提取 sessionId → EXISTS direct:session:{sessionId}；</li>
 *   <li><b>未 Complete MPU（隐藏分片，LIST 不可见）</b>：MPU 出生登记簿
 *       （{@code direct:mpu} ZSET）取发起超 24h 的条目主动 abort（幂等）——
 *       替代 ListMultipartUploads 扫描（该 API 在当前镜像 + SDK 双重不可用，实测）。</li>
 * </ol>
 */
@Component
public class DirectUploadOrphanCleaner {

    private static final Logger log = LoggerFactory.getLogger(DirectUploadOrphanCleaner.class);

    /** 孤儿判定阈值：与直传会话 TTL 对齐（24h，三阈值对齐前提见设计文档「Redis 会话」） */
    static final Duration ORPHAN_AGE = Duration.ofHours(24);

    private static final long CLEANUP_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long INITIAL_DELAY_MS = 8 * 60 * 1000L;

    /**
     * pending 对象路径：uploads/pending/{userId}/{sessionId}/{shortId}_{name}；
     * sessionId 为 36 位 UUID。解析失败保守视为活跃（R1-M4 同款防御）。
     */
    private static final Pattern PENDING_PATH_PATTERN =
            Pattern.compile("^" + Pattern.quote(UploadObjectKeys.PENDING_PREFIX) + "[^/]+/([0-9a-f-]{36})/.+$");

    private final @Nullable StringRedisTemplate redisTemplate;
    private final MinioClient minioClient;
    private final S3MultipartGateway gateway;
    private final BucketResolver bucketResolver;
    private final DirectUploadSessionStore sessionStore;

    @Autowired
    public DirectUploadOrphanCleaner(StringRedisTemplate redisTemplate,
                                     MinioClient minioClient,
                                     S3MultipartGateway gateway,
                                     BucketResolver bucketResolver) {
        this(redisTemplate != null ? new DirectUploadSessionStore(redisTemplate) : null,
                minioClient, gateway, bucketResolver, redisTemplate);
    }

    /** 测试直注构造器（直接给 store，绕过 redisTemplate 组装）。 */
    DirectUploadOrphanCleaner(@Nullable DirectUploadSessionStore sessionStore,
                              MinioClient minioClient,
                              S3MultipartGateway gateway,
                              BucketResolver bucketResolver,
                              @Nullable StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.minioClient = minioClient;
        this.gateway = gateway;
        this.bucketResolver = bucketResolver;
        this.sessionStore = sessionStore != null ? sessionStore
                : new DirectUploadSessionStore(redisTemplate);
    }

    @Scheduled(fixedRate = CLEANUP_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void cleanOrphans() {
        log.info("Direct upload orphan cleanup started");
        try {
            int objectsCleaned = cleanPendingObjects();
            int mpusAborted = abortLeakedMultipartUploads();
            if (objectsCleaned > 0 || mpusAborted > 0) {
                log.info("Direct upload orphan cleanup finished: pendingObjects={}, mpusAborted={}",
                        objectsCleaned, mpusAborted);
            } else {
                log.debug("Direct upload orphan cleanup finished: nothing to clean");
            }
        } catch (Exception e) {
            log.error("Direct upload orphan cleanup failed", e);
        }
    }

    // ==================== 扫描线 1：pending 可见对象 ====================

    private int cleanPendingObjects() {
        int totalCleaned = 0;
        for (String bucket : listManagedBuckets()) {
            totalCleaned += cleanPendingObjectsInBucket(bucket);
        }
        return totalCleaned;
    }

    private List<String> listManagedBuckets() {
        try {
            return minioClient.listBuckets().stream()
                    .map(ListAllMyBucketsResult.Bucket::name)
                    .filter(name -> name.equals(bucketResolver.defaultBucket()) || bucketResolver.isTeamBucket(name))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to list buckets for direct upload cleanup", e);
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "列举存储桶失败", e);
        }
    }

    private int cleanPendingObjectsInBucket(String bucket) {
        int cleaned = 0;
        try {
            Instant threshold = Instant.now().minus(ORPHAN_AGE);
            Iterable<io.minio.Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(UploadObjectKeys.PENDING_PREFIX)
                            .recursive(true)
                            .build());

            List<DeleteRequest.Object> orphans = new ArrayList<>();
            for (io.minio.Result<Item> result : results) {
                try {
                    Item item = result.get();
                    if (item.lastModified() != null && item.lastModified().toInstant().isBefore(threshold)
                            && !hasActiveSession(item.objectName())) {
                        orphans.add(new DeleteRequest.Object(item.objectName()));
                    }
                } catch (Exception e) {
                    log.warn("Failed to process pending object in bucket {}: {}", bucket, e.getMessage());
                }
            }
            if (!orphans.isEmpty()) {
                cleaned = orphans.size() - deleteObjects(bucket, orphans);
            }
        } catch (Exception e) {
            log.error("Failed to clean pending objects in bucket {}", bucket, e);
        }
        return cleaned;
    }

    /** 从 pending 对象名提取 sessionId（第三段）；不匹配预期格式返回 empty（保守视为活跃）。 */
    static Optional<String> extractSessionId(String objectName) {
        Matcher matcher = PENDING_PATH_PATTERN.matcher(objectName);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private boolean hasActiveSession(String objectName) {
        Optional<String> sessionId = extractSessionId(objectName);
        if (sessionId.isEmpty()) {
            return true; // 路径结构与预期不符：保守跳过，不删除
        }
        Long ttl = redisTemplate.getExpire(
                DirectUploadRedisConstants.sessionKey(sessionId.get()), TimeUnit.SECONDS);
        return ttl != null && ttl > 0;
    }

    private int deleteObjects(String bucket, List<DeleteRequest.Object> orphans) {
        int failures = 0;
        Iterable<io.minio.Result<DeleteResult.Error>> deleteResults = minioClient.removeObjects(
                RemoveObjectsArgs.builder().bucket(bucket).objects(orphans).build());
        for (io.minio.Result<DeleteResult.Error> r : deleteResults) {
            try {
                DeleteResult.Error err = r.get();
                log.warn("Failed to delete {} in bucket {}: {}", err.objectName(), bucket, err.message());
                failures++;
            } catch (Exception e) {
                log.debug("removeObjects result iteration ended: bucket={}, err={}", bucket, e.getMessage());
            }
        }
        return failures;
    }

    // ==================== 扫描线 2：泄漏 MPU（出生登记簿驱动） ====================

    /**
     * 对发起超 24h 的未 Complete MPU 主动 abort（幂等）。
     * ILM abort-incomplete 在本镜像不可用（实测），这是 MPU 唯一回收通道。
     */
    private int abortLeakedMultipartUploads() {
        long cutoff = System.currentTimeMillis() - ORPHAN_AGE.toMillis();
        List<DirectUploadSessionStore.MpuEntry> leaked = sessionStore.listMpusOlderThan(cutoff);
        int aborted = 0;
        for (DirectUploadSessionStore.MpuEntry entry : leaked) {
            try {
                gateway.abortMultipartUploadQuietly(entry.bucket(), entry.objectKey(), entry.uploadId());
                sessionStore.unregisterMpu(entry.bucket(), entry.objectKey(), entry.uploadId());
                log.info("Aborted leaked multipart upload: bucket={}, object={}, initiatedBefore={}",
                        entry.bucket(), entry.objectKey(), cutoff);
                aborted++;
            } catch (Exception e) {
                log.error("Failed to abort leaked multipart upload: bucket={}, object={}",
                        entry.bucket(), entry.objectKey(), e);
            }
        }
        return aborted;
    }
}
