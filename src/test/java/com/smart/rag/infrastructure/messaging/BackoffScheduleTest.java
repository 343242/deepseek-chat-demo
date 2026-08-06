package com.smart.rag.infrastructure.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BackoffSchedule} 单测（design §4.4）：封顶最后一档、配置驱动、默认 16 级。
 */
class BackoffScheduleTest {

    @Test
    @DisplayName("默认 16 级退避表按档位取值")
    void next_returnsConfiguredTier() {
        BackoffSchedule schedule = new BackoffSchedule(BackoffSchedule.DEFAULT_BACKOFF_MS);

        assertEquals(16, schedule.size());
        assertEquals(1000, schedule.next(1));       // 第 1 档 1s
        assertEquals(5000, schedule.next(2));       // 第 2 档 5s
        assertEquals(10000, schedule.next(3));      // 第 3 档 10s
        assertEquals(30000, schedule.next(4));      // 第 4 档 30s
        assertEquals(60000, schedule.next(5));      // 第 5 档 1m
        assertEquals(1_800_000, schedule.next(16)); // 第 16 档 30m
    }

    @Test
    @DisplayName("超出档位封顶最后一档")
    void next_capsAtLastTier() {
        BackoffSchedule schedule = new BackoffSchedule(BackoffSchedule.DEFAULT_BACKOFF_MS);
        assertEquals(1_800_000, schedule.next(17));
        assertEquals(1_800_000, schedule.next(100));
    }

    @Test
    @DisplayName("attempt <= 0 钳制到第一档")
    void next_clampsNonPositiveAttempt() {
        BackoffSchedule schedule = new BackoffSchedule(BackoffSchedule.DEFAULT_BACKOFF_MS);
        assertEquals(1000, schedule.next(0));
        assertEquals(1000, schedule.next(-3));
    }

    @Test
    @DisplayName("配置驱动：自定义表生效（child 2 relay 同 bean 复用）")
    void next_usesConfiguredSchedule() {
        BackoffSchedule schedule = new BackoffSchedule(new long[] {100, 200});
        assertEquals(100, schedule.next(1));
        assertEquals(200, schedule.next(2));
        assertEquals(200, schedule.next(5));   // 封顶
    }

    @Test
    @DisplayName("空/null 配置回退默认表")
    void emptyConfig_fallsBackToDefault() {
        assertEquals(16, new BackoffSchedule(null).size());
        assertEquals(16, new BackoffSchedule(new long[0]).size());
    }

    @Test
    @DisplayName("构造不共享调用方数组（防御性拷贝）")
    void constructorCopiesArray() {
        long[] custom = {1000};
        BackoffSchedule schedule = new BackoffSchedule(custom);
        custom[0] = 9999;
        assertEquals(1000, schedule.next(1));
    }
}
