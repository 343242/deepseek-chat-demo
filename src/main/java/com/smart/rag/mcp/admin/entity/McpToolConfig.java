package com.smart.rag.mcp.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.OffsetDateTime;

/**
 * MCP 工具配置实体 — 对应 {@code mcp_tool_config} 表（V19 重建）。
 * <p>
 * <b>prefixedToolName</b>：由 {@code McpToolUtils.prefixedToolName(localServerId, rawName)} 派生，
 * 基于 DB row ID 生成的 {@code mcp_<row-id>} local identity。
 * <b>enabled</b>：默认 false（DB-driven 默认 deny）。
 * <b>present</b>：true=当前 catalog 中存在；false=曾经见过现已缺失（catalog 对账标记）。
 * <b>inputSchema</b>：MCP inputSchema JSON 对象（≤64KiB/tool）。
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
    /** true=当前 catalog 中存在；false=曾经见过现已缺失 */
    private Boolean present = true;

    private OffsetDateTime lastSeenAt;

    /** MCP inputSchema JSON 对象（≤64KiB/tool） */
    private String inputSchema;

    @Version
    private Long version;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

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
    public Boolean getPresent() { return present; }
    public void setPresent(Boolean present) { this.present = present; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
