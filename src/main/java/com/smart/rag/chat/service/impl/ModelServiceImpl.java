package com.smart.rag.chat.service.impl;

import com.smart.rag.infrastructure.ai.client.ChatClientRegistry;
import com.smart.rag.infrastructure.ai.model.ModelInfo;
import com.smart.rag.infrastructure.ai.model.ProviderModelInfo;
import com.smart.rag.infrastructure.ai.provider.ModelProvider;
import com.smart.rag.infrastructure.ai.provider.ProviderRegistry;
import com.smart.rag.chat.service.ModelRegistryRefresher;
import com.smart.rag.chat.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class ModelServiceImpl implements ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelServiceImpl.class);

    private final ChatClientRegistry registry;
    private final ModelRegistryRefresher refresher;
    private final ProviderRegistry providerRegistry;

    public ModelServiceImpl(ChatClientRegistry registry,
                            ModelRegistryRefresher refresher,
                            ProviderRegistry providerRegistry) {
        this.registry = registry;
        this.refresher = refresher;
        this.providerRegistry = providerRegistry;
    }

    @Override
    public List<ProviderModelInfo> listModels() {
        List<ModelInfo> cachedModels = registry.getCachedModels();
        List<ProviderModelInfo> result = new ArrayList<>(cachedModels.size());

        for (ModelInfo model : cachedModels) {
            String providerId = refresher.getProviderIdForModel(model.id());
            if (providerId != null) {
                ModelProvider provider = providerRegistry.get(providerId);
                result.add(ProviderModelInfo.from(model,
                        provider.getProviderId(),
                        provider.getDisplayName()));
            } else {
                log.warn("Model '{}' not found in provider index, skipping", model.id());
            }
        }

        return result;
    }

    @Override
    public boolean isModelAvailable(String modelId) {
        return registry.contains(modelId);
    }

    @Override
    public boolean refreshModels() {
        boolean success = refresher.refresh();
        if (!success) {
            log.warn("Model refresh completed with failures — some providers may be unavailable");
        }
        return success;
    }

}
