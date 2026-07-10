package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.config.McpClientTransportConfiguration.McpClientTransportProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpBootstrapRunnerTest {

    @Test
    void startupConnectsOnlyEnabledAutoConnectRows() throws Exception {
        McpServerConfigMapper mapper = mock(McpServerConfigMapper.class);
        McpServerAdminService serverService = mock(McpServerAdminService.class);
        McpClientTransportProperties properties = new McpClientTransportProperties();
        McpServerConfig config = new McpServerConfig();
        config.setServerId("knowledge");
        when(mapper.selectCount(null)).thenReturn(1L);
        when(mapper.selectAutoConnectEnabled()).thenReturn(List.of(config));

        new McpBootstrapRunner(mapper, properties, serverService).run(null);

        verify(mapper).selectAutoConnectEnabled();
        verify(serverService).initializeAtStartup(config);
    }

    @Test
    void startupDoesNotInsertYamlConnectionsOneByOne() throws Exception {
        McpServerConfigMapper mapper = mock(McpServerConfigMapper.class);
        McpServerAdminService serverService = mock(McpServerAdminService.class);
        McpClientTransportProperties properties = new McpClientTransportProperties();
        McpClientTransportProperties.ConnectionParameters first =
                new McpClientTransportProperties.ConnectionParameters();
        first.setUrl("https://first.example/mcp");
        McpClientTransportProperties.ConnectionParameters second =
                new McpClientTransportProperties.ConnectionParameters();
        second.setUrl("https://second.example/mcp");
        properties.getStreamableHttp().setConnections(Map.of("first", first, "second", second));
        when(mapper.selectCount(null)).thenReturn(0L);
        when(mapper.selectAutoConnectEnabled()).thenReturn(List.of());

        new McpBootstrapRunner(mapper, properties, serverService).run(null);

        verify(mapper, never()).insert(any(McpServerConfig.class));
        verify(mapper).insert(anyCollection());
    }
}
