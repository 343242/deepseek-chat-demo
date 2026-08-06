package com.smart.rag.infrastructure.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动期断言（design §10，fail-fast）：违反 maxAttempts<=backoff.size / pelMinIdle>ETL+5min 时启动失败。
 */
class MessagingAutoConfigurationTest {

    private MessagingProperties defaults() {
        return new MessagingProperties("T_", Duration.ofSeconds(30), null, null, null, null, null, null);
    }

    @Test
    @DisplayName("默认配置通过断言")
    void defaultsPass() {
        assertDoesNotThrow(() -> MessagingAutoConfiguration.validateMessagingConfig(defaults()));
    }

    @Test
    @DisplayName("maxAttempts > backoff-ms.size() 启动失败")
    void maxAttemptsExceedsBackoffTiers_fails() {
        MessagingProperties.RedisStreamConfig config = new MessagingProperties.RedisStreamConfig(
            null, null, null, null, null, 0, 0, null, 0, null, null, null, 17, null, null, null);
        MessagingProperties properties = new MessagingProperties(
            "T_", Duration.ofSeconds(30), null, null, null, null, config, null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> MessagingAutoConfiguration.validateMessagingConfig(properties));
        assertTrue(e.getMessage().contains("max-attempts"));
    }

    @Test
    @DisplayName("pelMinIdleMs <= ETL 30min + 5min margin 启动失败")
    void pelMinIdleTooSmall_fails() {
        MessagingProperties.RedisStreamConfig config = new MessagingProperties.RedisStreamConfig(
            null, null, null, null, null, 0, 0, null, 0, Duration.ofMinutes(30), null, null, 0, null, null, null);
        MessagingProperties properties = new MessagingProperties(
            "T_", Duration.ofSeconds(30), null, null, null, null, config, null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> MessagingAutoConfiguration.validateMessagingConfig(properties));
        assertTrue(e.getMessage().contains("pel-min-idle"));
    }

    @Test
    @DisplayName("pelMinIdle = 40min（默认）通过断言（30min + 10min margin）")
    void pelMinIdle40min_passes() {
        assertDoesNotThrow(() -> MessagingAutoConfiguration.validateMessagingConfig(defaults()));
    }
}
