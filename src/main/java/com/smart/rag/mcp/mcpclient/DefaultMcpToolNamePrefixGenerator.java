package com.smart.rag.mcp.mcpclient;

import io.modelcontextprotocol.spec.McpSchema;

/** Default generator backed by the module-wide canonical naming contract. */
public class DefaultMcpToolNamePrefixGenerator implements McpToolNamePrefixGenerator {

    @Override
    public String prefixedToolName(McpConnectionInfo connectionInfo, McpSchema.Tool tool) {
        return McpToolUtils.prefixedToolName(connectionInfo, tool);
    }
}
