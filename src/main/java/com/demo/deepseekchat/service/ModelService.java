package com.demo.deepseekchat.service;

import com.demo.deepseekchat.chat.ChatClientRegistry;
import com.demo.deepseekchat.model.dto.ModelInfo;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型管理服务
 * <p>
 * 依赖 ChatClientRegistry 查询、ModelRegistryRefresher 刷新。
 * 不再直接依赖 RestClient / ChatClientFactory / Config 类。
 */
@Service
public class ModelService {

    private final ChatClientRegistry registry;
    private final ModelRegistryRefresher refresher;

    public ModelService(ChatClientRegistry registry, ModelRegistryRefresher refresher) {
        this.registry = registry;
        this.refresher = refresher;
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
     * <p>
     * 失败时原有模型不受影响（原子替换策略）。
     *
     * @return true 刷新成功
     */
    public boolean refreshModels() {
        return refresher.refresh();
    }
}
