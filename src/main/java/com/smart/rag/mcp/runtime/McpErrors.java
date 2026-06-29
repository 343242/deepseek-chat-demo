package com.smart.rag.mcp.runtime;

/**
 * MCP runtime 异常工具（包私有）——提取 root-cause 消息供日志/错误详情复用（DRY）。
 * <p>
 * 供 {@link McpServerImpl} 与 {@link McpServerRegistryImpl} 共用，避免两处复制相同的 cause 遍历。
 */
final class McpErrors {

    private McpErrors() {
    }

    /**
     * 取异常链 root cause 的类名 + 消息（防御自引用 cause 导致死循环）。
     */
    static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
