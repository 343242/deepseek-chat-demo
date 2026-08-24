package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 锁等待保险重试（V30 §8-4；路线三下死锁已构造性消除，本层为保险）。
 * <p>
 * 解包 cause 链读取 {@link SQLException#getSQLState()}，<b>仅精确匹配</b>
 * {40P01 死锁 / 40001 序列化失败 / 55P03 lock_not_available} 才重试——
 * 不捕 {@code TransientDataAccessException} 整族（该族含 {@code QueryTimeoutException}：
 * 语句超时被误重试会把对账长投影等故障放大为 300s×N 次，第四轮审查修正）。
 * <p>
 * 重试以<b>整事务为单位</b>：包装的 action 必须是无外部副作用的事务模板调用，重放安全。
 * 退避 1s/2s/4s × U(0.5,1.5) jitter（并发同时失败后固定退避会同步重试、浪费 attempt 预算）。
 * 耗尽后抛出原异常，由 ETL 失败隔离记录 + §6.2 重链接检测次日自愈。
 */
@Component
public class LockRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(LockRetryExecutor.class);

    private static final long[] BACKOFF_SECONDS = {1, 2, 4};

    private final RagEntityProperties properties;

    public LockRetryExecutor(RagEntityProperties properties) {
        this.properties = properties;
    }

    /** Runnable 形态（事务无返回值时用）。 */
    public void execute(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    /**
     * 带返回值形态：重试间返回值被丢弃、以最后一次成功执行为准（失败即抛出，不会留下部分结果）。
     */
    public <T> T execute(Supplier<T> action) {
        int maxAttempts = properties.lockRetryAttempts();
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isLockContention(e) || attempt == maxAttempts) {
                    throw e;
                }
                last = e;
                long backoffMs = backoffMillis(attempt);
                log.warn("Lock contention (SQLState eligible for retry), attempt {}/{} failed, retrying in {}ms: {}",
                        attempt + 1, maxAttempts + 1, backoffMs, e.getMessage());
                sleep(backoffMs);
            }
        }
        throw last;   // unreachable：attempt == maxAttempts 时已在循环内抛出
    }

    /**
     * 谓词：解包 cause 链，仅精确匹配 40P01 / 40001 / 55P03。
     * 包级可见供单测断言（验证 #17）。
     */
    static boolean isLockContention(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if ("40P01".equals(state) || "40001".equals(state) || "55P03".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long backoffMillis(int attempt) {
        long baseSeconds = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
        double jitter = ThreadLocalRandom.current().nextDouble(0.5, 1.5);
        return (long) (baseSeconds * 1000 * jitter);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new org.springframework.dao.DataAccessResourceFailureException(
                    "Lock retry backoff interrupted", e);
        }
    }
}
