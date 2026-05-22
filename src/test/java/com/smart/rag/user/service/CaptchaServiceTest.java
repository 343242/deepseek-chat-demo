package com.smart.rag.user.service;

import com.smart.rag.exception.RateLimitExceededException;
import com.smart.rag.security.dto.CaptchaResult;
import com.smart.rag.security.service.CaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CaptchaService 单元测试")
class CaptchaServiceTest {

    private CaptchaService captchaService;

    @BeforeEach
    void setUp() {
        // exposeAnswer=false for production-like behavior
        captchaService = new CaptchaService(false);
    }

    // ==================== Generate ====================

    @Nested
    @DisplayName("生成验证码")
    class GenerateTests {

        @Test
        @DisplayName("generate_returnsResult: 返回非 null CaptchaResult")
        void generate_returnsResult() {
            CaptchaResult result = captchaService.generate();

            assertNotNull(result);
            assertNotNull(result.captchaId());
            assertNotNull(result.backgroundImage());
            assertNotNull(result.puzzleImage());
            // exposeAnswer=false, so answer should be null
            assertNull(result.answer());
        }
    }

    // ==================== Validate ====================

    @Nested
    @DisplayName("校验验证码")
    class ValidateTests {

        @Test
        @DisplayName("validate_correctAnswer: 通过验证（容差内）")
        void validate_correctAnswer() {
            // Generate with exposeAnswer=true to know the answer
            CaptchaService devService = new CaptchaService(true);
            CaptchaResult result = devService.generate();

            // Validate with the same service instance (shares cache)
            assertTrue(devService.validate(result.captchaId(), result.answer()));
        }

        @Test
        @DisplayName("validate_wrongAnswer: 验证失败")
        void validate_wrongAnswer() {
            CaptchaService devService = new CaptchaService(true);
            CaptchaResult result = devService.generate();

            // Submit wrong answer (far from correct)
            int wrongAnswer = result.answer() + 100;
            assertFalse(devService.validate(result.captchaId(), wrongAnswer));
        }

        @Test
        @DisplayName("validate_expired: captchaId 过期返回 false")
        void validate_expired() {
            // Use a non-existent captchaId
            assertFalse(captchaService.validate("non-existent-id", 100));
        }

        @Test
        @DisplayName("validate_nullCaptchaId: 返回 false")
        void validate_nullCaptchaId() {
            assertFalse(captchaService.validate(null, 100));
        }
    }

    // ==================== Rate Limit ====================

    @Nested
    @DisplayName("生成频率限制")
    class RateLimitTests {

        @Test
        @DisplayName("checkRateLimit_underLimit: 不抛异常")
        void checkRateLimit_underLimit() {
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 20; i++) {
                    captchaService.checkRateLimit("192.168.1.1");
                }
            });
        }

        @Test
        @DisplayName("checkRateLimit_overLimit: 超限抛 RateLimitExceededException")
        void checkRateLimit_overLimit() {
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 20; i++) {
                    captchaService.checkRateLimit("192.168.1.2");
                }
            });

            // 21st call should exceed limit
            assertThrows(RateLimitExceededException.class,
                    () -> captchaService.checkRateLimit("192.168.1.2"));
        }
    }
}
