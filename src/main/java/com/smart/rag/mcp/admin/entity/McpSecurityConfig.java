package com.smart.rag.mcp.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * MCP 安全配置实体 — jsonb 单行表（{@code mcp_security_config}，V17 迁移）。
 * <p>
 * 单行表约定：{@code id} 固定为 1，CHECK 约束保证。
 * 整体读写（{@code configJson}），避免 EAV 反模式。
 * <p>
 * 强类型视图（{@link McpSecurityConfigView}）由 {@link #view(ObjectMapper)} 反序列化得到。
 */
@TableName("mcp_security_config")
public class McpSecurityConfig {

    /** 固定为 1（单行表约定，CHECK 约束保证） */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** JSONB 配置文档；反序列化为 {@link McpSecurityConfigView} */
    private String configJson;

    private LocalDateTime updatedAt;

    /** 反序列化得到强类型视图（不持久化） */
    public McpSecurityConfigView view(ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(configJson, McpSecurityConfigView.class);
        } catch (JsonProcessingException e) {
            return McpSecurityConfigView.defaults();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
