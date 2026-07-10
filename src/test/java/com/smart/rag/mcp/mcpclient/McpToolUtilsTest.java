package com.smart.rag.mcp.mcpclient;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.mcp.config.McpClientConfiguration;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolUtilsTest {

    @Test
    void prefixedToolNameUsesCanonicalSegments() {
        assertEquals("Knowledge_Base_search_docs",
                McpToolUtils.prefixedToolName("  Knowledge--Base  ", " search / docs "));
    }

    @Test
    void prefixedToolNameRejectsEmptyCanonicalSegment() {
        assertThrows(ClientException.class,
                () -> McpToolUtils.prefixedToolName("***", "search"));
        assertThrows(ClientException.class,
                () -> McpToolUtils.prefixedToolName("knowledge", "  /  "));
    }

    @Test
    void prefixedToolNameIsStableAndBoundedForLongInputs() {
        String server = "knowledge_server_with_a_name_that_is_longer_than_expected";
        String first = McpToolUtils.prefixedToolName(server,
                "search_documents_with_a_long_tail_that_differs_at_the_end_a");
        String repeated = McpToolUtils.prefixedToolName(server,
                "search_documents_with_a_long_tail_that_differs_at_the_end_a");
        String second = McpToolUtils.prefixedToolName(server,
                "search_documents_with_a_long_tail_that_differs_at_the_end_b");

        assertEquals(first, repeated);
        assertTrue(first.length() <= 64);
        assertTrue(first.startsWith("knowledge_server"));
        assertNotEquals(first, second);
    }

    @Test
    void configuredPrefixGeneratorUsesTheSameCanonicalContract() {
        String serverName = "knowledge_server_with_a_name_that_is_longer_than_expected";
        String toolName = "search_documents_with_a_long_tail_that_differs_at_the_end_a";
        McpSchema.InitializeResult initialization = org.mockito.Mockito.mock(McpSchema.InitializeResult.class);
        org.mockito.Mockito.when(initialization.serverInfo())
                .thenReturn(new McpSchema.Implementation(serverName, "1.0"));
        McpConnectionInfo connection = McpConnectionInfo.builder().initializeResult(initialization).build();
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(toolName)
                .inputSchema(java.util.Map.of("type", "object"))
                .build();

        String generated = new McpClientConfiguration().mcpToolNamePrefixGenerator()
                .prefixedToolName(connection, tool);

        assertEquals(McpToolUtils.prefixedToolName(serverName, toolName), generated);
        assertTrue(generated.length() <= 64);
    }

    @Test
    void canonicalServerIdKeepsLongServerNamesWithinTheToolNamespace() {
        String remoteName = "server_" + "x".repeat(200);

        String serverId = McpToolUtils.canonicalServerId(remoteName);
        String toolName = McpToolUtils.prefixedToolName(remoteName, "search");

        assertTrue(serverId.length() <= 48);
        assertTrue(toolName.startsWith(serverId + "_"));
    }
}
