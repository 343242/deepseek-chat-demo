package com.demo.deepseekchat.common.snowflake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * 雪花算法配置。
 *
 * <p>只负责创建 {@link SnowflakeIdGenerator} 单例 Bean，
 * 不接入 MyBatis-Plus 的任何 ID 生成机制。
 * 业务代码需要显式调用 {@link SnowflakeIdGenerator#nextId()} 设置实体 ID。
 */
@Configuration
@EnableConfigurationProperties(SnowflakeProperties.class)
public class SnowflakeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeConfiguration.class);

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties props) {
        long epochMillis = props.epochMillis();
        log.info("雪花 ID 纪元: {} ({}ms), datacenterId={}, workerId={}",
                props.epoch(), epochMillis, props.datacenterId(), props.workerId());

        long maxLifetimeMs = (1L << 41) - 1;
        double years = maxLifetimeMs / (365.25 * 24 * 3600 * 1000.0);
        Instant expiresAt = Instant.ofEpochMilli(epochMillis + maxLifetimeMs);
        log.info("雪花 ID 可用年限: {} 年, 到期时间: {}",
                String.format("%.1f", years), expiresAt);

        return new SnowflakeIdGenerator(epochMillis, props.datacenterId(), props.workerId());
    }
}
