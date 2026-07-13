package com.smart.rag.mcp.admin.dto;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import org.springframework.lang.Nullable;

/**
 * Server 配置响应（PRD §R3）。
 * <p>
 * Status is a read-time projection via {@code McpConnectionStateProjector}.
 * Never exposes: bearer token, ciphertext, desired/observed hash, stack trace, raw exception.
 */
public record ServerConfigResponse(
        Long id,
        String serverId,
        String displayName,
        String description,
        String url,
        String remoteServerName,
        Boolean enabled,
        Boolean autoConnect,
        Long version,
        @Nullable McpConnectionStatus status,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        @Nullable String lastAttemptAt,
        @Nullable String nextReconcileAt,
        @Nullable String lastConnectedAt,
        @Nullable String createdAt,
        @Nullable String updatedAt
) {
    public static ServerConfigResponse from(McpServerConfig c, @Nullable McpConnectionStatus status) {
        return new ServerConfigResponse(
                c.getId(),
                c.getServerId(),
                c.getName(),
                c.getDescription(),
                c.getUrl(),
                c.getRemoteServerName(),
                c.getEnabled(),
                c.getAutoConnect(),
                c.getVersion(),
                status,
                c.getErrorCode(),
                c.getErrorMessage(),
                c.getLastAttemptAt() != null ? c.getLastAttemptAt().toString() : null,
                c.getNextReconcileAt() != null ? c.getNextReconcileAt().toString() : null,
                c.getLastConnectedAt() != null ? c.getLastConnectedAt().toString() : null,
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null
        );
    }
}
