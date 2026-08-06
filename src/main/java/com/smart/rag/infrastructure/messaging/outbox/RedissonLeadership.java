package com.smart.rag.infrastructure.messaging.outbox;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 持续持锁的 leader election 组件（design §3.1，评审 P0-1/P0-2）。
 * <p>
 * <b>权威判定</b>：{@code volatile boolean leader} 标志（持锁线程获取后置 true、解锁/异常退出置
 * false）是<b>唯一</b> authority——{@link #isLeader()} 只查该标志，<b>不碰
 * {@code RLock.isHeldByCurrentThread()}</b>（该 API 比对当前线程的 threadId，relay drain 跑在
 * scheduler 线程上恒为 false，用它会导致 election 失效或 relay 永不 drain）。
 * <p>
 * <b>锁语义</b>：{@code start()} 时获取并持续持有（{@code tryLock(wait, -1)}，lease=-1 触发
 * Redisson 看门狗每 10s 续约，默认 30s lease）——不是每轮 drain 重新竞拍。leader 崩溃（JVM 死）
 * → 看门狗停止续约 → 锁 ~30s 自动释放 → 其它实例的 tryLock 立即获取，接管延迟 ≤ 看门狗超时。
 * <p>
 * <b>正常 stop()</b>：{@code running=false → interrupt()} → 内层 while 退出 → {@code finally}
 * 中 unlock（修正早期"条件写反永不 unlock"的 bug，follower 无需等看门狗 30s）。unlock 吞
 * {@code IllegalMonitorStateException}（锁已失），恢复中断标志。
 * <p>
 * <b>降级</b>：RedissonClient 为 null（未配置）→ {@code leader=true}（每实例都扫，正确性不变，
 * 仅 DB 压力回升）；tryLock 异常 → 5s 后重试，期间 leader=false。
 * <p>
 * <b>线程</b>：leaderThread 是 daemon + 命名（{@code outbox-relay-leader}）——非 daemon 线程在
 * stop() 未执行（Spring 上下文异常关闭）时阻止 JVM 退出；命名便于线程 dump 排障。
 */
public class RedissonLeadership {

    private static final Logger log = LoggerFactory.getLogger(RedissonLeadership.class);

    private final @Nullable RedissonClient redisson;
    private final String lockKey;
    private final Duration pollInterval;
    private final Duration lockWait;

    private volatile boolean leader = false;
    private volatile boolean running = true;
    private Thread leaderThread;

    public RedissonLeadership(@Nullable RedissonClient redisson,
                              String lockKey,
                              Duration pollInterval) {
        this(redisson, lockKey, pollInterval, Duration.ofSeconds(5));
    }

    RedissonLeadership(@Nullable RedissonClient redisson,
                       String lockKey,
                       Duration pollInterval,
                       Duration lockWait) {
        this.redisson = redisson;
        this.lockKey = lockKey;
        this.pollInterval = pollInterval;
        this.lockWait = lockWait;
    }

    public synchronized void start() {
        if (redisson == null) {
            // 无 Redisson（未配置/测试）→ 降级"每实例都扫"（正确性不变，仅 DB 压力回升）
            leader = true;
            log.info("Redisson unavailable, relay leadership degraded to all-instance scan");
            return;
        }
        leaderThread = Thread.ofPlatform()
            .daemon()
            .unstarted(this::holdLeadership);
        leaderThread.setName("outbox-relay-leader");
        leaderThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (leaderThread != null) {
            leaderThread.interrupt();   // 唤醒 sleep
        }
    }

    /** 唯一权威 leader 标志（tryDrainIfLeader 只查这个，不碰 isHeldByCurrentThread）。 */
    public boolean isLeader() {
        return leader;
    }

    /** 包级可见：测试断言 leader 线程 daemon + 命名。 */
    Thread leaderThread() {
        return leaderThread;
    }

    private void holdLeadership() {
        while (running) {
            RLock lock = redisson.getLock(lockKey);
            try {
                // tryLock 超时判定（评审 P2）：比 lock(-1) 在 Redis 抖动时行为更可预测、可测
                if (!lock.tryLock(lockWait.toMillis(), -1, TimeUnit.MILLISECONDS)) {
                    continue;   // 5s 拿不到 → 重试
                }
                leader = true;
                log.info("Acquired relay leadership: lock={}", lockKey);
                try {
                    while (running) {
                        Thread.sleep(pollInterval.toMillis());
                    }
                } finally {
                    // stop()/异常退出统一走这里解锁（P0-2 修正：不等看门狗 30s）
                    leader = false;
                    unlockQuietly(lock);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // 恢复中断标志
                break;
            } catch (Exception e) {
                // Redis 故障 / lock 抛异常
                leader = false;
                log.warn("Leadership lost, retry in 5s: {}", e.getMessage());
                sleepUninterruptibly(5);
            }
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException e) {
            log.warn("Lock already released: {}", e.getMessage());
        }
    }

    private static void sleepUninterruptibly(long seconds) {
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
