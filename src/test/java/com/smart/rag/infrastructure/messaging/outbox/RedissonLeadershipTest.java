package com.smart.rag.infrastructure.messaging.outbox;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedissonLeadership 测试（design §3.1，评审 P0-1/P0-2）——isLeader 由 volatile 标志驱动
 * （非 isHeldByCurrentThread）；正常 stop() 在 finally 中 unlock（不等看门狗 30s）；
 * Redisson null 降级 leader=true；leader 线程 daemon + 命名。
 */
@ExtendWith(MockitoExtension.class)
class RedissonLeadershipTest {

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    private RedissonLeadership leadership(Duration pollInterval) {
        return new RedissonLeadership(redisson, "outbox:relay:leader", pollInterval);
    }

    @Test
    @DisplayName("获取锁 → leader=true（volatile 标志驱动，非 isHeldByCurrentThread）")
    void acquiresLeadershipSetsFlag() throws InterruptedException {
        when(redisson.getLock("outbox:relay:leader")).thenReturn(lock);
        when(lock.tryLock(5000, -1, TimeUnit.MILLISECONDS)).thenReturn(true);

        RedissonLeadership leadership = leadership(Duration.ofMillis(200));
        leadership.start();

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(leadership.isLeader()).isTrue());

        // isLeader 不依赖持锁线程（drain 跑在 scheduler 线程上也能读到 true）
        assertThat(lock.isHeldByCurrentThread()).isFalse();
        leadership.stop();
        leadership.leaderThread().interrupt();
    }

    @Test
    @DisplayName("正常 stop()：finally unlock（不等看门狗 30s）")
    void stopUnlocksInFinally() throws InterruptedException {
        when(redisson.getLock("outbox:relay:leader")).thenReturn(lock);
        when(lock.tryLock(5000, -1, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);   // 真实 RLock 持锁线程判定

        RedissonLeadership leadership = leadership(Duration.ofMillis(50_000));   // 长 poll 防自然退出
        leadership.start();
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(leadership.isLeader()).isTrue());

        long start = System.nanoTime();
        leadership.stop();   // 不等看门狗
        Awaitility.await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertThat(leadership.isLeader()).isFalse());
        verify(lock).unlock();
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(30);
    }

    @Test
    @DisplayName("Redisson null → 降级 leader=true（每实例都扫），stop 不抛")
    void nullRedissonDegradesToLeader() {
        RedissonLeadership leadership = new RedissonLeadership(null, "k", Duration.ofMillis(100));
        leadership.start();
        assertThat(leadership.isLeader()).isTrue();
        leadership.stop();
    }

    @Test
    @DisplayName("tryLock 拿不到锁 → leader=false，重试（最终获取）")
    void lockContentionRetries() throws InterruptedException {
        when(redisson.getLock("outbox:relay:leader")).thenReturn(lock);
        when(lock.tryLock(5000, -1, TimeUnit.MILLISECONDS))
            .thenReturn(false)   // 第一次拿不到
            .thenReturn(true);   // 重试后拿到

        RedissonLeadership leadership = leadership(Duration.ofMillis(200));
        leadership.start();

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(leadership.isLeader()).isTrue());
        leadership.stop();
        leadership.leaderThread().interrupt();
    }

    @Test
    @DisplayName("tryLock 抛异常（Redis 故障）→ leader=false，不崩")
    void lockExceptionKeepsFollower() throws InterruptedException {
        when(redisson.getLock("outbox:relay:leader")).thenReturn(lock);
        doThrow(new RuntimeException("redis down")).when(lock).tryLock(5000, -1, TimeUnit.MILLISECONDS);

        RedissonLeadership leadership = leadership(Duration.ofMillis(100));
        leadership.start();

        Awaitility.await().atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(leadership.isLeader()).isFalse());
        leadership.stop();
        leadership.leaderThread().interrupt();
    }

    @Test
    @DisplayName("leader 线程 daemon + 命名 outbox-relay-leader")
    void leaderThreadIsDaemonAndNamed() throws InterruptedException {
        when(redisson.getLock("outbox:relay:leader")).thenReturn(lock);
        when(lock.tryLock(5000, -1, TimeUnit.MILLISECONDS)).thenReturn(true);

        RedissonLeadership leadership = leadership(Duration.ofMillis(200));
        leadership.start();
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(leadership.isLeader()).isTrue());

        Thread t = leadership.leaderThread();
        assertThat(t).isNotNull();
        assertThat(t.isDaemon()).isTrue();
        assertThat(t.getName()).isEqualTo("outbox-relay-leader");

        leadership.stop();
        t.interrupt();
    }
}
