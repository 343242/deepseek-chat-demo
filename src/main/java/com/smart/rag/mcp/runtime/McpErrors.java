package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.AbstractException;

/**
 * MCP runtime 异常工具，生成可持久化和可展示的安全摘要。
 * <p>
 * 供 MCP Server 生命周期和 registry 共用，
 * 避免多处复制相同的 cause 遍历。
 */
public final class McpErrors {

    public static final String CATALOG_SYNC_FAILED_CODE = "MCP_CATALOG_SYNC_FAILED";
    public static final String CATALOG_SYNC_FAILED_MESSAGE = "MCP 工具目录同步失败";

    private McpErrors() {
    }

    public static String safeSummary(Throwable error) {
        if (error instanceof AbstractException exception) {
            return exception.getErrorCode().getMessage();
        }
        return "MCP Server 连接或协议交互失败";
    }

    /**
     * Stable allowlisted error code for persistence.
     */
    public static String safeCode(Throwable error) {
        if (error instanceof com.smart.rag.infrastructure.exception.RemoteException re) {
            var code = re.getErrorCode();
            if (code == com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode.MCP_SERVER_UNREACHABLE) {
                return "MCP_CONNECT_FAILED";
            }
            if (code == com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode.MCP_CIRCUIT_BREAKER_OPEN) {
                return "MCP_CIRCUIT_OPEN";
            }
            return "MCP_REMOTE_ERROR";
        }
        if (error instanceof com.smart.rag.infrastructure.exception.ServiceException) {
            return "MCP_SERVICE_ERROR";
        }
        if (error instanceof com.smart.rag.infrastructure.exception.ClientException) {
            return "MCP_CLIENT_ERROR";
        }
        return "MCP_CONNECT_FAILED";
    }
}
