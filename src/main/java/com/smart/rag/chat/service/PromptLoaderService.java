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

    /**
     * 获取 default.xml 兜底模板的 system prompt（raw XML）。
     *
     * @return default 模板文本，无 default 模板时返回 null
     */
    String getDefaultPrompt();

    void loadPrompts();

    void reload();
}
