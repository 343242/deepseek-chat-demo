package com.smart.rag.mcp.config;

import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import com.smart.rag.mcp.mcpclient.McpConnectionInfo;
import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseToolFilterTest {

    private final McpToolConfigAccessor accessor = mock(McpToolConfigAccessor.class);
    private final McpToolNamePrefixGenerator prefixGenerator = (connection, tool) -> "knowledge_search";
    private final McpConnectionInfo connection = McpConnectionInfo.builder().build();
    private final McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("search")
            .inputSchema(java.util.Map.of("type", "object"))
            .build();

    @Test
    void knownToolUsesPersistedEnabledState() {
        when(accessor.get("knowledge_search")).thenReturn(config(true), config(false));
        DatabaseToolFilter filter = new DatabaseToolFilter(accessor, prefixGenerator, true);

        assertThat(filter.test(connection, tool)).isTrue();
        accessor.invalidate("knowledge_search");
        assertThat(filter.test(connection, tool)).isFalse();
    }

    @Test
    void unknownToolIsDeniedInStrictMode() {
        when(accessor.get("knowledge_search")).thenReturn(null);

        assertThat(new DatabaseToolFilter(accessor, prefixGenerator, true).test(connection, tool))
                .isFalse();
    }

    @Test
    void unknownToolCanBeAllowedOnlyInExplicitLenientMode() {
        when(accessor.get("knowledge_search")).thenReturn(null);

        assertThat(new DatabaseToolFilter(accessor, prefixGenerator, false).test(connection, tool))
                .isTrue();
    }

    private static McpToolConfig config(boolean enabled) {
        McpToolConfig config = new McpToolConfig();
        config.setEnabled(enabled);
        return config;
    }
}
