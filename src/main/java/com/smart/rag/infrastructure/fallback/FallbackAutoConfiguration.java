package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.fallback.cache.ModelHealthCache;
import com.smart.rag.infrastructure.fallback.cache.ModelHealthPreProber;
import com.smart.rag.infrastructure.fallback.probe.SharedProbeRegistry;
import com.smart.rag.infrastructure.provider.ProviderRegistry;
import org.redisson.api.RedissonClient;
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
 * <p>
 * 探测缓存优化（{@code app.chat.candidates.probe-cache-enabled=true}）启用后，
 * 额外注册 {@link SharedProbeRegistry}、{@link ModelHealthCache}、{@link ModelHealthPreProber}。
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
    @ConditionalOnProperty(prefix = "app.chat.candidates", name = "probe-cache-enabled",
            havingValue = "true", matchIfMissing = false)
    public SharedProbeRegistry sharedProbeRegistry() {
        return new SharedProbeRegistry();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.chat.candidates", name = "probe-cache-enabled",
            havingValue = "true", matchIfMissing = false)
    public ModelHealthCache modelHealthCache(RedissonClient redisson,
                                              ChatCandidatesProperties props) {
        int unhealthyTtl = Math.max(props.probeCacheTtlSeconds() / 2, 5);
        return new ModelHealthCache(redisson, props.probeCacheTtlSeconds(), unhealthyTtl);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.chat.candidates", name = "probe-cache-enabled",
            havingValue = "true", matchIfMissing = false)
    public ModelHealthPreProber modelHealthPreProber(ChatCandidatesProperties props,
                                                      ModelHealthCache healthCache) {
        // 默认探测函数：阻塞式 ping（由实际部署覆盖）
        ModelHealthPreProber.ProbeFunction noOp = modelId -> {
            throw new UnsupportedOperationException(
                    "Pre-probe function not configured. Provide a ModelHealthPreProber.ProbeFunction bean.");
        };
        return new ModelHealthPreProber(props, healthCache, noOp);
    }

    @Bean
    public StreamRetryHandler streamRetryHandler(ChatFallbackProperties properties,
                                                  FallbackEligibility eligibility,
                                                  @org.springframework.lang.Nullable SharedProbeRegistry probeRegistry,
                                                  @org.springframework.lang.Nullable ModelHealthCache healthCache,
                                                  ChatCandidatesProperties candidatesProps) {
        return new StreamRetryHandler(properties.maxRetries(), eligibility,
                healthCache, probeRegistry, candidatesProps.probeTimeoutSeconds());
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.chat.candidates", name = "probe-enabled",
            havingValue = "true", matchIfMissing = false)
    public ProbeStreamHandler probeStreamHandler(ChatCandidatesProperties props,
                                                  ModelCircuitBreakerRegistry breakers) {
        return new ProbeStreamHandler(props, breakers);
    }
}
