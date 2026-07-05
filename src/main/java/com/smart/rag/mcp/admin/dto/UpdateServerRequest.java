package com.smart.rag.mcp.admin.dto;

public record UpdateServerRequest(
        String url,
        String name,
        String description,
        Long version
) {}
