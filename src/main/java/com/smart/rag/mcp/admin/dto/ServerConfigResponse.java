package com.smart.rag.mcp.admin.dto;

import com.smart.rag.mcp.admin.entity.McpServerConfig;

/**
 * Server 配置响应（含 health 状态 + version）。
 * <p>
 * {@code health} 从 registry.find() 取 initError 派生；{@code initError} 非空 = DOWN。
 */
public record ServerConfigResponse(
        Long id,
        String serverId,
        String url,
        String name,
        String description,
        Boolean enabled,
        Boolean autoConnect,
        boolean hasBearerToken,
        String initError,
        String lastConnectedAt,
        Long version,
        String createdAt,
        String updatedAt,
        String health
) {
    public static ServerConfigResponse from(McpServerConfig c, String health) {
        return new ServerConfigResponse(
                c.getId(),
                c.getServerId(),
                c.getUrl(),
                c.getName(),
                c.getDescription(),
                c.getEnabled(),
                c.getAutoConnect(),
                c.getBearerTokenEncrypted() != null && !c.getBearerTokenEncrypted().isBlank(),
                c.getInitError(),
                c.getLastConnectedAt() != null ? c.getLastConnectedAt().toString() : null,
                c.getVersion(),
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null,
                health
        );
    }
}
