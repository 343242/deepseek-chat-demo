package com.smart.rag.mcp.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * MCP 安全配置实体 — jsonb 单行表（{@code mcp_security_config}，V17 迁移）。
 * <p>
 * 单行表约定：{@code id} 固定为 1，CHECK 约束保证。
 * 整体读写（{@code configJson}），避免 EAV 反模式。
 */
@TableName("mcp_security_config")
public class McpSecurityConfig {

    /** 固定为 1（单行表约定，CHECK 约束保证） */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** JSONB 配置文档；反序列化为 {@link McpSecurityConfigView} */
    private String configJson;

    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
