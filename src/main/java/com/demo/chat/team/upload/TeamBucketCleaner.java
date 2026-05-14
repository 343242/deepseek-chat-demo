package com.demo.chat.team.upload;

import com.demo.chat.common.team.TeamStatusService;
import com.demo.chat.rag.upload.BucketResolver;
import io.minio.*;
import io.minio.messages.Item;
import io.minio.messages.ListAllMyBucketsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 团队 Bucket 生命周期管理定时任务。
 * <p>
 * 定期扫描 MinIO 中的团队 bucket，清理已解散团队的孤儿空桶。
 * <p>
 * 清理条件（同时满足）：
 * <ol>
 *   <li>bucket 名匹配 {@code rag-team-*}</li>
 *   <li>bucket 内无任何对象（空桶）</li>
 *   <li>对应团队不存在或已软删（{@code deleted=1}）</li>
 * </ol>
 * <p>
 * 活跃团队的空桶不会被删除——即使暂时没有文档，
 * 下次上传时也需要 bucket 存在。
 * <p>
 * 孤儿分片的清理由 {@link com.demo.chat.rag.upload.OrphanChunkCleaner} 负责。
 */
@Component
public class TeamBucketCleaner {

    private static final Logger log = LoggerFactory.getLogger(TeamBucketCleaner.class);

    private final MinioClient minioClient;
    private final BucketResolver bucketResolver;
    private final TeamStatusService teamStatusService;

    public TeamBucketCleaner(MinioClient minioClient,
                             BucketResolver bucketResolver,
                             TeamStatusService teamStatusService) {
        this.minioClient = minioClient;
        this.bucketResolver = bucketResolver;
        this.teamStatusService = teamStatusService;
    }

    /**
     * 每 6 小时扫描一次，延迟 30 分钟启动。
     * <p>
     * 与 OrphanChunkCleaner（延迟 5 分钟）错开，避免同时大量调用 MinIO API。
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void cleanOrphanBuckets() {
        log.info("Team bucket cleanup started");

        try {
            List<String> teamBuckets = minioClient.listBuckets().stream()
                    .map(ListAllMyBucketsResult.Bucket::name)
                    .filter(bucketResolver::isTeamBucket)
                    .toList();

            int cleaned = 0;
            for (String bucket : teamBuckets) {
                if (tryCleanIfOrphan(bucket)) {
                    cleaned++;
                }
            }

            if (cleaned > 0) {
                log.info("Team bucket cleanup finished: orphanBuckets={}", cleaned);
            } else {
                log.debug("Team bucket cleanup finished: no orphans found");
            }
        } catch (Exception e) {
            log.error("Team bucket cleanup failed", e);
        }
    }

    /**
     * 判断并清理单个孤儿桶。
     *
     * @return true = 已清理
     */
    private boolean tryCleanIfOrphan(String bucket) {
        try {
            // 1. 非空桶跳过
            if (isBucketNonEmpty(bucket)) {
                return false;
            }

            // 2. 活跃团队的空桶保留
            Long teamId = bucketResolver.extractTeamId(bucket);
            if (teamId == null || teamStatusService.isTeamActive(teamId)) {
                return false;
            }

            // 3. 团队已不活跃 + 空桶 → 删除
            minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            log.info("Cleaned orphan empty bucket: {} (teamId={})", bucket, teamId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to clean orphan bucket {}: {}", bucket, e.getMessage());
            return false;
        }
    }

    /**
     * 检查 bucket 是否非空（最多查 1 个对象）。
     */
    private boolean isBucketNonEmpty(String bucket) throws Exception {
        Iterable<io.minio.Result<Item>> objects = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucket).maxKeys(1).build());
        return objects.iterator().hasNext();
    }
}
