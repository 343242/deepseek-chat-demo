package com.smart.rag.security.service;

import com.smart.rag.security.config.JwtProperties;
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

    /** 原子化 INCR + 首次 EXPIRE，修复两步 TTL 窗口问题 */
    private static final DefaultRedisScript<Long> INCR_WITH_EXPIRE_SCRIPT =
            new DefaultRedisScript<>(
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

    public Long getUserIdByRefreshToken(String refreshToken) {
        String hash = sha256Hex(refreshToken);
        String key = "auth:refresh:" + hash;
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : null;
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

    // --- Login Rate Limiting ---

    public long incrementLoginAttempts(String ip) {
        String key = "ratelimit:login:" + ip;
        Long count = redisTemplate.execute(INCR_WITH_EXPIRE_SCRIPT,
                List.of(key),
                String.valueOf(300));
        return count != null ? count : 0;
    }

    public boolean isLoginRateLimited(String ip) {
        String key = "ratelimit:login:" + ip;
        String val = redisTemplate.opsForValue().get(key);
        return val != null && Long.parseLong(val) >= 10;
    }

    public long getRemainingLoginAttempts(String ip) {
        String key = "ratelimit:login:" + ip;
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) return 10;
        long count = Long.parseLong(val);
        return Math.max(0, 10 - count);
    }
}
