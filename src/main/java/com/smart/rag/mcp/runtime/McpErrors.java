package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.AbstractException;

/**
 * MCP runtime 异常工具，生成可持久化和可展示的安全摘要。
 * <p>
 * 供 MCP Server 生命周期和 registry 共用，
 * 避免多处复制相同的 cause 遍历。
 */
public final class McpErrors {

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
        if (error instanceof AbstractException exception) {
            return exception.getClass().getSimpleName().replace("Exception", "")
                    + "_" + exception.getErrorCode().getCode();
        }
        return "MCP_CONNECT_FAILED";
    }
}
