package com.smart.rag.mcp.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * MCP Server 配置实体 — 对应 {@code mcp_server_config} 表（V17 迁移）。
 * <p>
 * <b>serverId</b>：系统派生（{@code McpToolUtils.format(initializeResult.serverInfo().name())}），
 * ADMIN 不可改——这与 {@code McpToolNamePrefixGenerator} 派生的工具前缀同源，
 * 保证 {@code DatabaseToolFilter} 按 {@code prefixed_tool_name} 查询命中。
 * INSERT 时可为 NULL（握手未完成），UPDATE 回填后非 NULL。
 * <p>
 * <b>bearerTokenEncrypted</b>：SecretCipher AES/GCM 密文（per-server 粒度），更新触发 client 重建。
 * <b>initError</b>：软失败语义——client 创建/握手失败时记录原因，registry 中保留占位 server。
 * <b>version</b>：MyBatis-Plus {@code @Version} 乐观锁，{@code OptimisticLockerInnerInterceptor} 拦截。
 *
 * @see com.smart.rag.infrastructure.security.SecretCipher
 */
@TableName("mcp_server_config")
public class McpServerConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 系统派生；握手前 NULL，回填后非 NULL。失败时用合成 id {@code unreachable-<rowId>} */
    private String serverId;

    private String url;

    /** ADMIN 可改的展示名（不影响 serverId / 工具前缀） */
    private String name;

    private String description;

    private Boolean enabled;

    private Boolean autoConnect;

    private String bearerTokenEncrypted;

    /** 软失败：client 创建/握手失败原因；非空时 health=down，工具调用返回友好错误 */
    private String initError;

    private LocalDateTime lastConnectedAt;

    @Version
    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getAutoConnect() { return autoConnect; }
    public void setAutoConnect(Boolean autoConnect) { this.autoConnect = autoConnect; }

    public String getBearerTokenEncrypted() { return bearerTokenEncrypted; }
    public void setBearerTokenEncrypted(String bearerTokenEncrypted) { this.bearerTokenEncrypted = bearerTokenEncrypted; }

    public String getInitError() { return initError; }
    public void setInitError(String initError) { this.initError = initError; }

    public LocalDateTime getLastConnectedAt() { return lastConnectedAt; }
    public void setLastConnectedAt(LocalDateTime lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
