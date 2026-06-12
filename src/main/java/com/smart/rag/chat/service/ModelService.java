package com.smart.rag.chat.service;

import java.util.List;

/**
 * 模型管理服务接口
 */
public interface ModelService {

    List<String> listModelIds();

    boolean isModelAvailable(String candidateId);

    boolean refreshModels();
}
