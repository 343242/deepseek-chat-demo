package com.smart.rag.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Configures MCP bootstrap properties. */
@Configuration
public class McpClientTransportConfiguration {

    /** Shared transport timeout properties. */
    @Bean
    @ConfigurationProperties(prefix = "spring.ai.mcp.client")
    public McpClientTransportProperties mcpClientTransportProperties() {
        return new McpClientTransportProperties();
    }

    /**
     * Properties POJO（读 {@code spring.ai.mcp.client.*} 配置）。
     */
    public static class McpClientTransportProperties {

        private boolean initialized = false;
        private String type = "SYNC";
        private String requestTimeout = "30s";
        private StreamableHttp streamableHttp = new StreamableHttp();

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
