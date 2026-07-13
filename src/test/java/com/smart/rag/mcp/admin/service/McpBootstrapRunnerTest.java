package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.runtime.McpConnectionRecoveryScheduler;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class McpBootstrapRunnerTest {

    @Test
    void startupTriggersRecoveryScanWhenServersExist() {
        McpServerConfigMapper mapper = mock(McpServerConfigMapper.class);
        McpConnectionRecoveryScheduler scheduler = mock(McpConnectionRecoveryScheduler.class);
        when(mapper.selectCount(null)).thenReturn(3L);

        new McpBootstrapRunner(mapper, scheduler).run(null);

        verify(scheduler).scan();
    }

    @Test
    void startupWithEmptyDbDoesNotScan() {
        McpServerConfigMapper mapper = mock(McpServerConfigMapper.class);
        McpConnectionRecoveryScheduler scheduler = mock(McpConnectionRecoveryScheduler.class);
        when(mapper.selectCount(null)).thenReturn(0L);

        new McpBootstrapRunner(mapper, scheduler).run(null);

        verify(scheduler, never()).scan();
    }
}
