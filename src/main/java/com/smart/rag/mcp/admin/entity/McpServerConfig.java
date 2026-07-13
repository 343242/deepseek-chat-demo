package com.smart.rag.mcp.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.OffsetDateTime;

/**
 * MCP Server 配置实体 — 对应 {@code mcp_server_config} 表（V19 重建）。
 * <p>
 * PostgreSQL is the sole connection source. A committed row is durable desired state;
 * in-memory clients are disposable observations.
 * <p>
 * <b>serverId</b>: stable local identity {@code mcp_<row-id>}, assigned when the row is
 * created. Remote Server name is informational only.
 * <b>desiredStateHash</b>: SHA-256 of canonical URL + encrypted envelope/null + enabled.
 * <b>observedStateHash</b>: hash of the currently published client; null = no trusted observation.
 * <b>catalogSynced</b>: complete tool catalog committed for desired state.
 * <b>version</b>: MyBatis-Plus {@code @Version} optimistic lock — desired writes bump it,
 * observed writes do not.
 *
 * @see com.smart.rag.mcp.runtime.McpDesiredStateHasher
 */
@TableName("mcp_server_config")
public class McpServerConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Stable local identity mcp_<row-id> */
    private String serverId;

    /** Informational MCP initialize result */
    private String remoteServerName;

    private String url;

    /** ADMIN display name (does not affect serverId or tool prefix) */
    private String name;

    private String description;

    private Boolean enabled;

    private Boolean autoConnect;

    /** v2:<keyId>:<cipher>:<iv> encrypted envelope */
    private String bearerTokenEncrypted;

    /** SHA-256 of canonical desired fields */
    private String desiredStateHash;

    /** Hash of currently published client; null = no trusted observation */
    private String observedStateHash;

    /** Complete tool catalog committed for desired state */
    private Boolean catalogSynced;

    /** Stable allowlisted current failure code */
    private String errorCode;

    /** Safe Chinese message from allowlist */
    private String errorMessage;

    /** Recovery backoff input */
    private Integer consecutiveFailures;

    /** Durable due time for reconciliation; null = no scheduled work */
    private OffsetDateTime nextReconcileAt;

    /** Latest background attempt */
    private OffsetDateTime lastAttemptAt;

    /** Latest applied connection */
    private OffsetDateTime lastConnectedAt;

    /** Create idempotency key; lives as long as the row */
    private String createRequestKey;

    @Version
    private Long version;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }

    public String getRemoteServerName() { return remoteServerName; }
    public void setRemoteServerName(String remoteServerName) { this.remoteServerName = remoteServerName; }

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

    public String getDesiredStateHash() { return desiredStateHash; }
    public void setDesiredStateHash(String desiredStateHash) { this.desiredStateHash = desiredStateHash; }

    public String getObservedStateHash() { return observedStateHash; }
    public void setObservedStateHash(String observedStateHash) { this.observedStateHash = observedStateHash; }

    public Boolean getCatalogSynced() { return catalogSynced; }
    public void setCatalogSynced(Boolean catalogSynced) { this.catalogSynced = catalogSynced; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }

    public OffsetDateTime getNextReconcileAt() { return nextReconcileAt; }
    public void setNextReconcileAt(OffsetDateTime nextReconcileAt) { this.nextReconcileAt = nextReconcileAt; }

    public OffsetDateTime getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(OffsetDateTime lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }

    public OffsetDateTime getLastConnectedAt() { return lastConnectedAt; }
    public void setLastConnectedAt(OffsetDateTime lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }

    public String getCreateRequestKey() { return createRequestKey; }
    public void setCreateRequestKey(String createRequestKey) { this.createRequestKey = createRequestKey; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
