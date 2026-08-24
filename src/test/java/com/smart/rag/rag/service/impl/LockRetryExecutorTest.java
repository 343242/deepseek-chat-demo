package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LockRetryExecutor} 单元测试（验证 #17：谓词精度）。
 * <p>
 * 断言：40P01/40001/55P03 触发重试；QueryTimeoutException 及其余非目标异常不触发、直接抛出；
 * 耗尽后抛出原异常。
 * <p>
 * 退避 jitter 会使重试间隔在 0.5~1.5 倍基值浮动——为避免慢测试，attempts 收窄为 1
 * （1s 基值 × [0.5,1.5] ≈ 0.5~1.5s，可接受）。
 */
@DisplayName("LockRetryExecutor — SQLState 精确匹配重试（验证 #17）")
class LockRetryExecutorTest {

    private LockRetryExecutor executorWithAttempts(int attempts) {
        return new LockRetryExecutor(new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7,
                0.5, 0.3, 0.2, true, null, true, 0, attempts, 0, 0, null));
    }

    /** 模拟 MyBatis-Spring 行为：SQLException 包在 DataAccessException cause 链里。 */
    private static RuntimeException wrapped(String sqlState) {
        return new ConcurrencyFailureException("simulated", new SQLException("pg error", sqlState));
    }

    @Test
    @DisplayName("40P01（死锁）→ 重试后成功")
    void deadlock_retried() {
        LockRetryExecutor executor = executorWithAttempts(1);
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw wrapped("40P01");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("40001（序列化失败）/ 55P03（lock_not_available）→ 同样重试")
    void serializationAndLockNotAvailable_retried() {
        assertThat(retryCountFor(executorWithAttempts(1), "40001")).isEqualTo(2);
        assertThat(retryCountFor(executorWithAttempts(1), "55P03")).isEqualTo(2);
    }

    @Test
    @DisplayName("QueryTimeoutException（语句超时）→ 不重试、直接抛出（第四轮修正：不捕整族）")
    void queryTimeout_notRetried() {
        LockRetryExecutor executor = executorWithAttempts(3);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(() -> {
            calls.incrementAndGet();
            throw new QueryTimeoutException("statement timeout",
                    new SQLException("timeout", "57014"));
        })).isInstanceOf(QueryTimeoutException.class);

        assertThat(calls.get()).isEqualTo(1);   // 无重试
    }

    @Test
    @DisplayName("非目标 SQLState（如 42P01 表不存在）→ 不重试、直接抛出")
    void otherSqlState_notRetried() {
        LockRetryExecutor executor = executorWithAttempts(3);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(() -> {
            calls.incrementAndGet();
            throw wrapped("42P01");
        })).isInstanceOf(ConcurrencyFailureException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("无 SQLException cause 的普通异常 → 不重试、直接抛出")
    void plainException_notRetried() {
        LockRetryExecutor executor = executorWithAttempts(3);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("plain");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("重试耗尽 → 抛出最后一次异常（终态交由失败隔离 + §6.2 次日重链接）")
    void exhausted_throwsLast() {
        LockRetryExecutor executor = executorWithAttempts(1);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(() -> {
            calls.incrementAndGet();
            throw wrapped("40P01");
        })).isInstanceOf(ConcurrencyFailureException.class);

        assertThat(calls.get()).isEqualTo(2);   // 初次 + 1 次重试
    }

    private int retryCountFor(LockRetryExecutor executor, String sqlState) {
        AtomicInteger calls = new AtomicInteger();
        try {
            executor.execute(() -> {
                calls.incrementAndGet();
                throw wrapped(sqlState);
            });
        } catch (RuntimeException expected) {
            // 耗尽后抛出
        }
        return calls.get();
    }
}
