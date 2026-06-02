package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.provider.ProviderRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 兜底策略自动配置
 * <p>
 * 启用 {@link ChatFallbackProperties} 和 {@link ChatCandidatesProperties} 配置绑定，
 * 并创建降级链提供者和 {@link StreamRetryHandler} Bean。
 * <p>
 * 当 {@code app.chat.candidates.list} 非空时激活 {@link DynamicModelSelector}，
 * 否则使用 {@link FallbackChainResolver}（静态降级链）。
 */
@Configuration
@EnableConfigurationProperties({
        ChatFallbackProperties.class,
        ModelCircuitBreakerProperties.class,
        ChatCandidatesProperties.class
})
public class FallbackAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.chat.candidates", name = "list", matchIfMissing = false)
    public DynamicModelSelector dynamicModelSelector(ChatCandidatesProperties props,
                                                      ModelCircuitBreakerRegistry breakers) {
        return new DynamicModelSelector(props, breakers);
    }

    @Bean
    @ConditionalOnMissingBean(FallbackChainProvider.class)
    public FallbackChainResolver fallbackChainResolver(ChatFallbackProperties properties,
                                                        ProviderRegistry providerRegistry) {
        return new FallbackChainResolver(properties, providerRegistry);
    }

    @Bean
    public StreamRetryHandler streamRetryHandler(ChatFallbackProperties properties,
                                                  FallbackEligibility eligibility) {
        return new StreamRetryHandler(properties.maxRetries(), eligibility);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.chat.candidates", name = "probe-enabled", havingValue = "true", matchIfMissing = false)
    public ProbeStreamHandler probeStreamHandler(ChatCandidatesProperties props,
                                                  ModelCircuitBreakerRegistry breakers) {
        return new ProbeStreamHandler(props, breakers);
    }
}
