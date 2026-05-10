package com.demo.chat.rag.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DashScope Embedding 模型配置属性
 * <p>
 * 对应 application.yml 中 spring.ai.dashscope.embedding.* 配置。
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "spring.ai.dashscope.embedding")
public class DashScopeEmbeddingProperties {

    /** DashScope API Base URL（OpenAI 兼容模式） */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** DashScope API Key */
    private String apiKey;

    /** 模型名称 */
    private String model = "text-embedding-v4";

    /** 向量维度 */
    private int dimensions = 1024;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
}
