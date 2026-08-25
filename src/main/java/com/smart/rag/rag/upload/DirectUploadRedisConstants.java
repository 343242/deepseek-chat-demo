package com.smart.rag.rag.upload;

import java.time.Duration;

/**
 * Presigned 直传模块 Redis 常量。
 * <p>
 * 与 {@link UploadRedisConstants}（代理分片路径）隔离：新前缀避免与旧分片会话冲突，
 * 旧路径阶段 3 退役时可直接整体下线。单一数据源，禁止在业务代码中硬编码。
 */
public final class DirectUploadRedisConstants {

    private DirectUploadRedisConstants() {}

    // ---- Key 前缀 ----

    /** 直传会话元数据 Hash：direct:session:{sessionId} */
    public static final String SESSION_PREFIX = "direct:session:";

    /** 反向索引（续传查找既有会话）：direct:file:{userId}:{fileChecksum} → sessionId */
    public static final String FILE_PREFIX = "direct:file:";

    /** direct init 独立限流桶（与 rate:upload:init: 语义不同频度，见 DirectUploadProperties） */
    public static final String RATE_PREFIX = "rate:upload:direct-init:";

    /** commit 状态机 COMMITTING 租约子键：direct:commit-lease:{sessionId} */
    public static final String COMMIT_LEASE_PREFIX = "direct:commit-lease:";

    /**
     * MPU 出生登记簿（ZSET，member=JSON{bucket,objectKey,uploadId}，score=发起时刻 epoch ms）。
     * 替代 ListMultipartUploads 扫描（该 API 在当前镜像+SDK 双重不可用，实测见 S3MultipartGateway）。
     */
    public static final String MPU_REGISTRY_KEY = "direct:mpu";

    /**
     * 登记簿整体 TTL：须大于 cleaner 阈值（24h）+ 扫描间隔（6h）+ 余量，
     * 保证条目不会先于 cleaner 处理而丢失（每次写入刷新）。
     */
    public static final Duration MPU_REGISTRY_TTL = Duration.ofHours(48);

    // ---- TTL ----

    /** 会话/反向索引 TTL（固定 24h 不续期，与 cleaner 阈值对齐，见设计文档） */
    public static final Duration SESSION_TTL = Duration.ofHours(24);

    /** 限流窗口 */
    public static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    // ---- Hash field 名 ----

    public static final String FIELD_STATUS = "status";
    public static final String FIELD_DOCUMENT_ID = "documentId";
    /** commit 成功时的响应状态（PROCESSING / PENDING_APPROVAL），幂等回查重建响应用 */
    public static final String FIELD_RESULT_STATUS = "resultStatus";
    public static final String FIELD_MODE = "mode";
    public static final String FIELD_FILE_CHECKSUM = "fileChecksum";
    public static final String FIELD_FILE_NAME = "fileName";
    public static final String FIELD_FILE_SIZE = "fileSize";
    public static final String FIELD_MIME_TYPE = "mimeType";
    public static final String FIELD_BUCKET = "bucket";
    public static final String FIELD_OBJECT_KEY = "objectKey";
    public static final String FIELD_UPLOAD_ID = "uploadId";
    public static final String FIELD_CHUNK_SIZE = "chunkSize";
    public static final String FIELD_TOTAL_CHUNKS = "totalChunks";
    public static final String FIELD_USER_ID = "userId";
    public static final String FIELD_TEAM_ID = "teamId";
    public static final String FIELD_REPLACE_DOCUMENT_ID = "replaceDocumentId";
    public static final String FIELD_CREATED_AT = "createdAt";

    // ---- 会话状态机（status 字段取值） ----

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMMITTING = "COMMITTING";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_ABORTED = "ABORTED";

    // ---- 辅助方法 ----

    public static String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    public static String fileKey(Long userId, String fileChecksum) {
        return FILE_PREFIX + userId + ":" + fileChecksum;
    }

    public static String rateKey(Long userId) {
        return RATE_PREFIX + userId;
    }

    public static String commitLeaseKey(String sessionId) {
        return COMMIT_LEASE_PREFIX + sessionId;
    }
}
