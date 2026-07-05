package com.smart.rag.mcp.mcpclient;

import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.runtime.McpServerRegistryAdmin;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 从 {@link McpServerRegistry} 发现工具并组装为 Spring AI {@link ToolCallback}（v4 B2 重构）。
 * <p>
 * <b>v3 → v4 关键变更</b>：
 * <ul>
 *   <li>构造改为注入 {@link McpServerRegistry}（只读）+ {@link McpServerToolCallbacksAdapter}（runtime 层），</li>
 *   <li>不再持有 {@code List<McpSyncClient>}（v3 的静态注入已废弃）</li>
 *   <li>缓存 key 加 registry 版本号，版本变更触发重新发现</li>
 *   <li>{@code McpServerImpl}（runtime）实现 {@link McpServerToolCallbacksAdapter}，per-server 工具发现下沉到 impl 内</li>
 * </ul>
 * <p>
 * <b>原 List&lt;McpSyncClient&gt; 构造保留</b>：用于 Phase 2.6 之前 Spring Boot 装配兼容
 * （{@code McpClientTransportConfiguration.syncMcpToolCallbackProvider} 仍传 List）。
 * Phase 2.6 删除 mcpSyncClients bean 后，统一用新构造。
 * <p>
 * 参照 Spring AI 2.0.0 {@code SyncMcpToolCallbackProvider}，省略
 * {@code ApplicationListener<McpToolsChangedEvent>}（主动拉取策略，不依赖 list_changed 事件）。
 *
 * @author Christian Tzolov, YunKui Lu（原 Spring AI）
 */
public class SyncMcpToolCallbackProvider implements ToolCallbackProvider {

    private final McpToolFilter toolFilter;
    private final McpToolNamePrefixGenerator toolNamePrefixGenerator;
    private final ToolContextToMcpMetaConverter toolContextToMcpMetaConverter;

    /** v4：registry 驱动（优先级高于 mcpClients） */
    private final McpServerRegistry registry;
    private final McpServerToolCallbacksAdapter adapter;

    /** v3 兼容：List<McpSyncClient> 直驱（registry=null 时使用，Phase 2.6 后废弃） */
    private final List<McpSyncClient> mcpClients;

    private volatile long cachedVersion = -1L;
    private volatile ToolCallback[] cachedCallbacks;
    private final Lock lock = new ReentrantLock();

    /** v3 兼容构造（保留给 McpClientTransportConfiguration 旧装配路径用） */
    public SyncMcpToolCallbackProvider(List<McpSyncClient> mcpClients) {
        this((McpToolFilter) null, null, mcpClients, null, null, null);
    }

    public SyncMcpToolCallbackProvider(McpToolFilter toolFilter, List<McpSyncClient> mcpClients) {
        this(toolFilter, null, mcpClients, null, null, null);
    }

    public SyncMcpToolCallbackProvider(McpToolFilter toolFilter,
                                       McpToolNamePrefixGenerator toolNamePrefixGenerator,
                                       List<McpSyncClient> mcpClients,
                                       ToolContextToMcpMetaConverter toolContextToMcpMetaConverter) {
        this(toolFilter, toolNamePrefixGenerator, mcpClients, null, null, toolContextToMcpMetaConverter);
    }

    /** v4 主构造：registry + adapter 驱动 */
    public SyncMcpToolCallbackProvider(McpToolFilter toolFilter,
                                       McpToolNamePrefixGenerator toolNamePrefixGenerator,
                                       McpServerRegistry registry,
                                       McpServerToolCallbacksAdapter adapter,
                                       ToolContextToMcpMetaConverter toolContextToMcpMetaConverter) {
        this(toolFilter, toolNamePrefixGenerator, null, registry, adapter, toolContextToMcpMetaConverter);
    }

    private SyncMcpToolCallbackProvider(McpToolFilter toolFilter,
                                        McpToolNamePrefixGenerator toolNamePrefixGenerator,
                                        List<McpSyncClient> mcpClients,
                                        McpServerRegistry registry,
                                        McpServerToolCallbacksAdapter adapter,
                                        ToolContextToMcpMetaConverter metaConverter) {
        this.toolFilter = toolFilter != null ? toolFilter : (connInfo, tool) -> true;
        this.toolNamePrefixGenerator = toolNamePrefixGenerator != null ? toolNamePrefixGenerator
                : new DefaultMcpToolNamePrefixGenerator();
        this.toolContextToMcpMetaConverter = metaConverter != null ? metaConverter
                : ToolContextToMcpMetaConverter.defaultConverter();
        this.mcpClients = mcpClients;
        this.registry = registry;
        this.adapter = adapter;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        if (registry != null && adapter != null) {
            return getToolCallbacksViaRegistry();
        }
        return getToolCallbacksViaClientList();
    }

