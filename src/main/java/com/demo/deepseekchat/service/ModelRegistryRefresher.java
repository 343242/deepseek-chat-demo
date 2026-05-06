package com.demo.deepseekchat.service;

import com.demo.deepseekchat.chat.ChatClientFactory;
import com.demo.deepseekchat.chat.ChatClientRegistry;
import com.demo.deepseekchat.config.DeepSeekProperties;
import com.demo.deepseekchat.model.dto.ModelInfo;
import com.demo.deepseekchat.model.dto.ModelsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型注册刷新器
 * <p>
 * 封装 "从 DeepSeek API 拉取模型列表 → 创建 ChatClient → 原子替换 Registry" 的完整流程。
 * 被 CommandLineRunner（启动时）和 ModelService（手动刷新）共同复用，消除重复代码。
 */
@Component
public class ModelRegistryRefresher {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryRefresher.class);

    private final ChatClientFactory factory;
    private final ChatClientRegistry registry;
    private final DeepSeekProperties properties;
    private final RestClient restClient;

    public ModelRegistryRefresher(ChatClientFactory factory,
                                  ChatClientRegistry registry,
                                  DeepSeekProperties properties,
                                  RestClient deepSeekRestClient) {
        this.factory = factory;
        this.registry = registry;
        this.properties = properties;
        this.restClient = deepSeekRestClient;
    }

    /**
     * 从 DeepSeek API 拉取模型列表，创建 ChatClient，原子替换 Registry。
     *
     * @return true 刷新成功，false 刷新失败（原有模型不受影响）
     */
    public boolean refresh() {
        log.info("Refreshing DeepSeek model list from {}...", properties.baseUrl());
        try {
            ModelsResponse response = restClient.get()
                    .uri("/models")
                    .retrieve()
                    .body(ModelsResponse.class);

            if (response == null || response.data() == null) {
                log.warn("Failed to fetch model list: response is null");
                return false;
            }

            List<ModelInfo> models = response.data();

            // 先在临时 Map 中构建所有 ChatClient，成功后再一次性替换
            Map<String, ChatClient> newClients = new LinkedHashMap<>();
            for (ModelInfo model : models) {
                newClients.put(model.id(), factory.create(model.id()));
            }

            // 原子替换：不会出现 "清空后拉取失败导致无模型可用" 的中间状态
            registry.replaceAll(newClients, models);

            log.info("Refreshed {} models: {}", registry.size(), registry.getAvailableModelIds());
            return true;
        } catch (Exception e) {
            log.error("Failed to refresh models from DeepSeek API", e);
            return false;
        }
    }
}
