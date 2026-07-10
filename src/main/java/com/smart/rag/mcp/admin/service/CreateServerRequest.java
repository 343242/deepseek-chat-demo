package com.smart.rag.mcp.admin.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建 MCP Server 连接请求；serverId 由握手身份信息派生。
 */
public record CreateServerRequest(
        @NotBlank(message = "MCP Server URL 不能为空")
        @Size(max = 2048, message = "MCP Server URL 不能超过 2048 个字符") String url,
        @Size(max = 256, message = "MCP Server 名称不能超过 256 个字符") String name,
        @Size(max = 512, message = "MCP Server 描述不能超过 512 个字符") String description,
        Boolean autoConnect,
        @Size(max = 8192, message = "Bearer Token 不能超过 8192 个字符")
        @Pattern(regexp = "(?s).*\\S.*", message = "Bearer Token 不能只包含空白字符") String bearerToken
) {}
