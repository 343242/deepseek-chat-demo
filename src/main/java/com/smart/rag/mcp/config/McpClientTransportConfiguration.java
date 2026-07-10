package com.smart.rag.mcp.config;

import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.mcpclient.McpServerToolCallbacksAdapter;
import com.smart.rag.mcp.mcpclient.McpToolFilter;
import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.mcpclient.ToolContextToMcpMetaConverter;
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

/** Configures MCP bootstrap properties and registry-backed tool callback discovery. */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpClientTransportConfiguration {

    /** Shared transport timeout plus optional YAML connections used for first bootstrap. */
    @Bean
    @ConfigurationProperties(prefix = "spring.ai.mcp.client")
    public McpClientTransportProperties mcpClientTransportProperties() {
        return new McpClientTransportProperties();
    }

    /** Registry-backed provider; each server discovery failure is isolated by the provider. */
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
