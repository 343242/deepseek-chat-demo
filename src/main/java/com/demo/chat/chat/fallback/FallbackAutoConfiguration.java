package com.demo.chat.chat.fallback;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 兜底策略自动配置
 * <p>
 * 启用 {@link ChatFallbackProperties} 配置绑定。
 * {@link FallbackChainResolver} 已通过 {@code @Component} 自动注册。
 */
@Configuration
@EnableConfigurationProperties(ChatFallbackProperties.class)
public class FallbackAutoConfiguration {
}
