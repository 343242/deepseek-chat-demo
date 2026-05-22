package com.smart.rag.rag.upload;

import java.time.Duration;

/**
 * 分片上传模块 Redis 常量。
 * <p>
 * 单一数据源：所有 key 前缀和 TTL 统一在此定义，禁止在业务代码中硬编码。
 */
public final class UploadRedisConstants {

    private UploadRedisConstants() {}

    // ---- Key 前缀 ----

    /** 上传会话元数据 Hash */
    public static final String SESSION_PREFIX = "upload:session:";

    /** 分片状态 + ETag Hash（含 __merging 合并锁标记） */
    public static final String PARTS_PREFIX = "upload:parts:";

    /** 反向索引（续传查找）：upload:file:{userId}:{fileMd5} → uploadId */
    public static final String FILE_PREFIX = "upload:file:";

    // ---- TTL ----

    /** 所有上传相关 key 的统一 TTL */
    public static final Duration SESSION_TTL = Duration.ofHours(24);

    // ---- 速率限制 ----

    /** init 端点限流 key 前缀 */
    public static final String RATE_PREFIX = "rate:upload:init:";

    /** init 端点限流窗口 */
    public static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** init 端点限流上限（每用户每窗口） */
    public static final int RATE_LIMIT = 10;

    // ---- Hash field 名 ----

    /** parts Hash 中合并锁标记的 field 名 */
    public static final String MERGING_FIELD = "__merging";

    // ---- 辅助方法 ----

    public static String sessionKey(String uploadId) {
        return SESSION_PREFIX + uploadId;
    }

    public static String partsKey(String uploadId) {
        return PARTS_PREFIX + uploadId;
    }

    public static String fileKey(Long userId, String fileMd5) {
        return FILE_PREFIX + userId + ":" + fileMd5;
    }

    public static String rateKey(Long userId) {
        return RATE_PREFIX + userId;
    }
}
