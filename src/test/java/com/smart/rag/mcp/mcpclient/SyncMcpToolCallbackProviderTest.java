package com.smart.rag.mcp.mcpclient;

import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.ServerId;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncMcpToolCallbackProviderTest {

    @Test
    void discoveryFailureOnOneServerDoesNotHideHealthyServerTools() {
        McpServerRegistry registry = mock(McpServerRegistry.class);
        McpServer failed = mock(McpServer.class);
        McpServer healthy = mock(McpServer.class);
        when(failed.id()).thenReturn(new ServerId("failed"));
        when(healthy.id()).thenReturn(new ServerId("healthy"));
        when(registry.list()).thenReturn(List.of(failed, healthy));

        McpServerToolCallbacksAdapter adapter = mock(McpServerToolCallbacksAdapter.class);
        when(adapter.toolCallbacks(org.mockito.ArgumentMatchers.eq(failed), any()))
                .thenThrow(new RuntimeException("connection refused"));
        ToolCallback callback = callback("healthy_search");
        when(adapter.toolCallbacks(org.mockito.ArgumentMatchers.eq(healthy), any()))
                .thenReturn(List.of(callback));

        SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(
                (connection, tool) -> true,
                (connection, tool) -> tool.name(),
                registry,
                adapter,
                ToolContextToMcpMetaConverter.defaultConverter());

        assertThat(provider.getToolCallbacks()).containsExactly(callback);
    }

    @Test
    void cachedCallbacksCannotBeModifiedByCallers() {
        McpServerRegistry registry = mock(McpServerRegistry.class);
        McpServer server = mock(McpServer.class);
        when(server.id()).thenReturn(new ServerId("healthy"));
        when(registry.list()).thenReturn(List.of(server));
        ToolCallback original = callback("healthy_search");
        McpServerToolCallbacksAdapter adapter = mock(McpServerToolCallbacksAdapter.class);
        when(adapter.toolCallbacks(org.mockito.ArgumentMatchers.eq(server), any()))
                .thenReturn(List.of(original));
        SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(
                (connection, tool) -> true,
                (connection, tool) -> tool.name(),
                registry,
                adapter,
                ToolContextToMcpMetaConverter.defaultConverter());

        ToolCallback[] first = provider.getToolCallbacks();
        first[0] = callback("mutated");

        assertThat(provider.getToolCallbacks()).containsExactly(original);
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn(name);
        return callback;
    }
}
