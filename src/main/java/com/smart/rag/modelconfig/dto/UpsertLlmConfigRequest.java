package com.smart.rag.modelconfig.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * BYOK 配置 upsert 请求（owner 唯一写入入口，design §12.2）。
 * <p>
 * {@code api_key} 为明文，由 {@code LlmModelConfigService.upsert} 加密后落库（不在请求外泄露）。
 * <p>
 * <b>P1-8</b>：{@code capability_type} 本期仅 {@code CHAT}，EMBEDDING/RERANKING → 422 Unsupported。
 * <p>
 * <b>防注入（design §12.2）</b>：{@code ignoreUnknown = false} 显式拒绝未知字段。
 * 项目其他 DTO 惯例 {@code ignoreUnknown=true}（宽松）；BYOK 写入入口必须严格，
 * 防止多余字段绕过校验/注入未来字段。Spring MVC 全局默认 FAIL_ON_UNKNOWN_PROPERTIES=true，
 * 此处 DTO 级显式标注兜底（即使全局被关仍拒绝）。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class UpsertLlmConfigRequest {

    /** CHAT / EMBEDDING / RERANKING（本期仅 CHAT 被消费） */
    private String capabilityType;

    /** 供应商代码（bailian / deepseek / 用户自定义） */
    private String providerCode;

    /** 经 HostSafetyValidator SSRF 校验 */
    private String baseUrl;

    /** 明文 api_key（落库前 AES/GCM 加密） */
    private String apiKey;

    /** 实际调用名（ModelCandidate.model） */
    private String modelName;

    private String displayName;

    /** JSON 文本 {@code {"chat":..}}；nullable */
    private String endpoints;

    /** 仅 EMBEDDING */
    private Integer dimension;

    private Boolean supportsStreaming;

    private Boolean supportsThinking;

    private Integer priority;

    private Boolean isDefault;

    /** 1=enabled 0=disabled；null 默认 1 */
    private Integer status;

    public String getCapabilityType() { return capabilityType; }
    public void setCapabilityType(String capabilityType) { this.capabilityType = capabilityType; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEndpoints() { return endpoints; }
    public void setEndpoints(String endpoints) { this.endpoints = endpoints; }
    public Integer getDimension() { return dimension; }
    public void setDimension(Integer dimension) { this.dimension = dimension; }
    public Boolean getSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(Boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
    public Boolean getSupportsThinking() { return supportsThinking; }
    public void setSupportsThinking(Boolean supportsThinking) { this.supportsThinking = supportsThinking; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
