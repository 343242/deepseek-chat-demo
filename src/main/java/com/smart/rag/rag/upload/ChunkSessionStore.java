package com.smart.rag.rag.upload;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分片上传 Redis 会话存储。
 * <p>
 * 拥有全部会话 Hash 的读写逻辑与会话字段名常量；
 * 解析失败统一抛 {@link ServiceException}（UPLOAD_SESSION_NOT_FOUND），
 * 绝不让 {@link NumberFormatException} 裸抛导致 500。
 */
public class ChunkSessionStore {

    // ==================== 会话 Hash 字段名常量 ====================

    public static final String FIELD_FILE_CHECKSUM = "fileChecksum";
    public static final String FIELD_FILE_NAME = "fileName";
    public static final String FIELD_FILE_SIZE = "fileSize";
    public static final String FIELD_MIME_TYPE = "mimeType";
    public static final String FIELD_CHUNK_SIZE = "chunkSize";
    public static final String FIELD_TOTAL_CHUNKS = "totalChunks";
    public static final String FIELD_USER_ID = "userId";
    public static final String FIELD_TEAM_ID = "teamId";
    public static final String FIELD_BUCKET = "bucket";
    public static final String FIELD_OBJECT_NAME = "objectName";
    public static final String FIELD_CREATED_AT = "createdAt";
    public static final String FIELD_REPLACE_DOCUMENT_ID = "replaceDocumentId";

    private final StringRedisTemplate redisTemplate;

    public ChunkSessionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== 会话读写 ====================

    /**
     * 加载会话 Hash；不存在返回空 Map（不可变）。
     */
    public Map<String, String> load(String uploadId) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(UploadRedisConstants.sessionKey(uploadId));
        return toStringMap(raw);
    }

    /**
     * 写入会话字段并设置统一 TTL，同时为 parts key 设置 TTL。
     */
    public void save(String uploadId, Map<String, String> fields) {
        String sessionKey = UploadRedisConstants.sessionKey(uploadId);
        redisTemplate.opsForHash().putAll(sessionKey, fields);
        redisTemplate.expire(sessionKey, UploadRedisConstants.SESSION_TTL);
        redisTemplate.expire(UploadRedisConstants.partsKey(uploadId), UploadRedisConstants.SESSION_TTL);
    }

    /**
     * 写入反向索引（续传查找）。
     */
    public void putFileIndex(Long userId, String fileChecksum, String uploadId) {
        redisTemplate.opsForValue().set(
                UploadRedisConstants.fileKey(userId, fileChecksum),
                uploadId, UploadRedisConstants.SESSION_TTL);
    }

    /**
     * 读取反向索引指向的 uploadId；不存在返回 null。
     */
    public @Nullable String findResumableUploadId(Long userId, String fileChecksum) {
        return redisTemplate.opsForValue().get(UploadRedisConstants.fileKey(userId, fileChecksum));
    }

    /**
     * 删除反向索引（会话已被清理时）。
     */
    public void deleteFileIndex(Long userId, String fileChecksum) {
        redisTemplate.delete(UploadRedisConstants.fileKey(userId, fileChecksum));
    }

    /**
     * 会话是否仍存在（非空判断）。
     */
    public boolean exists(String uploadId) {
        return !load(uploadId).isEmpty();
    }

    // ==================== parts Hash 操作 ====================

    /** 指定分片是否已上传（幂等检查） */
    public boolean hasPart(String uploadId, int chunkIndex) {
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(
                UploadRedisConstants.partsKey(uploadId), String.valueOf(chunkIndex)));
    }

    /** 已上传分片索引（升序，排除 __merging 标记） */
    public Set<Integer> uploadedPartIndexes(String uploadId) {
        Set<Object> keys = redisTemplate.opsForHash().keys(UploadRedisConstants.partsKey(uploadId));
        return keys.stream()
                .map(Object::toString)
                .filter(k -> !UploadRedisConstants.MERGING_FIELD.equals(k))
                .map(Integer::parseInt)
                .collect(Collectors.toSet());
    }

    /** __merging 合并锁标记是否存在 */
    public boolean isMerging(String uploadId) {
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(
                UploadRedisConstants.partsKey(uploadId), UploadRedisConstants.MERGING_FIELD));
    }

    /** 删除 __merging 合并锁标记 */
    public void clearMergingFlag(String uploadId) {
        redisTemplate.opsForHash().delete(UploadRedisConstants.partsKey(uploadId), UploadRedisConstants.MERGING_FIELD);
    }

    // ==================== 清理 ====================

    /**
     * 清理会话、parts 与反向索引。
     * <p>
     * userId 为会话中的字符串形式，安全解析（非法则跳过反向索引清理，不让 500 逃逸）。
     */
    public void cleanup(String uploadId, @Nullable String userIdStr, @Nullable String fileChecksum) {
        redisTemplate.delete(UploadRedisConstants.sessionKey(uploadId));
        redisTemplate.delete(UploadRedisConstants.partsKey(uploadId));
        Long userId = parseNullableLong(userIdStr, FIELD_USER_ID);
        if (userId != null && fileChecksum != null) {
            deleteFileIndex(userId, fileChecksum);
        }
    }

    // ==================== R1-M2: session 字段安全解析 ====================

    /** 解析 session 中必填 Long 字段；缺失/非法 → ServiceException(UPLOAD_SESSION_NOT_FOUND) */
    public static long parseSessionLong(Map<String, String> session, String key) {
        try {
            return Long.parseLong(session.get(key));
        } catch (NumberFormatException e) {
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND, "上传会话字段非法: " + key);
        }
    }

    /** 解析 session 中必填 int 字段；缺失/非法 → ServiceException */
    public static int parseSessionInt(Map<String, String> session, String key) {
        try {
            return Integer.parseInt(session.get(key));
        } catch (NumberFormatException e) {
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND, "上传会话字段非法: " + key);
        }
    }

    /** 解析可选 Long（null → null；非 null 但非法 → ServiceException） */
    public static @Nullable Long parseNullableLong(@Nullable String v, String label) {
        if (v == null) return null;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new ServiceException(ServiceErrorCode.UPLOAD_SESSION_NOT_FOUND, "上传会话字段非法: " + label);
        }
    }

    // ==================== 工具方法 ====================

    private static Map<String, String> toStringMap(Map<Object, Object> raw) {
        Map<String, String> result = new HashMap<>(raw.size());
        raw.forEach((k, v) -> result.put(k.toString(), v != null ? v.toString() : ""));
        return Collections.unmodifiableMap(result);
    }
}
