package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.entity.ModelParams;
import com.smart.rag.infrastructure.model.ModelOptionSettings;
import com.smart.rag.infrastructure.provider.ModelProvider;
import com.smart.rag.infrastructure.provider.ModelRouter;
import com.smart.rag.infrastructure.provider.ProviderRegistry;
import com.smart.rag.infrastructure.stream.ModelStreamRequest;
import org.springframework.stereotype.Component;

@Component
public class ModelStreamRequestFactory {

    private final ProviderRegistry providerRegistry;
    private final ModelParamsService modelParamsService;

    public ModelStreamRequestFactory(ProviderRegistry providerRegistry,
                                     ModelParamsService modelParamsService) {
        this.providerRegistry = providerRegistry;
        this.modelParamsService = modelParamsService;
    }

    public ModelStreamRequest create(ModelRouter.Route route, ChatRequest request) {
        ModelProvider provider = providerRegistry.get(route.providerId());
        return provider.createStreamRequest(route, request.message(), resolveOptionSettings(route));
    }

    private ModelOptionSettings resolveOptionSettings(ModelRouter.Route route) {
        ModelParams params = modelParamsService.getParams(route.toCompositeId());
        if (params == null) {
            params = modelParamsService.getParams(route.modelId());
        }
        if (params == null) {
            return null;
        }
        return new ModelOptionSettings(
                params.getTemperature(),
                params.getMaxTokens(),
                params.getTopP(),
                params.getFrequencyPenalty(),
                params.getPresencePenalty());
    }
}
