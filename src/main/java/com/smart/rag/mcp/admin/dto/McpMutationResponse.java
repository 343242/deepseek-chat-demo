package com.smart.rag.mcp.admin.dto;

/**
 * Exact Admin mutation response contract (PRD §R3).
 * No PARTIAL_SUCCESS, failure-domain, operation ID, or separate catalog error surface.
 */
public record McpMutationResponse(
        String resourceId,
        McpMutationOutcome outcome,
        McpConnectionStatus status,
        String errorCode,
        String errorMessage
) {
    public static McpMutationResponse accepted(String resourceId, McpConnectionStatus status) {
        return new McpMutationResponse(resourceId, McpMutationOutcome.ACCEPTED, status, null, null);
    }

    public static McpMutationResponse success(String resourceId, McpConnectionStatus status) {
        return new McpMutationResponse(resourceId, McpMutationOutcome.SUCCESS, status, null, null);
    }

    public static McpMutationResponse successNullStatus(String resourceId) {
        return new McpMutationResponse(resourceId, McpMutationOutcome.SUCCESS, null, null, null);
    }
}
