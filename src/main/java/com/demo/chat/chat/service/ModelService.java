package com.demo.chat.chat.service;

import com.demo.chat.chat.dto.ProviderModelInfo;

import java.util.List;

/**
 * 模型管理服务接口
 */
public interface ModelService {

    List<ProviderModelInfo> listModels();

    boolean isModelAvailable(String modelId);

    boolean refreshModels();
}
