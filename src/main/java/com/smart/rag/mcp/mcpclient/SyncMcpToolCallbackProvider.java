package com.smart.rag.mcp.mcpclient;

import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.runtime.McpServerRegistryAdmin;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.support.ToolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** Discovers Spring AI tool callbacks from the live MCP server registry. */
public class SyncMcpToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(SyncMcpToolCallbackProvider.class);

    private final McpServerRegistry registry;
    private final McpServerToolCallbacksAdapter adapter;
    private final McpServerToolCallbacksAdapter.DiscoveryOptions discoveryOptions;

    private volatile CachedCallbacks cache;
    private final Lock lock = new ReentrantLock();

    public SyncMcpToolCallbackProvider(McpToolFilter toolFilter,
                                       McpToolNamePrefixGenerator toolNamePrefixGenerator,
                                       McpServerRegistry registry,
                                       McpServerToolCallbacksAdapter adapter,
                                       ToolContextToMcpMetaConverter toolContextToMcpMetaConverter) {
        McpToolFilter effectiveFilter = toolFilter != null ? toolFilter : (connInfo, tool) -> true;
        McpToolNamePrefixGenerator effectivePrefix = toolNamePrefixGenerator != null ? toolNamePrefixGenerator
                : new DefaultMcpToolNamePrefixGenerator();
        ToolContextToMcpMetaConverter effectiveConverter = toolContextToMcpMetaConverter != null
                ? toolContextToMcpMetaConverter
                : ToolContextToMcpMetaConverter.defaultConverter();
        this.registry = registry;
        this.adapter = adapter;
        this.discoveryOptions = new McpServerToolCallbacksAdapter.DiscoveryOptions(
                effectiveFilter, effectivePrefix, effectiveConverter);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        long currentVersion = registry instanceof McpServerRegistryAdmin admin ? admin.currentVersion() : 0L;
        CachedCallbacks cached = cache;
        if (cached != null && cached.version() == currentVersion) {
            return cached.copy();
        }
        lock.lock();
        try {
            cached = cache;
            if (cached != null && cached.version() == currentVersion) {
                return cached.copy();
            }
            List<ToolCallback> callbacks = new ArrayList<>();
            for (McpServer server : registry.list()) {
                try {
                    callbacks.addAll(adapter.toolCallbacks(server, discoveryOptions));
                } catch (RuntimeException e) {
                    log.warn("MCP Server 工具发现失败，serverId={}, errorType={}",
                            server.id().value(), e.getClass().getSimpleName());
                }
            }
            ToolCallback[] result = validateAndToArray(callbacks);
            cache = new CachedCallbacks(currentVersion, result);
            return result.clone();
        } finally {
            lock.unlock();
        }
    }

    /** 强制下次 {@link #getToolCallbacks()} 重新发现工具 */
    public void invalidateCache() {
        lock.lock();
        try {
            cache = null;
        } finally {
            lock.unlock();
        }
    }

    private ToolCallback[] validateAndToArray(List<ToolCallback> callbacks) {
        List<String> duplicateToolNames = ToolUtils.getDuplicateToolNames(callbacks);
        if (!duplicateToolNames.isEmpty()) {
            throw new IllegalStateException(
                    "Multiple tools with the same name (%s)".formatted(String.join(", ", duplicateToolNames)));
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    private record CachedCallbacks(long version, ToolCallback[] callbacks) {

        private ToolCallback[] copy() {
            return callbacks.clone();
        }
    }

}
