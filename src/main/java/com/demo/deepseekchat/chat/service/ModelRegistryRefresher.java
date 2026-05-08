package com.demo.deepseekchat.chat.service;

import com.demo.deepseekchat.chat.client.ChatClientRegistry;
import com.demo.deepseekchat.chat.dto.ModelInfo;
import com.demo.deepseekchat.chat.provider.ModelProvider;
import com.demo.deepseekchat.chat.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 模型注册刷新器（多 Provider 版）
 * <p>
 * 遍历所有可用的 ModelProvider，聚合所有厂商的模型到 ChatClientRegistry。
 * 单个 Provider 拉取失败不影响其他 Provider（try-catch 隔离）。
 * <p>
 * 被 CommandLineRunner（启动时）和 ModelService（手动刷新）共同复用。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>OCP — 新增 Provider 后自动被遍历，不需要修改此类</li>
 *   <li>容错 — 单个 Provider 失败不阻塞其他 Provider 的模型注册</li>
 * </ul>
 */
@Component
public class ModelRegistryRefresher {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryRefresher.class);

    private final ProviderRegistry providerRegistry;
    private final ChatClientRegistry chatClientRegistry;

    public ModelRegistryRefresher(ProviderRegistry providerRegistry,
                                  ChatClientRegistry chatClientRegistry) {
        this.providerRegistry = providerRegistry;
        this.chatClientRegistry = chatClientRegistry;
    }

    /**
     * 从所有可用 Provider 拉取模型，创建 ChatClient，原子替换 Registry。
     * <p>
     * 策略：先在临时 Map/List 中构建所有模型，全部完成后一次性替换。
     * 不会出现 "清空后某个 Provider 失败导致无模型可用" 的中间状态。
     *
     * @return true 至少有一个 Provider 刷新成功
     */
    public boolean refresh() {
        log.info("Refreshing models from {} providers: {}",
                providerRegistry.size(), providerRegistry.getAvailableProviderIds());

        Map<String, ChatClient> newClients = new LinkedHashMap<>();
        List<ModelInfo> allModels = new ArrayList<>();
        int successCount = 0;

        for (ModelProvider provider : providerRegistry.getAll()) {
            try {
                List<ModelInfo> models = provider.fetchModels();
                if (models.isEmpty()) {
                    log.warn("Provider {} returned empty model list", provider.getProviderId());
                    continue;
                }

                for (ModelInfo model : models) {
                    try {
                        ChatClient client = provider.createClient(model.id(), null);
                        // 用复合格式作为 key: "deepseek/deepseek-chat"
                        String compositeKey = provider.getProviderId() + "/" + model.id();
                        newClients.put(compositeKey, client);
                        // 同时用纯 modelId 注册（向后兼容，最后一个同名 Provider 覆盖）
                        newClients.putIfAbsent(model.id(), client);
                        allModels.add(model);
                    } catch (Exception e) {
                        log.warn("Failed to create client for {}/{}: {}",
                                provider.getProviderId(), model.id(), e.getMessage());
                    }
                }
                successCount++;
                log.info("Provider {}: registered {} models", provider.getProviderId(), models.size());
            } catch (Exception e) {
                log.error("Failed to refresh provider {}: {}", provider.getProviderId(), e.getMessage());
            }
        }

        if (!newClients.isEmpty()) {
            chatClientRegistry.replaceAll(newClients, allModels);
        }

        log.info("Refresh complete: {} models from {}/{} providers",
                newClients.size(), successCount, providerRegistry.size());

        return successCount > 0;
    }
}
