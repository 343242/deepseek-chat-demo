package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.config.McpClientTransportConfiguration.McpClientTransportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class McpBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpBootstrapRunner.class);

    private final McpServerConfigMapper serverConfigMapper;
    private final McpClientTransportProperties transportProperties;
    private final McpServerAdminService serverAdminService;

    public McpBootstrapRunner(McpServerConfigMapper serverConfigMapper,
                              McpClientTransportProperties transportProperties,
                              McpServerAdminService serverAdminService) {
        this.serverConfigMapper = serverConfigMapper;
        this.transportProperties = transportProperties;
        this.serverAdminService = serverAdminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (serverConfigMapper.selectCount(null) == 0) {
            importYamlConnections();
        }
        serverConfigMapper.selectAutoConnectEnabled().forEach(serverAdminService::initializeAtStartup);
    }

    private void importYamlConnections() {
        McpClientTransportProperties.StreamableHttp streamable = transportProperties.getStreamableHttp();
        if (streamable == null || streamable.getConnections() == null) {
            return;
        }
        List<McpServerConfig> rows = new ArrayList<>();
        for (Map.Entry<String, McpClientTransportProperties.ConnectionParameters> entry
                : streamable.getConnections().entrySet()) {
            McpClientTransportProperties.ConnectionParameters connection = entry.getValue();
            if (connection == null || connection.getUrl() == null || connection.getUrl().isBlank()) {
                continue;
            }
            McpServerConfig row = new McpServerConfig();
            row.setUrl(connection.getUrl().trim());
            row.setName(entry.getKey());
            row.setEnabled(true);
            row.setAutoConnect(true);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            serverConfigMapper.insert(rows);
        }
        log.info("MCP 启动配置导入完成，连接数={}", rows.size());
    }
}
