package com.demo.deepseekchat.service;

import com.demo.deepseekchat.chat.ChatClientFactory;
import com.demo.deepseekchat.chat.ChatClientRegistry;
import com.demo.deepseekchat.config.DeepSeekProperties;
import com.demo.deepseekchat.model.dto.ModelInfo;
import com.demo.deepseekchat.model.dto.ModelsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 模型管理服务
 * <p>
 * 依赖 ChatClientRegistry 查询、ChatClientFactory 创建、RestClient 远程调用。
 * 不再直接依赖 Config 类。
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    private final ChatClientRegistry registry;
    private final ChatClientFactory factory;
    private final DeepSeekProperties properties;
    private final RestClient restClient;

    public ModelService(ChatClientRegistry registry,
                        ChatClientFactory factory,
                        DeepSeekProperties properties,
                        RestClient deepSeekRestClient) {
        this.registry = registry;
        this.factory = factory;
        this.properties = properties;
        this.restClient = deepSeekRestClient;
    }

    /**
     * 获取所有可用模型列表
     */
    public List<ModelInfo> listModels() {
        return registry.getCachedModels();
    }

    /**
     * 检查模型是否可用
     */
    public boolean isModelAvailable(String modelId) {
        return registry.contains(modelId);
    }

    /**
     * 刷新模型列表（从 DeepSeek API 重新拉取）
     */
    public void refreshModels() {
        log.info("Refreshing DeepSeek model list...");
        registry.clear();

        try {
            ModelsResponse response = restClient.get()
                    .uri("/models")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .retrieve()
                    .body(ModelsResponse.class);

            if (response != null && response.data() != null) {
                registry.setCachedModels(response.data());
                for (ModelInfo model : response.data()) {
                    registry.register(model.id(), factory.create(model.id()));
                }
                log.info("Refreshed {} models", registry.size());
            }
        } catch (Exception e) {
            log.error("Failed to refresh models", e);
        }
    }
}
