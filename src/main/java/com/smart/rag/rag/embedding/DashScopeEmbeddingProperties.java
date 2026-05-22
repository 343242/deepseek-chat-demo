package com.smart.rag.rag.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DashScope Embedding 模型配置属性。
 * <p>
 * 对应 application.yml 中 spring.ai.dashscope.embedding.* 配置。
 * 支持 text-embedding-v4 的全部高级参数。
 * </p>
 */
@ConfigurationProperties(prefix = "spring.ai.dashscope.embedding")
@Component
public class DashScopeEmbeddingProperties {

    /** DashScope API Base URL */
    private String baseUrl = "https://dashscope.aliyuncs.com";

    /** DashScope API Key */
    private String apiKey;

    /** 模型名称 */
    private String model = "text-embedding-v4";

    /** 向量维度 */
    private int dimensions = 1024;

    /**
     * 文本类型策略。
     * <ul>
     *   <li>auto — 自动判断：embed(Document) → document, embed(String) → query</li>
     *   <li>query — 强制使用 query</li>
     *   <li>document — 强制使用 document</li>
     *   <li>disabled — 不传 text_type</li>
     * </ul>
     */
    private TextType textType = TextType.AUTO;

    /**
     * 自定义任务指令（仅 text_type=query 时生效）。
     * 建议使用英文撰写，通常可带来约 1-5% 的效果提升。
     * 空字符串表示不传该参数。
     */
    private String instruct = "";

    /** 单次 API 调用超时时间（秒），默认 10 秒 */
    private int timeoutSeconds = 10;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public TextType getTextType() { return textType; }
    public void setTextType(TextType textType) { this.textType = textType; }
    public String getInstruct() { return instruct; }
    public void setInstruct(String instruct) { this.instruct = instruct; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
