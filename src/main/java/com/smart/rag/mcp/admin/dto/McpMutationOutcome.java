package com.smart.rag.mcp.admin.dto;

/**
 * Mutation outcome enum for Admin API responses.
 * SUCCESS = completed local work; ACCEPTED = background MCP I/O remains.
 */
public enum McpMutationOutcome {
    SUCCESS,
    ACCEPTED
}
