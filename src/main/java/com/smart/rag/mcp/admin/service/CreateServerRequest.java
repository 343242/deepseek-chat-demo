package com.smart.rag.mcp.admin.service;

/**
 * 创建 MCP Server 连接请求（v4 B4：无 serverId 字段，系统握手后派生）。
 */
public record CreateServerRequest(
        String url,
        String name,
        String description,
        Boolean autoConnect,
        String bearerToken
) {}
