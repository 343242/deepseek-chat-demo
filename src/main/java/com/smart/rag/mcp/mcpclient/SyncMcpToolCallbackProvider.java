package com.smart.rag.mcp.mcpclient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * 从多个 MCP server 发现工具并组装为 Spring AI {@link ToolCallback}。
 * <p>
 * 遍历所有 {@link McpSyncClient} 的 {@code listTools()}，经 {@link McpToolFilter} 过滤、
 * {@link McpToolNamePrefixGenerator} 加前缀后组装为 {@link SyncMcpToolCallback}。
 * 带缓存（double-check + {@link ReentrantLock}），{@code invalidateCache()} 强制重发现。
 * <p>
 * 参照 Spring AI 2.0.0 {@code SyncMcpToolCallbackProvider}，省略
 * {@code ApplicationListener<McpToolsChangedEvent>}（主动拉取策略，不依赖 list_changed 事件）。
 *
 * @author Christian Tzolov, YunKui Lu（原 Spring AI）
 */
public class SyncMcpToolCallbackProvider implements ToolCallbackProvider {

    private final List<McpSyncClient> mcpClients;

    private final McpToolFilter toolFilter;

    private final McpToolNamePrefixGenerator toolNamePrefixGenerator;


    private final ToolContextToMcpMetaConverter toolContextToMcpMetaConverter;

    private volatile boolean invalidateCache = true;

    private volatile List<ToolCallback> cachedToolCallbacks = List.of();

    private final Lock lock = new ReentrantLock();

    public SyncMcpToolCallbackProvider(List<McpSyncClient> mcpClients) {
        this((mcpClient, tool) -> true, mcpClients);
    }

    public SyncMcpToolCallbackProvider(McpToolFilter toolFilter, List<McpSyncClient> mcpClients) {
        this(toolFilter, new DefaultMcpToolNamePrefixGenerator(), mcpClients,
                ToolContextToMcpMetaConverter.defaultConverter());
    }

    public SyncMcpToolCallbackProvider(McpToolFilter toolFilter,
            McpToolNamePrefixGenerator toolNamePrefixGenerator, List<McpSyncClient> mcpClients,
            ToolContextToMcpMetaConverter toolContextToMcpMetaConverter) {
        Assert.notNull(mcpClients, "MCP clients must not be null");
        Assert.notNull(toolFilter, "Tool filter must not be null");
        Assert.notNull(toolNamePrefixGenerator, "Tool name prefix generator must not be null");
        Assert.notNull(toolContextToMcpMetaConverter, "Tool context to MCP meta converter must not be null");
        this.mcpClients = mcpClients;
        this.toolFilter = toolFilter;
        this.toolNamePrefixGenerator = toolNamePrefixGenerator;
        this.toolContextToMcpMetaConverter = toolContextToMcpMetaConverter;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        if (this.invalidateCache) {
            this.lock.lock();
            try {
                if (this.invalidateCache) {
                    this.cachedToolCallbacks = this.mcpClients.stream()
                            .flatMap(mcpClient -> mcpClient.listTools()
                                    .tools()
                                    .stream()
                                    .filter(tool -> this.toolFilter.test(connectionInfo(mcpClient), tool))
                                    .<ToolCallback>map(tool -> SyncMcpToolCallback.builder()
                                            .mcpClient(mcpClient)
                                            .tool(tool)
                                            .prefixedToolName(this.toolNamePrefixGenerator
                                                    .prefixedToolName(connectionInfo(mcpClient), tool))
                                            .toolContextToMcpMetaConverter(this.toolContextToMcpMetaConverter)
                                            .build()))
                            .toList();
                    this.validateToolCallbacks(this.cachedToolCallbacks);
                    this.invalidateCache = false;
                }
            } finally {
                this.lock.unlock();
            }
        }
        return this.cachedToolCallbacks.toArray(new ToolCallback[0]);
    }

    /** 强制下次 {@link #getToolCallbacks()} 重新发现工具。 */
    public void invalidateCache() {
        this.invalidateCache = true;
    }

    private static McpConnectionInfo connectionInfo(McpSyncClient mcpClient) {
        return McpConnectionInfo.builder()
                .clientCapabilities(mcpClient.getClientCapabilities())
                .clientInfo(mcpClient.getClientInfo())
                .initializeResult(mcpClient.getCurrentInitializationResult())
                .build();
    }

    private void validateToolCallbacks(List<ToolCallback> toolCallbacks) {
        List<String> duplicateToolNames = ToolUtils.getDuplicateToolNames(toolCallbacks);
        if (!duplicateToolNames.isEmpty()) {
            throw new IllegalStateException(
                    "Multiple tools with the same name (%s)".formatted(String.join(", ", duplicateToolNames)));
        }
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

        private McpToolFilter toolFilter = (mcpClient, tool) -> true;

        private McpToolNamePrefixGenerator toolNamePrefixGenerator = new DefaultMcpToolNamePrefixGenerator();

        private ToolContextToMcpMetaConverter toolContextToMcpMetaConverter = ToolContextToMcpMetaConverter
                .defaultConverter();

        public Builder mcpClients(List<McpSyncClient> mcpClients) {
            Assert.notNull(mcpClients, "MCP clients list must not be null");
            this.mcpClients = new ArrayList<>(mcpClients);
            return this;
        }

        public Builder mcpClients(McpSyncClient... mcpClients) {
            Assert.notNull(mcpClients, "MCP clients array must not be null");
            this.mcpClients = new ArrayList<>(List.of(mcpClients));
            return this;
        }

        public Builder addMcpClient(McpSyncClient mcpClient) {
            Assert.notNull(mcpClient, "MCP client must not be null");
            this.mcpClients.add(mcpClient);
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
            return new SyncMcpToolCallbackProvider(this.toolFilter, this.toolNamePrefixGenerator,
                    this.mcpClients, this.toolContextToMcpMetaConverter);
        }
    }
}
