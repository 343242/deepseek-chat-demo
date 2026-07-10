package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.runtime.McpClientFactory;
import com.smart.rag.mcp.runtime.McpServerRegistryAdmin;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Owns the boundary between Admin server operations and the runtime registry. */
@Component
public class McpServerRuntime {

    private final McpClientFactory clientFactory;
    private final McpServerRegistryAdmin registryAdmin;
    private final McpServerRegistry registry;

    public McpServerRuntime(McpClientFactory clientFactory,
                            McpServerRegistryAdmin registryAdmin,
                            McpServerRegistry registry) {
        this.clientFactory = clientFactory;
        this.registryAdmin = registryAdmin;
        this.registry = registry;
    }

    public McpSyncClient connect(McpServerConfig config) {
        return clientFactory.createClient(config);
    }

    public void add(McpServerConfig config, @Nullable McpSyncClient client, @Nullable String initError) {
        registryAdmin.addServer(config, client, initError);
    }

    public void replace(McpServerConfig config, McpSyncClient client) {
        registryAdmin.replaceServer(config, client);
    }

    public void remove(String serverId) {
        registryAdmin.removeServer(new ServerId(serverId));
    }

    public void close(@Nullable McpSyncClient client) {
        clientFactory.destroyClient(client);
    }

    public Optional<McpServer> find(String serverId) {
        return registry.find(new ServerId(serverId));
    }

    public String health(String serverId) {
        return find(serverId)
                .map(McpServer::health)
                .map(health -> health.status().name())
                .orElse("UNKNOWN");
    }
}
