package com.smart.rag.mcp.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateToolRequest(
        Boolean enabled,
        @Pattern(regexp = "DIRECT_ANSWER|RETRIEVAL|DEEP_RETRIEVAL|GENERAL_TOOL",
                message = "MCP 工具 intent 非法") String intent,
        @Pattern(regexp = "low|high", message = "MCP 工具 risk 非法") String risk,
        @Size(max = 2000, message = "工具描述覆盖不能超过 2000 个字符") String descriptionOverride,
        @PositiveOrZero(message = "版本号不能为负数") Long version
) {}
