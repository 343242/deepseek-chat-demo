package com.smart.rag.mcp.admin.dto;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import org.springframework.lang.Nullable;

import java.time.OffsetDateTime;

/**
 * Server 配置响应（PRD §R3）。
 * <p>
 * Status is a read-time projection via {@code McpConnectionStateProjector}.
 * Never exposes: bearer token, ciphertext, desired/observed hash, stack trace, raw exception.
 * <p>
 * 时间字段为 {@link OffsetDateTime}，由全局 Jackson 配置统一格式化为 {@code yyyy-MM-dd HH:mm:ss}。
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
        @Nullable OffsetDateTime lastAttemptAt,
        @Nullable OffsetDateTime nextReconcileAt,
        @Nullable OffsetDateTime lastConnectedAt,
        @Nullable OffsetDateTime createdAt,
        @Nullable OffsetDateTime updatedAt
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
                c.getLastAttemptAt(),
                c.getNextReconcileAt(),
                c.getLastConnectedAt(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
