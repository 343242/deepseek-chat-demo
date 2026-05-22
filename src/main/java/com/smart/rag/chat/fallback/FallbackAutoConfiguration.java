package com.smart.rag.chat.fallback;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 兜底策略自动配置
 * <p>
 * 启用 {@link ChatFallbackProperties} 配置绑定，
 * 并创建 {@link StreamRetryHandler} Bean。
 * <p>
 * {@link FallbackChainResolver}（实现 {@link FallbackChainProvider}）
 * 和 {@link FallbackEligibility} 已通过 {@code @Component} 自动注册。
 */
@Configuration
@EnableConfigurationProperties(ChatFallbackProperties.class)
public class FallbackAutoConfiguration {

    @Bean
    public StreamRetryHandler streamRetryHandler(ChatFallbackProperties properties,
                                                  FallbackEligibility eligibility) {
        return new StreamRetryHandler(properties.maxRetries(), eligibility);
    }
}
