package com.smart.rag.infrastructure.web.service;

import com.smart.rag.infrastructure.web.config.JwtProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;

@Service
public class TokenCacheService {

    /**
     * 原子化限流检查+递增：GET 判断 → 超限返回 -1 → 否则 INCR+EXPIRE。
     */
    private static final DefaultRedisScript<Long> CHECK_AND_INCREMENT_SCRIPT =
            new DefaultRedisScript<>(
                    "local val = redis.call('GET', KEYS[1]) " +
                    "if val ~= false and tonumber(val) >= tonumber(ARGV[2]) then " +
                    "  return -1 " +
                    "end " +
                    "local count = redis.call('INCR', KEYS[1]) " +
                    "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
                    "return count", Long.class);

    /** 原子化 refresh token 旋转：GET userId + DEL old token + SREM index */
    private static final DefaultRedisScript<String> ROTATE_REFRESH_SCRIPT =
            new DefaultRedisScript<>(
                    "local val = redis.call('GET', KEYS[1]) " +
                    "if val == false then return nil end " +
                    "redis.call('DEL', KEYS[1]) " +
                    "redis.call('SREM', 'auth:user_refresh:' .. val, ARGV[1]) " +
                    "return val", String.class);

    /**
     * 原子化会话 Token 存储：读取用户状态，仅当未 disabled/deleted 时才写入 access+refresh，
     * 单次 Redis 往返。消除「先写 token 再查状态」的竞态——disabled/deleted 用户不会落任何 token。
     */
    private static final DefaultRedisScript<String> STORE_TOKENS_IF_ACTIVE_SCRIPT =
            new DefaultRedisScript<>(
                    "local status = redis.call('GET', KEYS[2]) " +
                    "if status == 'disabled' or status == 'deleted' then " +
                    "  return status " +
                    "end " +
                    "redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2])) " +
                    "redis.call('SET', KEYS[3], ARGV[3], 'EX', tonumber(ARGV[4])) " +
                    "redis.call('SADD', KEYS[4], ARGV[5]) " +
                    "redis.call('EXPIRE', KEYS[4], tonumber(ARGV[4])) " +
                    "return status", String.class);

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;

    public TokenCacheService(StringRedisTemplate redisTemplate,
                              JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.jwtProperties = jwtProperties;
        this.objectMapper = new ObjectMapper();
    }

    // --- Access Token ---

    public void storeAccessToken(Long userId, String tokenId, List<String> roles) {
        String key = jwtProperties.redisPrefix() + userId + ":" + tokenId;
        Map<String, Object> data = Map.of(
            "roles", roles,
            "createdAt", Instant.now().toString()
        );
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(key, json, jwtProperties.accessExpiration(), TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize token metadata", e);
        }
    }

    public boolean isAccessTokenValid(Long userId, String tokenId) {
        String key = jwtProperties.redisPrefix() + userId + ":" + tokenId;
        return redisTemplate.hasKey(key);
    }

    // --- Refresh Token (SHA-256 hashed) ---

    /**
     * SHA-256 hash of the refresh token, returned as lowercase hex.
     */
    private String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public void storeRefreshToken(String refreshToken, Long userId) {
        String hash = sha256Hex(refreshToken);
        String key = "auth:refresh:" + hash;
        redisTemplate.opsForValue().set(key, String.valueOf(userId),
            jwtProperties.refreshExpiration(), TimeUnit.SECONDS);

        // Add to reverse index: auth:user_refresh:{userId} → Set<sha256Hex>
        String indexKey = "auth:user_refresh:" + userId;
        redisTemplate.opsForSet().add(indexKey, hash);
        redisTemplate.expire(indexKey, jwtProperties.refreshExpiration(), TimeUnit.SECONDS);
    }

    /**
     * 原子化 refresh token 旋转：GET userId + DEL old token + SREM index
     * 使用 Lua 脚本保证原子性，防止并发旋转竞态
     *
     * @return userId if token was valid and revoked, null otherwise
     */
    public Long rotateRefreshToken(String refreshToken) {
        String hash = sha256Hex(refreshToken);
        String key = "auth:refresh:" + hash;

        String result = redisTemplate.execute(
                ROTATE_REFRESH_SCRIPT,
                List.of(key),
                hash
        );
        return result != null ? Long.parseLong(result) : null;
    }

