package com.smart.rag.rag.upload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.rag.upload.s3.S3MultipartGateway;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_CHECKSUM;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_NAME;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_FILE_SIZE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_MIME_TYPE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_CHUNK_SIZE;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_TOTAL_CHUNKS;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_USER_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_TEAM_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_BUCKET;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_OBJECT_KEY;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_UPLOAD_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_REPLACE_DOCUMENT_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_RESULT_STATUS;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_CREATED_AT;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_DOCUMENT_ID;
import static com.smart.rag.rag.upload.DirectUploadRedisConstants.FIELD_STATUS;

/**
 * Presigned 直传 Redis 会话存储。
 * <p>
 * 对齐 {@link ChunkSessionStore} 的字段解析与安全惯例，但结构差异：
 * <ul>
 *   <li>无 parts Hash——分片 ETag 由前端回传（SDK listParts 构建器缺陷 + 镜像 ListMultipartUploads
 *       双重不可用，均实测，见 {@link S3MultipartGateway}），本地差集模式；</li>
 *   <li>status 字段驱动 commit 状态机（ACTIVE/COMMITTING/COMMITTED/ABORTED），
 *       CAS 抢占与租约由 {@code direct_commit_acquire.lua} 原子完成；</li>
 *   <li>MPU 出生登记簿（ZSET）替代 ListMultipartUploads 扫描：create 登记 / complete+abort 注销 /
 *       cleaner 按阈值取超龄项 abort（幂等）。</li>
 * </ul>
 * 会话 TTL 固定 24h 不随访问续期（与 cleaner 阈值对齐，见设计文档「Redis 会话」）。
 */
public class DirectUploadSessionStore {

    /** commit 状态机 CAS 脚本（见 resources/scripts/direct_commit_acquire.lua） */
    private static final DefaultRedisScript<List> COMMIT_ACQUIRE_SCRIPT;

    /** MPU 出生登记簿成员（JSON 编码，防 objectKey 中分隔符碰撞） */
    public record MpuEntry(@JsonProperty("b") String bucket,
                           @JsonProperty("o") String objectKey,
                           @JsonProperty("u") String uploadId) {}

