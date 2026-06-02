package com.smart.rag.infrastructure.provider;

import com.smart.rag.infrastructure.exception.ProviderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型厂商注册中心（服务定位模式）
 * <p>
 * 管理所有可用的 ModelProvider 实例。通过 Spring 构造器注入自动发现所有实现。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 只负责 Provider 的注册和查询</li>
 *   <li>OCP — 新增 Provider 自动被发现，不需要修改此类</li>
 *   <li>容错 — 未配置 API Key 的 Provider 静默跳过，不影响其他 Provider</li>
 * </ul>
 * <p>
 * 生命周期：Spring 容器启动时一次性构建，运行时不可变（线程安全）。
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, ModelProvider> providers;

    /**
     * Spring 自动注入所有 ModelProvider 实现
     * <p>
     * 过滤掉 isAvailable() == false 的 Provider（未配置 API Key），
     * 确保应用不会因缺少某个厂商的配置而启动失败。
     *
     * @param providerList Spring 容器中所有 ModelProvider 实现
     */
    public ProviderRegistry(List<ModelProvider> providerList) {
        this.providers = Collections.unmodifiableMap(
                providerList.stream()
                        .filter(ModelProvider::isAvailable)
                        .collect(Collectors.toMap(
                                ModelProvider::getProviderId,
                                Function.identity(),
                                (a, b) -> a,
                                LinkedHashMap::new)));

        if (providers.isEmpty()) {
            log.warn("No model providers available — check API key configuration");
        } else {
            log.info("Registered {} model providers: {}",
                    providers.size(), providers.keySet());
        }
    }

    /**
     * 获取指定厂商的 Provider
     *
     * @param providerId 厂商 ID
     * @return 对应的 ModelProvider
     * @throws ProviderNotFoundException 厂商不存在或未配置
     */
    public ModelProvider get(String providerId) {
        ModelProvider provider = providers.get(providerId);
        if (provider == null) {
            log.warn("Provider not found: {}, available: {}", providerId, providers.keySet());
            throw new ProviderNotFoundException(providerId,
                    "Provider not found: " + providerId);
        }
        return provider;
    }

    /**
     * 获取所有可用的 Provider
     *
     * @return 不可变的 Provider 集合
     */
    public Collection<ModelProvider> getAll() {
        return providers.values();
    }

    /**
     * 获取所有可用的厂商 ID
     *
     * @return 不可变的厂商 ID 集合
     */
    public Set<String> getAvailableProviderIds() {
        return providers.keySet();
    }

    /**
     * 检查指定厂商是否可用
     *
     * @param providerId 厂商 ID
     * @return true 表示已注册且可用
     */
    public boolean isAvailable(String providerId) {
        return providers.containsKey(providerId);
    }

    /**
     * 获取已注册 Provider 数量
     */
    public int size() {
        return providers.size();
    }
}
