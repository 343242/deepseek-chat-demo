package com.demo.deepseekchat.chat.service;

import com.demo.deepseekchat.chat.client.ChatClientRegistry;
import com.demo.deepseekchat.chat.dto.ModelInfo;
import com.demo.deepseekchat.chat.dto.ProviderModelInfo;
import com.demo.deepseekchat.chat.provider.ModelProvider;
import com.demo.deepseekchat.chat.provider.ProviderRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型管理服务
 * <p>
 * 依赖 ChatClientRegistry 查询、ModelRegistryRefresher 刷新、ProviderRegistry 获取厂商信息。
 * 不再直接依赖 RestClient / ChatClientFactory / Config 类。
 * <p>
 * 多 Provider 支持：listModels() 返回 ProviderModelInfo（含厂商信息），
 * 替代原有的纯 ModelInfo 列表。
 */
@Service
public class ModelService {

    private final ChatClientRegistry registry;
    private final ModelRegistryRefresher refresher;
    private final ProviderRegistry providerRegistry;

    public ModelService(ChatClientRegistry registry,
                        ModelRegistryRefresher refresher,
                        ProviderRegistry providerRegistry) {
        this.registry = registry;
        this.refresher = refresher;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 获取所有可用模型列表（含厂商信息）
     * <p>
     * 将 ChatClientRegistry 缓存的 ModelInfo 与 Provider 信息关联，
     * 返回 ProviderModelInfo 列表供前端展示。
     *
     * @return 多厂商模型列表
     */
    public List<ProviderModelInfo> listModels() {
        List<ModelInfo> cachedModels = registry.getCachedModels();
        List<ProviderModelInfo> result = new ArrayList<>(cachedModels.size());

        for (ModelInfo model : cachedModels) {
            // O(1) 查询：基于刷新时构建的反向索引
            String providerId = refresher.getProviderIdForModel(model.id());
            if (providerId != null) {
                ModelProvider provider = providerRegistry.get(providerId);
                result.add(ProviderModelInfo.from(model,
                        provider.getProviderId(),
                        provider.getDisplayName()));
            } else {
                // 未匹配时使用默认值（向后兼容）
                result.add(new ProviderModelInfo(
                        model.id(), "deepseek", "DeepSeek",
                        "deepseek/" + model.id(),
                        model.ownedBy(), model.created()));
            }
        }

        return result;
    }

    /**
     * 检查模型是否可用
     */
    public boolean isModelAvailable(String modelId) {
        return registry.contains(modelId);
    }

    /**
     * 刷新模型列表（从所有 Provider API 重新拉取）
     *
     * @return true 至少一个 Provider 刷新成功
     */
    public boolean refreshModels() {
        return refresher.refresh();
    }

}
