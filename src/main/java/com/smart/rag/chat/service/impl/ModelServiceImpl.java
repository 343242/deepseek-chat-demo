package com.smart.rag.chat.service.impl;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.chat.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型管理服务
 * <p>
 * 通过 {@link LlmClientRegistry} 查询已注册的模型候选。
 */
@Service
public class ModelServiceImpl implements ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelServiceImpl.class);

    private final LlmClientRegistry llmRegistry;

    public ModelServiceImpl(LlmClientRegistry llmRegistry) {
        this.llmRegistry = llmRegistry;
    }

    @Override
    public List<String> listModelIds() {
        return List.copyOf(llmRegistry.registeredCandidateIds());
    }

    @Override
    public boolean isModelAvailable(String candidateId) {
        CapabilityClient client = llmRegistry.find(candidateId);
        return client != null && client.isAvailable();
    }

    @Override
    public boolean refreshModels() {
        try {
            llmRegistry.refresh();
            return true;
        } catch (Exception e) {
            log.warn("Model refresh failed: {}", e.getMessage());
            return false;
        }
    }
}
