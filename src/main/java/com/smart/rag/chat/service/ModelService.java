package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.ai.model.ProviderModelInfo;

import java.util.List;

/**
 * 模型管理服务接口
 */
public interface ModelService {

    List<ProviderModelInfo> listModels();

    boolean isModelAvailable(String modelId);

    boolean refreshModels();
}
