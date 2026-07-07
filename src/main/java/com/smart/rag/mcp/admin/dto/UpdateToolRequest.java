package com.smart.rag.mcp.admin.dto;

public record UpdateToolRequest(
        Boolean enabled,
        String intent,
        String risk,
        String descriptionOverride,
        Long version
) {}
