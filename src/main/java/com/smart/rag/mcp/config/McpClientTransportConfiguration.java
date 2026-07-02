package com.smart.rag.mcp.config;

import com.smart.rag.mcp.mcpclient.McpToolFilter;
import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.policy.McpSecurityProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP client transport 装配层——替代 spring-ai-starter-mcp-client 的 autoconfig。
 * <p>
 * 读 yaml {@code spring.ai.mcp.client.streamable-http.connections} → 构建
 * {@link HttpClientStreamableHttpTransport}（SDK 2.0.0，已修复 #773 405）→
 * {@link McpSyncClient}（未握手，由 {@code McpServerRegistryImpl} per-client init）。
 * <p>
 * 同时注册 {@link SyncMcpToolCallbackProvider} bean（工具发现 + filter + prefix），
 * 以及 Bearer auth customizer（{@code mcp.security.bearer-tokens}）。
 * <p>
 * <b>fail-soft</b>：无 connections 时产出空列表，不阻塞启动。
 *
 * @author instant（参照原 starter autoconfig + Spring AI 2.0.0 胶水层）
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpClientTransportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpClientTransportConfiguration.class);

    /**
     * 读 yaml 连接配置（streamable-http）。
     * <p>
     * 复用 starter 的配置键名（{@code spring.ai.mcp.client.streamable-http.connections}），
     * 但<b>不依赖</b> starter 的 {@code McpStreamableHttpClientProperties}（那是 starter jar 内的类）。
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.ai.mcp.client")
    public McpClientTransportProperties mcpClientTransportProperties() {
        return new McpClientTransportProperties();
    }

    /**
     * 构建 {@link McpSyncClient} 列表（每条 connection 一个 client）。
     * <p>
     * client 交付时<b>未握手</b>（{@code initialize()} 由 {@code McpServerRegistryImpl} per-client 调用，
     * 实现 server 间 fail-soft 隔离）。
     */
    @Bean
    public List<McpSyncClient> mcpSyncClients(
            McpClientTransportProperties props,
            @Autowired(required = false) McpSyncHttpClientRequestCustomizer requestCustomizer,
            @Autowired(required = false) McpToolNamePrefixGenerator prefixGen) {

        if (!"SYNC".equalsIgnoreCase(props.getType())) {
            log.warn("MCP: type 非 SYNC 或未配置，跳过 transport 构建");
            return List.of();
        }

        McpClientTransportProperties.StreamableHttp streamableHttp = props.getStreamableHttp();
        if (streamableHttp == null || streamableHttp.getConnections() == null
                || streamableHttp.getConnections().isEmpty()) {
            log.info("MCP: 无 streamable-http connections，不构建 McpSyncClient");
            return List.of();
        }

        Duration requestTimeout = parseDuration(props.getRequestTimeout(), Duration.ofSeconds(30));
        List<McpSyncClient> clients = new ArrayList<>();

        for (Map.Entry<String, McpClientTransportProperties.ConnectionParameters> entry : streamableHttp.getConnections()
                .entrySet()) {
            String name = entry.getKey();
            McpClientTransportProperties.ConnectionParameters conn = entry.getValue();
            if (conn == null || conn.getUrl() == null || conn.getUrl().isBlank()) {
                log.warn("MCP: connection [{}] 无 url，跳过", name);
                continue;
            }
            try {
                HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                        .builder(conn.getUrl())
                        .openConnectionOnStartup(false); // ★ 避免 eager GET（SDK 2.0.0 已修复 #773，但默认仍 false 更安全）
                if (requestCustomizer != null) {
                    builder.httpRequestCustomizer(requestCustomizer);
                }
                HttpClientStreamableHttpTransport transport = builder.build();

                McpSyncClient client = McpClient.sync(transport)
                        .requestTimeout(requestTimeout)
                        .clientInfo(new McpSchema.Implementation("smart-rag", "1.0.0"))
                        .build();

                clients.add(client);
                log.info("MCP: streamable-http connection [{}] → {}", name, conn.getUrl());
            } catch (Exception e) {
                log.warn("MCP: connection [{}] transport 构建失败（不影响其他 server）: {}", name, e.getMessage());
            }
        }
        return Collections.unmodifiableList(clients);
    }

    /**
     * 工具发现 provider——遍历所有 {@link McpSyncClient} 的 {@code listTools()} 组装 ToolCallback[]。
     * <p>
     * 注入 {@link McpToolFilter}（allowlist）和 {@link McpToolNamePrefixGenerator}（前缀）。
     * 两者均由 {@link McpClientConfiguration} 提供默认 bean（@ConditionalOnMissingBean）。
     */
    @Bean
    @ConditionalOnMissingBean(SyncMcpToolCallbackProvider.class)
    public SyncMcpToolCallbackProvider syncMcpToolCallbackProvider(
            List<McpSyncClient> mcpClients,
            @Autowired(required = false) McpToolFilter toolFilter,
            @Autowired(required = false) McpToolNamePrefixGenerator prefixGen) {

        McpToolFilter effectiveFilter = toolFilter != null ? toolFilter : (connInfo, tool) -> true;
        McpToolNamePrefixGenerator effectivePrefix = prefixGen != null ? prefixGen
                : (connInfo, tool) -> tool.name();

        return new SyncMcpToolCallbackProvider(effectiveFilter, effectivePrefix, mcpClients,
                com.smart.rag.mcp.mcpclient.ToolContextToMcpMetaConverter.defaultConverter());
    }

    // === 内部配置类 ===

    /**
     * 出站 Bearer 鉴权 customizer（design D-9）。
     * <p>
     * 按 {@code uri.getHost()} 匹配 {@link McpSecurityProperties#getBearerTokens()}，命中加 Authorization 头。
     */
    @Bean
    @ConditionalOnMissingBean(McpSyncHttpClientRequestCustomizer.class)
    public McpSyncHttpClientRequestCustomizer mcpBearerAuthRequestCustomizer(
            @Autowired(required = false) McpSecurityProperties securityProps) {
        Map<String, String> tokens = securityProps == null ? Collections.emptyMap()
                : securityProps.getBearerTokens();
        return (builder, method, uri, body, ctx) -> {
            if (tokens.isEmpty()) {
                return;
            }
            URI u = uri == null ? null : URI.create(uri.toString());
            String host = u == null ? null : u.getHost();
            String token = host == null ? null : tokens.get(host);
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
        };
    }

    // === 工具方法 ===

    private static Duration parseDuration(@Nullable String value, Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Duration.parse(value.startsWith("PT") ? value : "PT" + value.toUpperCase());
        } catch (Exception e) {
            return fallback;
        }
    }

    // === Properties POJO（读 spring.ai.mcp.client.* 配置）===

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
