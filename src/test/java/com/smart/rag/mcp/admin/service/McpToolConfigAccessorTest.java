package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolConfigAccessorTest {

    @Test
    void unknownToolLookupIsNegativelyCachedUntilInvalidated() {
        McpToolConfigMapper mapper = mock(McpToolConfigMapper.class);
        when(mapper.selectByPrefixedName("unknown_tool")).thenReturn(null);
        McpToolConfigAccessor accessor = new McpToolConfigAccessor(mapper);

        assertThat(accessor.get("unknown_tool")).isNull();
        assertThat(accessor.get("unknown_tool")).isNull();
        verify(mapper).selectByPrefixedName("unknown_tool");

        accessor.invalidate("unknown_tool");
        McpToolConfig config = new McpToolConfig();
        config.setEnabled(true);
        when(mapper.selectByPrefixedName("unknown_tool")).thenReturn(config);
        assertThat(accessor.get("unknown_tool")).isSameAs(config);
    }
}
