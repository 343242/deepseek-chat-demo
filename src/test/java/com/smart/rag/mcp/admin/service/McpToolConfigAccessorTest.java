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
    void directReadReturnsCommittedRow() {
        McpToolConfigMapper mapper = mock(McpToolConfigMapper.class);
        McpToolConfig config = new McpToolConfig();
        config.setEnabled(true);
        when(mapper.selectByPrefixedName("mcp_1_search")).thenReturn(config);

        McpToolConfigAccessor accessor = new McpToolConfigAccessor(mapper);

        assertThat(accessor.get("mcp_1_search")).isSameAs(config);
        verify(mapper).selectByPrefixedName("mcp_1_search");
    }

    @Test
    void unknownToolReturnsNull() {
        McpToolConfigMapper mapper = mock(McpToolConfigMapper.class);
        when(mapper.selectByPrefixedName("unknown")).thenReturn(null);

        McpToolConfigAccessor accessor = new McpToolConfigAccessor(mapper);

        assertThat(accessor.get("unknown")).isNull();
    }
}