    static {
        COMMIT_ACQUIRE_SCRIPT = new DefaultRedisScript<>();
        COMMIT_ACQUIRE_SCRIPT.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("scripts/direct_commit_acquire.lua")));
        COMMIT_ACQUIRE_SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate redisTemplate;

    public DirectUploadSessionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== 会话读写 ====================

    /** 加载会话 Hash；不存在返回空 Map（不可变）。 */
    public Map<String, String> load(String sessionId) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(DirectUploadRedisConstants.sessionKey(sessionId));
        Map<String, String> result = new HashMap<>(raw.size());
        raw.forEach((k, v) -> result.put(k.toString(), v != null ? v.toString() : ""));
        return Collections.unmodifiableMap(result);
    }

    /** 写入会话字段并设置固定 TTL（不随访问续期）。 */
    public void save(String sessionId, Map<String, String> fields) {
        String sessionKey = DirectUploadRedisConstants.sessionKey(sessionId);
        redisTemplate.opsForHash().putAll(sessionKey, fields);
        redisTemplate.expire(sessionKey, DirectUploadRedisConstants.SESSION_TTL);
    }

    /** 单字段写入（documentId 回写、状态翻转用；刷新 TTL 防对已删会话的迟到写复活无 TTL 键）。 */
    public void putField(String sessionId, String field, String value) {
        String sessionKey = DirectUploadRedisConstants.sessionKey(sessionId);
        redisTemplate.opsForHash().put(sessionKey, field, value);
        redisTemplate.expire(sessionKey, DirectUploadRedisConstants.SESSION_TTL);
    }

    /** 会话是否仍存在。 */
    public boolean exists(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(DirectUploadRedisConstants.sessionKey(sessionId)));
    }

    // ==================== 反向索引（续传查找） ====================

    public void putFileIndex(Long userId, String fileChecksum, String sessionId) {
        redisTemplate.opsForValue().set(
                DirectUploadRedisConstants.fileKey(userId, fileChecksum),
                sessionId, DirectUploadRedisConstants.SESSION_TTL);
    }

    public @Nullable String findResumableSessionId(Long userId, String fileChecksum) {
        return redisTemplate.opsForValue().get(DirectUploadRedisConstants.fileKey(userId, fileChecksum));
    }

    public void deleteFileIndex(Long userId, String fileChecksum) {
        redisTemplate.delete(DirectUploadRedisConstants.fileKey(userId, fileChecksum));
    }

    // ==================== commit 状态机 ====================

    /** commit 抢占结果（对应 Lua 返回码） */
    public enum CommitAcquire {
        /** ACTIVE → COMMITTING 抢占成功 */
        ACQUIRED,
        /** 已 COMMITTED：读 documentId 幂等回查 */
        ALREADY_COMMITTED,
        /** COMMITTING 且租约存活：冲突，前端稍后重试 */
        CONFLICT,
        /** COMMITTING 且租约过期：崩溃残留，接管续走 */
        TAKEOVER,
        /** 会话不存在 / ABORTED */
        REJECTED
    }

    /**
     * 原子执行 commit 状态机 CAS（ACTIVE 抢占 / COMMITTED 回查 / 租约冲突 / 过期接管 / 拒绝）。
     */
    public CommitAcquire acquireForCommit(String sessionId, Duration leaseTtl) {
        List<?> result = redisTemplate.execute(COMMIT_ACQUIRE_SCRIPT,
                List.of(DirectUploadRedisConstants.sessionKey(sessionId),
                        DirectUploadRedisConstants.commitLeaseKey(sessionId)),
                String.valueOf(leaseTtl.toSeconds()));
        if (result == null || result.isEmpty()) {
            return CommitAcquire.REJECTED;
        }
        int code = ((Number) result.get(0)).intValue();
        return switch (code) {
            case 1 -> CommitAcquire.ACQUIRED;
            case 2 -> CommitAcquire.ALREADY_COMMITTED;
            case 3 -> CommitAcquire.CONFLICT;
            case 4 -> CommitAcquire.TAKEOVER;
            default -> CommitAcquire.REJECTED;
        };
    }

    /**
     * 成功收尾：documentId / resultStatus 与 COMMITTED 同一条 HSET 原子写入
     * （任何时序下幂等回查都有确定结果），随后释放租约（终态无需租约保护）。
     */
    public void markCommitted(String sessionId, Long documentId, String resultStatus) {
        String sessionKey = DirectUploadRedisConstants.sessionKey(sessionId);
        redisTemplate.opsForHash().putAll(sessionKey, Map.of(
                FIELD_DOCUMENT_ID, String.valueOf(documentId),
                FIELD_RESULT_STATUS, resultStatus,
                FIELD_STATUS, DirectUploadRedisConstants.STATUS_COMMITTED));
        redisTemplate.expire(sessionKey, DirectUploadRedisConstants.SESSION_TTL);
        redisTemplate.delete(DirectUploadRedisConstants.commitLeaseKey(sessionId));
    }

    /** 中途失败回退 ACTIVE（允许重试），并释放租约。 */
    public void rollbackToActive(String sessionId) {
        String sessionKey = DirectUploadRedisConstants.sessionKey(sessionId);
        redisTemplate.opsForHash().put(sessionKey,
                FIELD_STATUS, DirectUploadRedisConstants.STATUS_ACTIVE);
        redisTemplate.expire(sessionKey, DirectUploadRedisConstants.SESSION_TTL);
        redisTemplate.delete(DirectUploadRedisConstants.commitLeaseKey(sessionId));
    }

    /** 标记 ABORTED（终态；随后的 cleanup 会整体删除会话）。 */
    public void markAborted(String sessionId) {
        String sessionKey = DirectUploadRedisConstants.sessionKey(sessionId);
        redisTemplate.opsForHash().put(sessionKey,
                FIELD_STATUS, DirectUploadRedisConstants.STATUS_ABORTED);
        redisTemplate.expire(sessionKey, DirectUploadRedisConstants.SESSION_TTL);
        redisTemplate.delete(DirectUploadRedisConstants.commitLeaseKey(sessionId));
    }

    // ==================== 限流 ====================

    /**
     * direct init 独立限流桶：INCR + 首次 EXPIRE 原子化。
     *
     * @return 当前窗口内计数
     */
    public long incrRateLimit(Long userId) {
        Long count = redisTemplate.execute(RATE_INCR_SCRIPT,
                List.of(DirectUploadRedisConstants.rateKey(userId)),
                String.valueOf(DirectUploadRedisConstants.RATE_WINDOW.getSeconds()));
        return count != null ? count : 0L;
    }

    private static final DefaultRedisScript<Long> RATE_INCR_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return c", Long.class);

    // ==================== MPU 出生登记簿 ====================

    /**
     * 登记 MPU 出生（score=发起时刻 epoch ms）。key 整体 TTL 48h，每次写入刷新——
     * cleaner 阈值 24h + 6h 扫描间隔 + 余量 < 48h，条目不会先于 cleaner 处理而丢失。
     */
    public void registerMpu(String bucket, String objectKey, String uploadId) {
        redisTemplate.opsForZSet().add(DirectUploadRedisConstants.MPU_REGISTRY_KEY,
                mpuMember(bucket, objectKey, uploadId), System.currentTimeMillis());
        redisTemplate.expire(DirectUploadRedisConstants.MPU_REGISTRY_KEY, DirectUploadRedisConstants.MPU_REGISTRY_TTL);
    }

    /** 注销 MPU（complete / abort / cleaner 处理后）。 */
    public void unregisterMpu(String bucket, String objectKey, String uploadId) {
        redisTemplate.opsForZSet().remove(DirectUploadRedisConstants.MPU_REGISTRY_KEY,
                mpuMember(bucket, objectKey, uploadId));
    }

    /** 取发起时刻早于 cutoff 的全部登记项（cleaner 用）。 */
    public List<MpuEntry> listMpusOlderThan(long cutoffEpochMillis) {
        var members = redisTemplate.opsForZSet().rangeByScore(
                DirectUploadRedisConstants.MPU_REGISTRY_KEY, 0, cutoffEpochMillis);
        if (members == null) {
            return List.of();
        }
        return members.stream()
                .map(DirectUploadSessionStore::parseMpuMember)
                .filter(e -> e != null)
                .toList();
    }

    private static String mpuMember(String bucket, String objectKey, String uploadId) {
        return "{\"b\":\"" + jsonEscape(bucket) + "\",\"o\":\"" + jsonEscape(objectKey)
                + "\",\"u\":\"" + jsonEscape(uploadId) + "\"}";
    }

    private static @Nullable MpuEntry parseMpuMember(String member) {
        try {
            var node = JSON.readTree(member);
            if (node == null || node.path("b").asText().isEmpty()
                    || node.path("o").asText().isEmpty() || node.path("u").asText().isEmpty()) {
                return null;
            }
            return new MpuEntry(node.path("b").asText(), node.path("o").asText(), node.path("u").asText());
        } catch (IOException e) {
            return null;
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ==================== 清理 ====================

    /** 清理会话、租约与反向索引（commit/abort 成功后调用）。 */
    public void cleanup(String sessionId, @Nullable Long userId, @Nullable String fileChecksum) {
        redisTemplate.delete(DirectUploadRedisConstants.sessionKey(sessionId));
        redisTemplate.delete(DirectUploadRedisConstants.commitLeaseKey(sessionId));
        if (userId != null && fileChecksum != null) {
            deleteFileIndex(userId, fileChecksum);
        }
    }

    // ==================== 字段安全解析（对齐 ChunkSessionStore 惯例） ====================

    public static long parseSessionLong(Map<String, String> session, String key) {
        try {
            return Long.parseLong(session.get(key));
        } catch (NumberFormatException e) {
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND, "直传会话字段非法: " + key);
        }
    }

    public static int parseSessionInt(Map<String, String> session, String key) {
        try {
            return Integer.parseInt(session.get(key));
        } catch (NumberFormatException e) {
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND, "直传会话字段非法: " + key);
        }
    }

    public static @Nullable Long parseNullableLong(@Nullable String v, String label) {
        if (v == null || v.isEmpty()) return null;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND, "直传会话字段非法: " + label);
        }
    }

    public static @Nullable Long sessionTeamId(Map<String, String> session) {
        return parseNullableLong(session.get(FIELD_TEAM_ID), FIELD_TEAM_ID);
    }

    public static @Nullable Long sessionReplaceDocumentId(Map<String, String> session) {
        return parseNullableLong(session.get(FIELD_REPLACE_DOCUMENT_ID), FIELD_REPLACE_DOCUMENT_ID);
    }
}
