package com.demo.deepseekchat.common.snowflake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 自定义雪花算法 ID 生成器。
 *
 * <p>设计思想借鉴百度 uid-generator：
 * <ul>
 *   <li><b>自定义纪元</b>：不固定使用 Twitter 原版的 2010 年，由配置指定，
 *       项目从 2026 年起步可获得完整 69 年寿命</li>
 *   <li><b>datacenter + worker 分离</b>：5 + 5 = 10 位，最多 1024 个实例，
 *       支持容器化/多机房部署时通过环境变量分别指定</li>
 *   <li><b>毫秒级溢出回拨容忍</b>：当时钟小幅回拨时等待追上而非直接报错；
 *       超过阈值则抛异常，防止生成重复 ID</li>
 *   <li><b>ReentrantLock</b>：比 synchronized 更公平，避免长期饥饿</li>
 * </ul>
 *
 * <h3>Bit 布局（共 64 位）</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ 1b sign │ 41b timestamp │ 5b datacenterId │ 5b workerId │ 12b seq │
 * └──────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>sign: 固定 0，保证正数</li>
 *   <li>timestamp: 当前毫秒 - 自定义纪元，41 位可用约 69 年</li>
 *   <li>datacenterId: 0~31，数据中心/机房</li>
 *   <li>workerId: 0~31，机器实例</li>
 *   <li>sequence: 同一毫秒内的序列号，0~4095</li>
 * </ul>
 *
 * <p>每毫秒最多生成 4096 个 ID，单机每秒 409.6 万 ID。
 */
public class SnowflakeIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    // ==================== Bit 常量 ====================
    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);         // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS); // 31
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);           // 4095

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                              // 12
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;         // 17
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22

    // ==================== 实例字段 ====================
    private final long epochMillis;
    private final long datacenterId;
    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param epochMillis  自定义纪元（毫秒）
     * @param datacenterId 数据中心 ID (0~31)
     * @param workerId    工作机器 ID (0~31)
     */
    public SnowflakeIdGenerator(long epochMillis, long datacenterId, long workerId) {
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    "datacenterId 范围 0~" + MAX_DATACENTER_ID + "，当前: " + datacenterId);
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId 范围 0~" + MAX_WORKER_ID + "，当前: " + workerId);
        }

        this.epochMillis = epochMillis;
        this.datacenterId = datacenterId;
        this.workerId = workerId;

        log.info("SnowflakeIdGenerator 初始化: epoch={}, datacenterId={}, workerId={}",
                epochMillis, datacenterId, workerId);
    }

    /**
     * 生成下一个唯一 ID（线程安全）。
     *
     * @return 64 位 long 型 ID
     */
    public long nextId() {
        lock.lock();
        try {
            long currentTimestamp = timeMillis();

            // 时钟回拨处理
            if (currentTimestamp < lastTimestamp) {
                long offset = lastTimestamp - currentTimestamp;
                if (offset <= 5) {
                    // 小幅回拨（≤5ms），等待追上
                    log.warn("时钟小幅回拨 {}ms，等待恢复", offset);
                    try {
                        Thread.sleep(offset);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("等待时钟恢复时被中断", e);
                    }
                    currentTimestamp = timeMillis();
                }
                if (currentTimestamp < lastTimestamp) {
                    throw new IllegalStateException(
                            "时钟回拨超过容忍范围: 回拨 " + (lastTimestamp - currentTimestamp) + "ms");
                }
            }

            if (currentTimestamp == lastTimestamp) {
                // 同一毫秒内递增序列
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0) {
                    // 序列溢出，等到下一毫秒
                    currentTimestamp = waitNextMillis(lastTimestamp);
                }
            } else {
                // 新的一毫秒，序列归零
                sequence = 0L;
            }

            lastTimestamp = currentTimestamp;

            return ((currentTimestamp - epochMillis) << TIMESTAMP_SHIFT)
                    | (datacenterId << DATACENTER_ID_SHIFT)
                    | (workerId << WORKER_ID_SHIFT)
                    | sequence;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从 ID 中解析出生成时间戳（毫秒）。
     * 用于调试和排查。
     */
    public long extractTimestamp(long id) {
        return (id >> TIMESTAMP_SHIFT) + epochMillis;
    }

    /**
     * 从 ID 中解析 datacenterId。
     */
    public long extractDatacenterId(long id) {
        return (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
    }

    /**
     * 从 ID 中解析 workerId。
     */
    public long extractWorkerId(long id) {
        return (id >> WORKER_ID_SHIFT) & MAX_WORKER_ID;
    }

    // ==================== 内部工具 ====================

    private long timeMillis() {
        return System.currentTimeMillis();
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = timeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = timeMillis();
        }
        return timestamp;
    }
}
