package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.runtime.McpClientFactory;
import com.smart.rag.mcp.runtime.McpServerImpl;
import com.smart.rag.mcp.runtime.McpServerRegistryAdmin;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Owns the boundary between Admin server operations and the runtime registry.
 * <p>
 * Holds one instance-local monitor for mutation guard. The monitor covers only the
 * short linearization section containing Registry withdrawal and desired DB mutation.
 * It never covers validation, encryption, remote connect, tools/list, sleep, or retry.
 */
@Component
public class McpServerRuntime {

    private final Object mutationGuard = new Object();
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

    /**
     * Execute a short mutation under the process-local guard.
     * The callback must contain no remote call, sleep, executor wait, or broad sync.
     */
    public <T> T withMutationGuard(Supplier<T> work) {
        synchronized (mutationGuard) {
            return work.get();
        }
    }

    public McpSyncClient connect(McpServerConfig config) {
        return clientFactory.createClient(config);
    }

    public void add(McpServerConfig config, @Nullable McpSyncClient client) {
        registryAdmin.addServer(config, client);
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

    public McpServerImpl withdraw(String serverId) {
        return registryAdmin.withdraw(new ServerId(serverId));
    }

    public void restore(McpServerImpl withdrawn) {
        registryAdmin.restore(withdrawn);
    }

    public boolean removeIfSame(String serverId, McpServerImpl instance) {
        return registryAdmin.removeIfSame(new ServerId(serverId), instance);
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
