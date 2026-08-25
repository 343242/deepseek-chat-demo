package com.smart.rag.rag.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Presigned URL 浏览器直传配置属性（app.upload.direct.*）。
 * <p>
 * 三阶段灰度开关（见 docs/design/presigned-direct-upload.md「迁移路径」）：
 * 阶段 1 默认 false——后端端点上线、前端仍走代理路径；阶段 2 置 true；
 * 阶段 3 移除开关。其余参数为直传链路运行时常量，均有安全缺省值。
 */
@Component
@ConfigurationProperties(prefix = "app.upload.direct")
public class DirectUploadProperties {

    /** 灰度开关：false 时直传端点拒绝服务（前端 config 接口回 false，走代理路径） */
    private boolean enabled = false;

    /** presigned URL 有效期：URL 即短期 bearer 能力，过期重签无状态成本 */
    private Duration presignExpiry = Duration.ofMinutes(10);

    /** direct init 独立限流（次/分/用户）。direct 化后每文件一次 init，前端批量 10 文件
     *  单批即打满旧 chunk 会话 10/分 配额必然 429，故独立成桶 */
    private int initRateLimitPerMinute = 30;

    /** 会话 TTL：与 cleaner 两个 24h 阈值（pending 对象、MPU 发起时间）对齐的前提，
     *  固定不随访问续期（见设计文档「Redis 会话」） */
    private Duration sessionTtl = Duration.ofHours(24);

    /** commit 状态机 COMMITTING 短租约：进程崩溃残留由租约超时自愈 */
    private Duration commitLeaseTtl = Duration.ofSeconds(60);

    /** part-urls 单批签发上限（现规格 50MB/5MB 最多 10 片，上限为规格提升预留） */
    private int maxPartsPerBatch = 20;

    /** multipart 触发阈值：≤ 阈值走 single presigned PUT */
    private long multipartThresholdBytes = 5L * 1024 * 1024;

    /** 直传分片大小（固定值，直传路径新约定，非复用 DefaultChunkSizeStrategy 动态分片） */
    private long chunkSizeBytes = 5L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getPresignExpiry() { return presignExpiry; }
    public void setPresignExpiry(Duration presignExpiry) { this.presignExpiry = presignExpiry; }

    public int getInitRateLimitPerMinute() { return initRateLimitPerMinute; }
    public void setInitRateLimitPerMinute(int initRateLimitPerMinute) { this.initRateLimitPerMinute = initRateLimitPerMinute; }

    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }

    public Duration getCommitLeaseTtl() { return commitLeaseTtl; }
    public void setCommitLeaseTtl(Duration commitLeaseTtl) { this.commitLeaseTtl = commitLeaseTtl; }

    public int getMaxPartsPerBatch() { return maxPartsPerBatch; }
    public void setMaxPartsPerBatch(int maxPartsPerBatch) { this.maxPartsPerBatch = maxPartsPerBatch; }

    public long getMultipartThresholdBytes() { return multipartThresholdBytes; }
    public void setMultipartThresholdBytes(long multipartThresholdBytes) { this.multipartThresholdBytes = multipartThresholdBytes; }

    public long getChunkSizeBytes() { return chunkSizeBytes; }
    public void setChunkSizeBytes(long chunkSizeBytes) { this.chunkSizeBytes = chunkSizeBytes; }
}