    // --- Revoke All (SCAN-based) ---

    public void revokeAllTokens(Long userId) {
        // 1. Revoke access tokens via SCAN (replaces KEYS)
        String pattern = jwtProperties.redisPrefix() + userId + ":*";
        ScanOptions scanOptions = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
            while (cursor.hasNext()) {
                redisTemplate.delete(cursor.next());
            }
        }

        // 2. Revoke all refresh tokens via reverse index
        String indexKey = "auth:user_refresh:" + userId;
        Set<String> hashes = redisTemplate.opsForSet().members(indexKey);
        if (hashes != null && !hashes.isEmpty()) {
            for (String hash : hashes) {
                redisTemplate.delete("auth:refresh:" + hash);
            }
        }
        redisTemplate.delete(indexKey);
    }

    // --- Permission Cache ---

    public void cacheUserPermissions(Long userId, Set<String> permissions) {
        String key = "auth:perms:" + userId;
        try {
            String json = objectMapper.writeValueAsString(permissions);
            redisTemplate.opsForValue().set(key, json, 300, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize permissions", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Set<String> getUserPermissions(Long userId) {
        String key = "auth:perms:" + userId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Set.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public void evictUserPermissions(Long userId) {
        redisTemplate.delete("auth:perms:" + userId);
    }

    // --- User Status ---

    public void markUserStatus(Long userId, String status) {
        String key = "auth:status:" + userId;
        redisTemplate.opsForValue().set(key, status, 86400, TimeUnit.SECONDS);
    }

    public String getUserStatus(Long userId) {
        return redisTemplate.opsForValue().get("auth:status:" + userId);
    }

    public void clearUserStatus(Long userId) {
        redisTemplate.delete("auth:status:" + userId);
    }

    /**
     * 原子化限流检查+递增（per-IP 滑动窗口，单次 Redis 往返）。
     * <p>
     * 登录与注册共用本方法，通过 {@code keyPrefix} 区分计数器，互不消耗配额。
     *
     * @param ip 客户端 IP
     * @param keyPrefix Redis key 前缀，与 ip 拼接（登录 {@code "ratelimit:login:"} / 注册 {@code "ratelimit:register:"}）
     * @param limit 窗口最大次数；达到即拒绝（本次不递增）
     * @param ttlSec 窗口 TTL（秒）
     * @return 递增后的计数（1..limit）；-1 表示已超限（本次不递增）
     */
    public long checkAndIncrementAttempts(String ip, String keyPrefix, int limit, int ttlSec) {
        String key = keyPrefix + ip;
        Long result = redisTemplate.execute(CHECK_AND_INCREMENT_SCRIPT,
                List.of(key),
                String.valueOf(ttlSec),
                String.valueOf(limit));
        return result != null ? result : 0;
    }


    /**
     * 批量存储会话 Token（access + refresh）+ 用户状态查询。
     * <p>
     * 登录与刷新共用：用 Lua 脚本原子地「读状态 → 仅当未 disabled/deleted 才写 token」，
     * 单次 Redis 往返，消除先写后查的竞态（disabled/deleted 用户不写入任何 token）。
     *
     * @return 用户状态字符串（active 时为 null；disabled/deleted 时返回对应串且不写入 token）
     */
    public String batchStoreTokens(Long userId, String tokenId, List<String> roles,
                                    String refreshToken, long accessExp, long refreshExp) {
        String accessKey = jwtProperties.redisPrefix() + userId + ":" + tokenId;
        String refreshHash = sha256Hex(refreshToken);
        String refreshKey = "auth:refresh:" + refreshHash;
        String userStatusKey = "auth:status:" + userId;
        String refreshIndexKey = "auth:user_refresh:" + userId;

        String accessJson;
        try {
            Map<String, Object> data = Map.of("roles", roles, "createdAt", Instant.now().toString());
            accessJson = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize token metadata", e);
        }

        return redisTemplate.execute(
                STORE_TOKENS_IF_ACTIVE_SCRIPT,
                List.of(accessKey, userStatusKey, refreshKey, refreshIndexKey),
                accessJson,
                String.valueOf(accessExp),
                String.valueOf(userId),
                String.valueOf(refreshExp),
                refreshHash
        );
    }
}
