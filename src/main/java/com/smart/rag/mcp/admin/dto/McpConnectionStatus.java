package com.smart.rag.mcp.admin.dto;

/**
 * Derived connection status: read-time projection, never persisted.
 * PENDING, READY, DEGRADED, or DISABLED.
 */
public enum McpConnectionStatus {
    PENDING,
    READY,
    DEGRADED,
    DISABLED
}