    private ToolCallback[] getToolCallbacksViaRegistry() {
        long currentVersion = registry instanceof McpServerRegistryAdmin admin ? admin.currentVersion() : 0L;
        ToolCallback[] cached = cachedCallbacks;
        if (currentVersion == cachedVersion && cached != null) {
            return cached;
        }
        lock.lock();
        try {
            if (currentVersion == cachedVersion && cachedCallbacks != null) {
                return cachedCallbacks;
            }
            List<ToolCallback> callbacks = new ArrayList<>();
            for (McpServer server : registry.list()) {
                callbacks.addAll(adapter.toolCallbacks(server, toolFilter, toolNamePrefixGenerator,
                        toolContextToMcpMetaConverter));
            }
            ToolCallback[] result = validateAndToArray(callbacks);
            cachedVersion = currentVersion;
            cachedCallbacks = result;
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** v3 兼容路径：从 List&lt;McpSyncClient&gt; 直驱发现工具 */
    private ToolCallback[] getToolCallbacksViaClientList() {
        if (CollectionUtils.isEmpty(mcpClients)) {
            return new ToolCallback[0];
        }
        List<ToolCallback> callbacks = mcpClients.stream()
                .flatMap(mcpClient -> mcpClient.listTools().tools().stream()
                        .filter(tool -> toolFilter.test(connectionInfo(mcpClient), tool))
                        .<ToolCallback>map(tool -> SyncMcpToolCallback.builder()
                                .mcpClient(mcpClient)
                                .tool(tool)
                                .prefixedToolName(toolNamePrefixGenerator.prefixedToolName(connectionInfo(mcpClient), tool))
                                .toolContextToMcpMetaConverter(toolContextToMcpMetaConverter)
                                .build()))
                .toList();
        return validateAndToArray(callbacks);
    }

    /** 强制下次 {@link #getToolCallbacks()} 重新发现工具 */
    public void invalidateCache() {
        lock.lock();
        try {
            cachedVersion = -1L;
            cachedCallbacks = null;
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

    private static McpConnectionInfo connectionInfo(McpSyncClient mcpClient) {
        return McpConnectionInfo.builder()
                .clientCapabilities(mcpClient.getClientCapabilities())
                .clientInfo(mcpClient.getClientInfo())
                .initializeResult(mcpClient.getCurrentInitializationResult())
                .build();
    }

    public static List<ToolCallback> syncToolCallbacks(List<McpSyncClient> mcpClients) {
        if (CollectionUtils.isEmpty(mcpClients)) {
            return List.of();
        }
        return List.of(new SyncMcpToolCallbackProvider(mcpClients).getToolCallbacks());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<McpSyncClient> mcpClients = new ArrayList<>();
        private McpServerRegistry registry;
        private McpServerToolCallbacksAdapter adapter;
        private McpToolFilter toolFilter = (mcpClient, tool) -> true;
        private McpToolNamePrefixGenerator toolNamePrefixGenerator = new DefaultMcpToolNamePrefixGenerator();
        private ToolContextToMcpMetaConverter toolContextToMcpMetaConverter =
                ToolContextToMcpMetaConverter.defaultConverter();

        public Builder mcpClients(List<McpSyncClient> mcpClients) {
            Assert.notNull(mcpClients, "MCP clients list must not be null");
            this.mcpClients = new ArrayList<>(mcpClients);
            return this;
        }

        public Builder registry(McpServerRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder adapter(McpServerToolCallbacksAdapter adapter) {
            this.adapter = adapter;
            return this;
        }

        public Builder toolFilter(McpToolFilter toolFilter) {
            Assert.notNull(toolFilter, "Tool filter must not be null");
            this.toolFilter = toolFilter;
            return this;
        }

        public Builder toolNamePrefixGenerator(McpToolNamePrefixGenerator toolNamePrefixGenerator) {
            Assert.notNull(toolNamePrefixGenerator, "Tool name prefix generator must not be null");
            this.toolNamePrefixGenerator = toolNamePrefixGenerator;
            return this;
        }

        public Builder toolContextToMcpMetaConverter(ToolContextToMcpMetaConverter converter) {
            Assert.notNull(converter, "Tool context to MCP meta converter must not be null");
            this.toolContextToMcpMetaConverter = converter;
            return this;
        }

        public SyncMcpToolCallbackProvider build() {
            if (registry != null && adapter != null) {
                return new SyncMcpToolCallbackProvider(toolFilter, toolNamePrefixGenerator,
                        registry, adapter, toolContextToMcpMetaConverter);
            }
            return new SyncMcpToolCallbackProvider(toolFilter, toolNamePrefixGenerator, mcpClients,
                    toolContextToMcpMetaConverter);
        }
    }
}
