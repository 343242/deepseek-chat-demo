package com.smart.rag.mcp.admin.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateServerRequest(
        @Size(max = 2048, message = "MCP Server URL 不能超过 2048 个字符") String url,
        @Size(max = 256, message = "MCP Server 名称不能超过 256 个字符") String name,
        @Size(max = 512, message = "MCP Server 描述不能超过 512 个字符") String description,
        @PositiveOrZero(message = "版本号不能为负数") Long version
) {}
