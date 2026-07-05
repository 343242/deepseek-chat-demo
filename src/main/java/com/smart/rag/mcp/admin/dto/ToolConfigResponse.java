package com.smart.rag.mcp.admin.dto;

import com.smart.rag.mcp.admin.entity.McpToolConfig;

public record ToolConfigResponse(
        Long id,
        String serverId,
        String toolName,
        String prefixedToolName,
        String description,
        Boolean enabled,
        String intent,
        String risk,
        String descriptionOverride,
        Long version
) {
    public static ToolConfigResponse from(McpToolConfig t) {
        return new ToolConfigResponse(
                t.getId(),
                t.getServerId(),
                t.getToolName(),
                t.getPrefixedToolName(),
                t.getDescription(),
                t.getEnabled(),
                t.getIntent(),
                t.getRisk(),
                t.getDescriptionOverride(),
                t.getVersion()
        );
    }
}
