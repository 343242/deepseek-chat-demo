package com.smart.rag.mcp.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * MCP 工具配置实体 — 对应 {@code mcp_tool_config} 表（V17 迁移）。
 * <p>
 * <b>prefixedToolName</b>：由 {@code McpToolNamePrefixGenerator.prefixedToolName(connInfo, tool)} 派生
 * （基于 {@code serverInfo.name}），与系统派生的 serverId 同源。{@code DatabaseToolFilter} 主查键。
 * <p>
 * <b>enabled</b>：默认 false（v4 修复 1.4：DB-driven 默认 deny，避免远端新增危险工具自动放行）。
 * <b>version</b>：MyBatis-Plus {@code @Version} 乐观锁。
 */
@TableName("mcp_tool_config")
public class McpToolConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String serverId;

    /** 原始工具名（未前缀） */
    private String toolName;

    /** 前缀全名（server_name + "_" + toolName），DatabaseToolFilter 主查键 */
    private String prefixedToolName;

    private String description;

    /** 默认 false：未入库或新入库的工具需 ADMIN 显式启用 */
    private Boolean enabled;

    /** McpIntent 枚举名：GENERAL_TOOL / RETRIEVAL / DEEP_RETRIEVAL / DIRECT_ANSWER */
    private String intent;

    /** low / high */
    private String risk;

    /** ADMIN 可信描述覆盖（替代远端不可信 description，防 T2 元数据注入） */
    private String descriptionOverride;

    @Version
    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getPrefixedToolName() { return prefixedToolName; }
    public void setPrefixedToolName(String prefixedToolName) { this.prefixedToolName = prefixedToolName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }

    public String getDescriptionOverride() { return descriptionOverride; }
    public void setDescriptionOverride(String descriptionOverride) { this.descriptionOverride = descriptionOverride; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
