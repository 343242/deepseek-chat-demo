package com.smart.rag.infrastructure.llm.provider.generic;

import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.strategy.CapabilityStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.env.Environment;

import java.util.Map;

/**
 * 供应商自动注册器 — 扫描 {@code app.llm.providers} 配置，注册 GenericOpenAiProvider Bean
 * <p>
 * 所有供应商统一注册为 {@link GenericOpenAiProvider}，
 * 由 {@link com.smart.rag.infrastructure.llm.strategy.CapabilityStrategy} 负责差异化客户端创建。
 */
@Configuration
public class GenericOpenAiProviderRegistrar implements BeanDefinitionRegistryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(GenericOpenAiProviderRegistrar.class);

    private final Environment environment;
    private final CapabilityStrategyRegistry strategyRegistry;

    public GenericOpenAiProviderRegistrar(Environment environment,
                                          CapabilityStrategyRegistry strategyRegistry) {
        this.environment = environment;
        this.strategyRegistry = strategyRegistry;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        String prefix = "app.llm.providers";
        String providersStr = environment.getProperty(prefix);
        if (providersStr == null) {
            log.info("No LLM providers configured ({} not found)", prefix);
            return;
        }

        // Discover provider IDs dynamically from YAML keys
        org.springframework.boot.context.properties.bind.Binder binder =
            org.springframework.boot.context.properties.bind.Binder.get(environment);
        java.util.Map<String, Object> providersMap = binder
            .bind(prefix, java.util.Map.class)
            .orElse(java.util.Map.of());

        if (providersMap.isEmpty()) {
            log.info("No LLM providers found under {}", prefix);
            return;
        }

        String[] providerIds = providersMap.keySet().toArray(String[]::new);
        int registered = 0;
        for (String id : providerIds) {
            String url = environment.getProperty(prefix + "." + id + ".url");
            String apiKey = environment.getProperty(prefix + "." + id + ".api-key");
            if (url == null || url.isBlank() || apiKey == null || apiKey.isBlank()) {
                log.debug("Provider '{}' not configured or missing credentials, skipping", id);
                continue;
            }

            // Read endpoints map for EndpointConfig support
            java.util.Map<String, String> endpoints = new java.util.HashMap<>();
            String chatEndpoint = environment.getProperty(prefix + "." + id + ".endpoints.chat");
            if (chatEndpoint != null) endpoints.put("chat", chatEndpoint);
            String embEndpoint = environment.getProperty(prefix + "." + id + ".endpoints.embedding");
            if (embEndpoint != null) endpoints.put("embedding", embEndpoint);
            String rerankEndpoint = environment.getProperty(prefix + "." + id + ".endpoints.rerank");
            if (rerankEndpoint != null) endpoints.put("reranking", rerankEndpoint);

            ProviderConfig config = endpoints.isEmpty()
                ? ProviderConfig.of(url, apiKey)
                : ProviderConfig.of(url, apiKey, endpoints);

            String beanName = "llmProvider-" + id;

            registry.registerBeanDefinition(beanName,
                new RootBeanDefinition(GenericOpenAiProvider.class,
                    () -> new GenericOpenAiProvider(id, config, strategyRegistry)));

            registered++;
            log.info("Registered LLM provider: id={}, url={}, endpoints={}", id, url, endpoints.keySet());
        }

        if (registered == 0) {
            log.warn("No LLM providers registered — check app.llm.providers configuration");
        }
    }

    @Override
    public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        // no-op
    }
}
