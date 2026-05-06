package com.demo.deepseekchat.security.service;

import com.demo.deepseekchat.security.config.JwtProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class TokenCacheService {

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
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // --- Refresh Token ---

    public void storeRefreshToken(String refreshToken, Long userId) {
        String key = "auth:refresh:" + refreshToken;
        redisTemplate.opsForValue().set(key, String.valueOf(userId),
            jwtProperties.refreshExpiration(), TimeUnit.SECONDS);
    }

    public Long getUserIdByRefreshToken(String refreshToken) {
        String key = "auth:refresh:" + refreshToken;
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : null;
    }

    public void revokeRefreshToken(String refreshToken) {
        redisTemplate.delete("auth:refresh:" + refreshToken);
    }

    // --- Revoke All ---

    public void revokeAllTokens(Long userId) {
        String pattern = jwtProperties.redisPrefix() + userId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
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
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, 300, TimeUnit.SECONDS);
        }
        return count != null ? count : 0;
    }

    public boolean isLoginRateLimited(String ip) {
        String key = "ratelimit:login:" + ip;
        String val = redisTemplate.opsForValue().get(key);
        return val != null && Long.parseLong(val) > 10;
    }

    public long getRemainingLoginAttempts(String ip) {
        String key = "ratelimit:login:" + ip;
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) return 10;
        long count = Long.parseLong(val);
        return Math.max(0, 10 - count);
    }
}
