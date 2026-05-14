package com.demo.chat.rag.upload;

import com.demo.chat.rag.config.MinioProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Bucket 解析器。
 * <p>
 * 封装 bucket 选择逻辑：teamId 为 null 时返回个人文档默认 bucket，
 * 否则返回团队专属 bucket（{@code rag-team-{teamId}}）。
 * <p>
 * 上传策略、定时任务等统一通过此类获取 bucket 名，
 * 避免各处硬编码命名规则。
 */
@Component
public class BucketResolver {

    private static final String TEAM_BUCKET_PREFIX = "rag-team-";

    private final MinioProperties minioProperties;

    public BucketResolver(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
    }

    /**
     * 根据团队 ID 解析目标 bucket。
     *
     * @param teamId 团队 ID，null 表示个人文档
     * @return 目标 bucket 名称
     */
    public String resolve(@Nullable Long teamId) {
        return teamId == null ? defaultBucket() : TEAM_BUCKET_PREFIX + teamId;
    }

    /**
     * 获取个人文档默认 bucket 名。
     */
    public String defaultBucket() {
        return minioProperties.getBucket();
    }

    /**
     * 判断是否为团队 bucket。
     */
    public boolean isTeamBucket(@Nullable String bucket) {
        return bucket != null && bucket.startsWith(TEAM_BUCKET_PREFIX);
    }

    /**
     * 从团队 bucket 名中提取 teamId。
     *
     * @return teamId，非团队 bucket 返回 null
     */
    public @Nullable Long extractTeamId(@Nullable String bucket) {
        if (!isTeamBucket(bucket)) {
            return null;
        }
        try {
            return Long.parseLong(bucket.substring(TEAM_BUCKET_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
