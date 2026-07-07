package com.smart.rag.modelconfig.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * BYOK 模型配置实体 — 对应 {@code llm_config} 表（V16 迁移）。
 * <p>
 * 用户级 BYOK 配置：每个用户自带 LLM provider + 加密 api_key。
 * 系统默认仍由 application-*.yml 驱动（无系统级 DB 层，design §7）。
 * <p>
 * <b>id</b>：snowflake（{@link com.smart.rag.common.snowflake.SnowflakeIdGenerator}，
 * {@link IdType#INPUT} 应用层填充，非 DB 自增）；
 * <b>api_key_cipher/iv</b>：AES/GCM 密文 + 12B IV（{@link com.smart.rag.infrastructure.llm.crypto.ApiKeyCipher}）；
 * <b>endpoints</b>：JSON 文本 {@code {"chat":..,"embedding":..}}，XML 侧 {@code CAST AS jsonb}；
 * <b>deleted</b>：MyBatis-Plus 逻辑删除，软删行不进唯一索引可重建（对抗审查 R4）。
 * <p>
 * <b>created_by/updated_by</b>：service 层从 {@link com.smart.rag.chat.service.UserContextProvider}
 * 取 owner userId 填充（design §12.3 owner 自操作留痕；MetaHandler 仅填时间戳）。
 *
 * @see com.smart.rag.infrastructure.llm.crypto.ApiKeyCipher
 * @see com.smart.rag.infrastructure.llm.LlmCapability
 */
@TableName("llm_config")
public class LlmModelConfig {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属用户（admin 也是用户，无系统级 NULL 行，design §7） */
    private Long userId;

    /** CHAT / EMBEDDING / RERANKING（对齐 {@link com.smart.rag.infrastructure.llm.LlmCapability} 枚举名） */
    private String capabilityType;

    /** 供应商代码（bailian / deepseek / 用户自定义） */
    private String providerCode;

    /** 经 HostSafetyValidator SSRF 校验（design §13） */
    private String baseUrl;

    /** AES/GCM/NoPadding 密文（含 16B auth tag） */
    private byte[] apiKeyCipher;

    /** 每行独立 12B IV */
    private byte[] apiKeyIv;

    /** 实际调用名（ModelCandidate.model） */
    private String modelName;

    private String displayName;

    /** JSON 文本 {@code {"chat":..,"embedding":..,"rerank":..}}，XML 侧 {@code CAST AS jsonb}；nullable */
    private String endpoints;

    /** 仅 EMBEDDING */
    private Integer dimension;

    private Boolean supportsStreaming;

    private Boolean supportsThinking;

    private Integer priority;

    /** (userId, capabilityType) 下唯一；DB 部分唯一索引强制（并发安全 P0-2） */
    private Boolean isDefault;

    /** 1=enabled 0=disabled */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    /** owner 自操作留痕（service 层从 UserContextProvider 填） */
    private String createdBy;

    private String updatedBy;

    @TableLogic
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getCapabilityType() { return capabilityType; }
    public void setCapabilityType(String capabilityType) { this.capabilityType = capabilityType; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public byte[] getApiKeyCipher() { return apiKeyCipher; }
    public void setApiKeyCipher(byte[] apiKeyCipher) { this.apiKeyCipher = apiKeyCipher; }
    public byte[] getApiKeyIv() { return apiKeyIv; }
    public void setApiKeyIv(byte[] apiKeyIv) { this.apiKeyIv = apiKeyIv; }
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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
