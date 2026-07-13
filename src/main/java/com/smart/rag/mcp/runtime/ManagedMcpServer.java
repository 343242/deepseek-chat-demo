package com.smart.rag.mcp.runtime;

import com.smart.rag.mcp.core.McpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.lang.Nullable;

/**
 * Lifecycle SPI for {@link McpServer} instances owned by {@link McpServerRegistryImpl}.
 * <p>
 * Exposes instance-identity lifecycle methods that the registry admin needs
 * (withdraw/restore/removeIfSame) without leaking the concrete {@code McpServerImpl}
 * into public interface signatures.
 */
public interface ManagedMcpServer extends McpServer {

    /** Returns true if a real MCP client is attached (non-placeholder). */
    boolean hasClient();

    /** Close the underlying client, swallowing errors. */
    void closeQuietly();

    /** Mark this instance inactive — callbacks captured before withdraw fail fast. */
    void markInactive();

    /** Mark this instance active again (restore). */
    void markActive();

    /** Whether this instance is currently active in the registry snapshot. */
    boolean isActive();

    /** Returns the underlying client, or null if this is a placeholder server. */
    @Nullable
    McpSyncClient getConnectedClient();
}
