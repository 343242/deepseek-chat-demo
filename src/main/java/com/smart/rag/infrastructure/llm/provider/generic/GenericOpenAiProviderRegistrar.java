package com.smart.rag.infrastructure.llm.provider.generic;

import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.strategy.CapabilityStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.context.EnvironmentAware;

import com.smart.rag.infrastructure.llm.LlmCapability;
import org.springframework.boot.context.properties.bind.Bindable;

import java.util.Map;

/**
 * 供应商自动注册器 — 扫描 {@code app.llm.providers} 配置，注册 GenericOpenAiProvider Bean
 * <p>
 * 所有供应商统一注册为 {@link GenericOpenAiProvider}，
 * 由 {@link com.smart.rag.infrastructure.llm.strategy.CapabilityStrategy} 负责差异化客户端创建。
 * <p>
 * 使用 {@link EnvironmentAware} 回调获取 Environment（在 BDRPP 回调之前触发），
 * CapabilityStrategyRegistry 通过 Supplier 延迟查找以避开早期生命周期限制。
 */
@Configuration(proxyBeanMethods = false)
public class GenericOpenAiProviderRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(GenericOpenAiProviderRegistrar.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (environment == null) {
            throw new IllegalStateException(
                "Environment not injected — setEnvironment() must be called before postProcessBeanDefinitionRegistry()");
        }

        String prefix = "app.llm.providers";

        org.springframework.boot.context.properties.bind.Binder binder =
            org.springframework.boot.context.properties.bind.Binder.get(environment);
        Map<String, Object> providersMap = binder
            .bind(prefix, Bindable.mapOf(String.class, Object.class))
            .orElse(Map.of());

        if (providersMap.isEmpty()) {
            log.info("No LLM providers found under {}", prefix);
            return;
        }

        if (!(registry instanceof DefaultListableBeanFactory beanFactory)) {
            throw new IllegalStateException(
                "GenericOpenAiProviderRegistrar requires DefaultListableBeanFactory, "
                + "but got " + registry.getClass().getName());
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

            Map<String, String> endpoints = new java.util.HashMap<>();
            for (LlmCapability cap : LlmCapability.values()) {
                String mapKey = cap.name().toLowerCase();
                String value = environment.getProperty(prefix + "." + id + ".endpoints." + cap.yamlKey());
                if (value != null) {
                    endpoints.put(mapKey, value);
                }
            }

            ProviderConfig config = endpoints.isEmpty()
                ? ProviderConfig.of(url, apiKey)
                : ProviderConfig.of(url, apiKey, endpoints);

            String beanName = "llmProvider-" + id;

            registry.registerBeanDefinition(beanName,
                new RootBeanDefinition(GenericOpenAiProvider.class,
                    () -> new GenericOpenAiProvider(id, config,
                        beanFactory.getBean(CapabilityStrategyRegistry.class))));

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
