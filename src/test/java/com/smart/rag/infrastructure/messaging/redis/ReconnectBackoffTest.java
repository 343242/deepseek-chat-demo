package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReconnectBackoff} 单测（design §3 Redis 故障韧性）：指数退避 + 封顶 + ±20% jitter + 成功即 reset。
 */
class ReconnectBackoffTest {

    private MessagingProperties.ReconnectBackoffConfig config() {
        return new MessagingProperties.ReconnectBackoffConfig(1000, 2.0, 30_000, 0.2);
    }

    @Test
    @DisplayName("指数退避 1s→2s→4s，jitter ±20% 内")
    void exponentialGrowthWithJitter() {
        ReconnectBackoff backoff = new ReconnectBackoff(config());

        long first = backoff.nextSleepMs();
        long second = backoff.nextSleepMs();
        long third = backoff.nextSleepMs();

        assertTrue(first >= 800 && first <= 1200, "first=" + first);      // 1s ±20%
        assertTrue(second >= 1600 && second <= 2400, "second=" + second); // 2s ±20%
        assertTrue(third >= 3200 && third <= 4800, "third=" + third);     // 4s ±20%
    }

    @Test
    @DisplayName("封顶 30s，不再继续放大")
    void capsAtMax() {
        ReconnectBackoff backoff = new ReconnectBackoff(config());
        long value = 0;
        for (int i = 0; i < 20; i++) {
            value = backoff.nextSleepMs();
        }
        // 20 次指数增长远超 30s 上限 → 必须封顶在 30s ±20%
        assertTrue(value >= 24_000 && value <= 36_000, "value=" + value);
    }

    @Test
    @DisplayName("成功即 reset 回初始 1s")
    void resetReturnsToInitial() {
        ReconnectBackoff backoff = new ReconnectBackoff(config());
        backoff.nextSleepMs();
        backoff.nextSleepMs();
        backoff.nextSleepMs();

        backoff.reset();

        long after = backoff.nextSleepMs();
        assertTrue(after >= 800 && after <= 1200, "after=" + after);
    }

    @Test
    @DisplayName("多实例退避不同步（jitter 随机性）")
    void jitterDesynchronizesInstances() {
        ReconnectBackoff a = new ReconnectBackoff(config());
        ReconnectBackoff b = new ReconnectBackoff(config());
        boolean differs = false;
        for (int i = 0; i < 50 && !differs; i++) {
            differs = a.nextSleepMs() != b.nextSleepMs();
        }
        // 50 次采样内两实例至少一次不同（防同步重连风暴的关键性质）
        assertTrue(differs, "jitter 应使多实例退避不同步");
    }
}
