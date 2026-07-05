package com.smart.rag.mcp.config;

import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.mcpclient.McpServerToolCallbacksAdapter;
import com.smart.rag.mcp.mcpclient.McpToolFilter;
import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.mcpclient.ToolContextToMcpMetaConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP client transport 装配层（v4 C8 改造：降级为 bootstrap）。
 * <p>
 * <b>v3 → v4 关键变更</b>：
 * <ul>
 *   <li>{@code mcpSyncClients()} Bean <b>已删除</b>——动态 client 由 {@code McpClientFactory.createClient()} 创建</li>
 *   <li>{@code mcpBearerAuthRequestCustomizer()} Bean <b>已删除</b>——Bearer Token 改在 McpClientFactory.buildTransport 内注入</li>
 *   <li>{@code McpClientTransportProperties} Bean <b>保留</b>，仅 {@code McpAdminService.bootstrapFromYaml()} 启动时读一次</li>
 *   <li>{@code SyncMcpToolCallbackProvider} Bean 改注入 {@link McpServerRegistry} + {@link McpServerToolCallbacksAdapter}</li>
 * </ul>
 * <p>
 * <b>对称降级（v4 C8）</b>：与 {@code McpSecurityProperties} 一样，
 * 运行时<b>无</b> Bean 注入本 Properties；仅 {@code McpAdminService.bootstrapFromYaml()} 启动期读。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpClientTransportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpClientTransportConfiguration.class);

    /**
     * 读 yaml 连接配置（仅 bootstrap 用，运行时不被任何 Bean 注入）。
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.ai.mcp.client")
    public McpClientTransportProperties mcpClientTransportProperties() {
        return new McpClientTransportProperties();
    }

    /**
     * 工具发现 provider——v4 改为 registry + adapter 驱动（替代 v3 的 List&lt;McpSyncClient&gt;）。
     * <p>
     * 注册为 {@link SyncMcpToolCallbackProvider} bean，注入 {@link McpServerRegistry}（只读）+
     * {@link McpServerToolCallbacksAdapter}（runtime 层 adapter，由 {@code McpServerImpl} 实现）。
     */
    @Bean
    @ConditionalOnMissingBean(SyncMcpToolCallbackProvider.class)
    public SyncMcpToolCallbackProvider syncMcpToolCallbackProvider(
            McpServerRegistry registry,
            McpServerToolCallbacksAdapter adapter,
            @Autowired(required = false) McpToolFilter toolFilter,
            @Autowired(required = false) McpToolNamePrefixGenerator prefixGen) {

        McpToolFilter effectiveFilter = toolFilter != null ? toolFilter : (connInfo, tool) -> true;
        McpToolNamePrefixGenerator effectivePrefix = prefixGen != null ? prefixGen
                : (connInfo, tool) -> tool.name();

        return new SyncMcpToolCallbackProvider(effectiveFilter, effectivePrefix, registry, adapter,
                ToolContextToMcpMetaConverter.defaultConverter());
    }

    /**
     * Properties POJO（读 {@code spring.ai.mcp.client.*} 配置，仅启动期用）。
     */
    public static class McpClientTransportProperties {

        private boolean enabled = true;
        private boolean initialized = false;
        private String type = "SYNC";
        private String requestTimeout = "30s";
        private StreamableHttp streamableHttp = new StreamableHttp();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isInitialized() { return initialized; }
        public void setInitialized(boolean v) { this.initialized = v; }
        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(String v) { this.requestTimeout = v; }
        public StreamableHttp getStreamableHttp() { return streamableHttp; }
        public void setStreamableHttp(StreamableHttp v) { this.streamableHttp = v; }

        public static class StreamableHttp {
            private Map<String, ConnectionParameters> connections = new LinkedHashMap<>();

            public Map<String, ConnectionParameters> getConnections() { return connections; }
            public void setConnections(Map<String, ConnectionParameters> v) { this.connections = v; }
        }

        public static class ConnectionParameters {
            private String url;
            private String endpoint;

            public String getUrl() { return url; }
            public void setUrl(String v) { this.url = v; }
            public String getEndpoint() { return endpoint; }
            public void setEndpoint(String v) { this.endpoint = v; }
        }
    }
}
