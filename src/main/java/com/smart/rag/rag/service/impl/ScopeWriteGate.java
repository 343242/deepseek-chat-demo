package com.smart.rag.rag.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smart.rag.rag.config.RagEntityProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 同 scope 写闸门（V30 §3.6，批量上传对策）。
 * <p>
 * 在<b>开启写事务之前</b>先 acquire per-scope 信号量（permits=1）：批量上传下同 scope 的
 * N 个写事务排队发生在应用内信号量上（零 DB 连接占用），而非 advisory 队列
 * （每排队者占一条 Hikari 连接，dev 池仅 5）——池压力从源头消除。
 * <p>
 * 写路径与删除路径<b>共用</b>同一 per-scope 信号量（§3.6 第八轮：批量/级联删除与批量上传同构）。
 * 闸门位于事务外、不取任何数据库锁，不参与 §3.2.1 的 waits-for 图——零死锁论证不变。
 * <p>
 * {@code tryAcquire} 等待上限（{@code write-gate-wait-millis}，默认 120s）：闸门等待占用
 * etlIo 线程，超时上限防单 scope 大批量独占线程池、饿死其它 scope 的事件消费；超时抛出 →
 * 失败隔离 → 标记不写 → §6.2 次日重链接（与重试耗尽同一兜底通道）。
 */
@Component
public class ScopeWriteGate {

    /** 信号量缓存上限：防 scope 泄漏（§3.6 Caffeine maximumSize 有界）。 */
    private static final long MAX_TRACKED_SCOPES = 10_000;

    private final Cache<String, Semaphore> gates = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_SCOPES)
            .build();

    private final RagEntityProperties properties;

    public ScopeWriteGate(RagEntityProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取 scope 写闸门（上限等待，超时抛出）。
     *
     * @throws WriteGateTimeoutException 等待超时或被中断（走失败隔离 → §6.2 次日重链接）；
     *                                   被中断时恢复线程中断标志后再抛出
     */
    public void tryAcquire(Long userId, @Nullable Long teamId, long waitMillis) {
        Semaphore gate = gates.get(scopeKey(userId, teamId), k -> new Semaphore(1));
        boolean acquired;
        try {
            acquired = gate.tryAcquire(Math.max(0, waitMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WriteGateTimeoutException(
                    "Scope write gate wait interrupted (scope userId=" + userId
                            + ", teamId=" + teamId + ")");
        }
        if (!acquired) {
            throw new WriteGateTimeoutException(
                    "Scope write gate wait timed out after " + waitMillis + "ms (scope userId="
                            + userId + ", teamId=" + teamId + ")——排队走 §6.2 次日重链接兜底");
        }
    }

    /** 释放闸门（与 acquire 同 scope 键；finally 中调用）。 */
    public void release(Long userId, @Nullable Long teamId) {
        Semaphore gate = gates.getIfPresent(scopeKey(userId, teamId));
        if (gate != null) {
            gate.release();
        }
    }

    private static String scopeKey(Long userId, @Nullable Long teamId) {
        return userId + ":" + (teamId != null ? teamId : -1L);
    }

    /** 闸门等待超时（语义：该文档本轮放弃，标记不写 → §6.2 重链接）。 */
    public static class WriteGateTimeoutException extends RuntimeException {
        public WriteGateTimeoutException(String message) {
            super(message);
        }
    }
}
