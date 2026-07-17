package com.smart.rag.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性通用配置——给所有指标打共用 tag（application / env），便于 Prometheus 维度筛选与跨环境区分。
 * <p>
 * 与 yml 的 {@code management.metrics.tags.application} 互补：这里能动态读 profile，
 * 避免 yml 静态值在多 profile 叠加时失真（如 {@code stable,prod} 模式）。
 */
@Configuration
public class ObservabilityConfig {

    /**
     * 给所有新注册的 Meter 打 {@code application=smart-rag} + {@code env=<active profile>} tag。
     * <p>
     * 注：仅对 customizer 装配<b>之后</b>注册的指标生效（Spring Boot 在 registry 初始化时应用所有 customizer），
     * LLM/Messaging 等指标在 Bean 初始化阶段注册，时序上能覆盖。
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonMetricsTagsCustomizer(
            @Value("${spring.profiles.active:dev}") String activeProfile) {
        return registry -> registry.config().commonTags(
            Tags.of("application", "smart-rag").and("env", activeProfile));
    }
}
