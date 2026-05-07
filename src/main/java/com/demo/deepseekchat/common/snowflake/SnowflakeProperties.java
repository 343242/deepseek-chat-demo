package com.demo.deepseekchat.common.snowflake;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;

/**
 * 自定义雪花算法 ID 生成器配置。
 *
 * <p>参考百度 uid-generator 的核心思想：
 * <ul>
 *   <li>自定义纪元（epoch），延长可用年限</li>
 *   <li>可配的 datacenterId + workerId，支持多实例部署</li>
 *   <li>秒级 vs 毫秒级可选</li>
 * </ul>
 *
 * <p>Bit 布局（64 位）：
 * <pre>
 *  1 bit  sign (固定 0)
 * 41 bits timestamp (ms since epoch → 约 69 年)
 *  5 bits datacenterId (0~31)
 *  5 bits workerId (0~31)
 * 12 bits sequence (0~4095，每毫秒 409.6 万 ID)
 * </pre>
 *
 * @param epoch        自定义纪元，默认 2026-01-01T00:00:00+08:00
 * @param datacenterId 数据中心 ID (0~31)
 * @param workerId    工作机器 ID (0~31)
 */
@ConfigurationProperties(prefix = "app.snowflake")
public record SnowflakeProperties(
    String epoch,
    Integer datacenterId,
    Integer workerId
) {
    /** 默认纪元：2026-01-01 00:00:00 CST */
    private static final String DEFAULT_EPOCH = "2026-01-01T00:00:00+08:00";

    public SnowflakeProperties {
        if (epoch == null || epoch.isBlank()) epoch = DEFAULT_EPOCH;
        if (datacenterId == null) datacenterId = 0;
        if (workerId == null) workerId = 0;
    }

    public long epochMillis() {
        return Instant.parse(epoch).toEpochMilli();
    }
}
