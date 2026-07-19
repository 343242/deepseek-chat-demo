package com.smart.rag.user.service;

import com.smart.rag.infrastructure.web.config.JwtProperties;
import com.smart.rag.infrastructure.web.service.TokenCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenCacheService 单元测试")
class TokenCacheServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TokenCacheService tokenCacheService;

    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(
                "test-secret", 3600L, 86400L, "issuer", "auth:access:", false, "Lax"
        );
        // Use reflection to set jwtProperties since @InjectMocks already created the instance
        try {
            var field = TokenCacheService.class.getDeclaredField("jwtProperties");
            field.setAccessible(true);
            field.set(tokenCacheService, jwtProperties);
        } catch (Exception e) {
            fail("Failed to set jwtProperties: " + e.getMessage());
        }
    }

    // ==================== Login Rate Limiting ====================

    @Nested
    @DisplayName("登录限流")
    class LoginRateLimitTests {

        @Test
        @DisplayName("checkAndIncrementAttempts_underLimit: 未超限时返回递增后的计数")
        @SuppressWarnings("unchecked")
        void checkAndIncrementAttempts_underLimit() {
            doReturn(5L).when(redisTemplate).execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    any(Object[].class)
            );

            long count = tokenCacheService.checkAndIncrementAttempts("127.0.0.1", "ratelimit:login:", 10, 300);

            assertEquals(5L, count);
            verify(redisTemplate).execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    eq(java.util.List.of("ratelimit:login:127.0.0.1")),
                    eq("300"),
                    eq("10")
            );
        }

        @Test
        @DisplayName("checkAndIncrementAttempts_atLimit: 超限时返回 -1（本次不递增）")
        @SuppressWarnings("unchecked")
        void checkAndIncrementAttempts_atLimit() {
            doReturn(-1L).when(redisTemplate).execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    any(Object[].class)
            );

            assertEquals(-1L, tokenCacheService.checkAndIncrementAttempts("127.0.0.1", "ratelimit:login:", 10, 300));
        }
    }

    // ==================== Store Access Token ====================

    @Nested
    @DisplayName("存储 Token")
    class StoreTokenTests {

        @Test
        @DisplayName("storeAccessToken_success: 正常存储")
        void storeAccessToken_success() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            tokenCacheService.storeAccessToken(1L, "jti-abc", java.util.List.of("USER", "ADMIN"));

            verify(valueOperations).set(
                    eq("auth:access:1:jti-abc"),
                    anyString(),
                    eq(3600L),
                    any()
            );
        }
    }

    // ==================== Revoke All ====================

    @Nested
    @DisplayName("吊销所有 Token")
    class RevokeAllTests {

        @Test
        @DisplayName("revokeAllTokens_success: 扫描并删除")
        @SuppressWarnings("unchecked")
        void revokeAllTokens_success() {
            // Mock SCAN cursor
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenReturn(true, true, false);
            when(cursor.next()).thenReturn("auth:access:1:token1", "auth:access:1:token2");
            when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

            // Mock refresh token set
            when(redisTemplate.opsForSet()).thenReturn(mock(org.springframework.data.redis.core.SetOperations.class));
            when(redisTemplate.opsForSet().members("auth:user_refresh:1")).thenReturn(Set.of("hash1", "hash2"));

            tokenCacheService.revokeAllTokens(1L);

            verify(redisTemplate).delete("auth:access:1:token1");
            verify(redisTemplate).delete("auth:access:1:token2");
            verify(redisTemplate).delete("auth:refresh:hash1");
            verify(redisTemplate).delete("auth:refresh:hash2");
            verify(redisTemplate).delete("auth:user_refresh:1");
        }
    }
}
