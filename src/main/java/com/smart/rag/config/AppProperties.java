package com.smart.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * 应用级配置（时间统一）。
 * <p>
 * 绑定 {@code app.time-zone} 与 {@code app.date-format}。展示时区是部署级配置项，
 * 默认东八区，可被环境变量 {@code APP_TIME_ZONE} 覆盖。
 *
 * @see com.smart.rag.config.time.TimeCodec
 */
@ConfigurationProperties("app")
public record AppProperties(ZoneId timeZone, String dateFormat) {
}
