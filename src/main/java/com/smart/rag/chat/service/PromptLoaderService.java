package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.PromptTemplate;

import java.util.List;

/**
 * XML 系统提示词加载服务接口
 */
public interface PromptLoaderService {

    String getPrompt(String modelId);

    String getPromptFromRedis(String modelId);

    PromptTemplate getTemplate(String modelId);

    List<String> getAvailableModels();

    void loadPrompts();

    void reload();
}
