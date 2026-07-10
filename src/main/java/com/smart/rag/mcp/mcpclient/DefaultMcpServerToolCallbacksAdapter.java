package com.smart.rag.mcp.mcpclient;

import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.runtime.McpServerImpl;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/** Bridges runtime servers to Spring AI callbacks without leaking starter types into core. */
@Component
public class DefaultMcpServerToolCallbacksAdapter implements McpServerToolCallbacksAdapter {

    @Override
    public List<ToolCallback> toolCallbacks(McpServer server,
                                            DiscoveryOptions options) {
        if (server instanceof McpServerImpl implementation) {
            return implementation.toolCallbacks(options);
        }
        return List.of();
    }
}
