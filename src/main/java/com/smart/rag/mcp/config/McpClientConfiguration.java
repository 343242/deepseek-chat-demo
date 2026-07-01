package com.smart.rag.mcp.config;

import com.smart.rag.mcp.policy.McpSecurityProperties;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Map;

/**
 * MCP client starter 装配补充——提供默认 {@link McpToolNamePrefixGenerator} bean + 出站 Bearer 鉴权 customizer。
 * <p>
 * starter 默认 {@code DefaultMcpToolNamePrefixGenerator} <b>不加 server 前缀</b>（仅清洗/去重），多 server
 * 命名空间会冲突 → 自定义 bean 是<b>必须的</b>（§9）。产出 {@code <serverInfo.name()>_<tool.name()}，
 * 组件均经 {@link McpToolUtils#format} 清洗（合法集 {@code [a-zA-Z0-9_-]}，§9 E6）。
 * <p>
 * <b>同一 bean 共享</b>（§7 C1）：{@link AllowlistMcpToolFilter} 注入本 bean 反算前缀键，starter autoconfig
 * 亦注入本 bean 命名 provider callback——保证 yaml 键 / callback 名 / 内核 id 三者 1:1 同源。
 * <p>
 * <b>Bearer 鉴权（design D-9 兑现）</b>：MCP SDK 的 streamable-http transport 默认跑 OAuth 协商层，部分 server
 * （如 Tavily）拒收协商出的令牌 → 需直接注入 {@code Authorization: Bearer <token>}。下方 customizer bean
 * 按 {@code uri.getHost()} 匹配 {@link McpSecurityProperties#getBearerTokens()} 注入头，未配置的 host 不加（零影响）。
 */
@Configuration
public class McpClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(McpToolNamePrefixGenerator.class)
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return (connInfo, tool) -> serverName(connInfo) + "_" + McpToolUtils.format(tool.name());
    }

    /**
     * 出站 HTTP 请求 Bearer 鉴权（design D-9）。
     * <p>
     * autoconfig 自动拾取本 bean 注入 HttpClient transport（streamable-http + SSE 均生效）。
     * customizer 签名：{@code customize(HttpRequest.Builder, method, uri, body, ctx)}；
     * 按 {@code uri.getHost()} 查 {@link McpSecurityProperties#getBearerTokens()}，命中加头。
     */
    @Bean
    @ConditionalOnMissingBean(McpSyncHttpClientRequestCustomizer.class)
    public McpSyncHttpClientRequestCustomizer mcpBearerAuthRequestCustomizer(
            @Autowired(required = false) McpSecurityProperties securityProps) {
        Map<String, String> tokens = securityProps == null ? Map.of() : securityProps.getBearerTokens();
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

    /** 提取并清洗 server 名（防御 {@code initializeResult} 为 null，见 design R-9）。 */
    private static String serverName(McpConnectionInfo connInfo) {
        McpSchema.InitializeResult ir = connInfo.initializeResult();
        if (ir == null || ir.serverInfo() == null
                || ir.serverInfo().name() == null || ir.serverInfo().name().isBlank()) {
            return "unknown";
        }
        return McpToolUtils.format(ir.serverInfo().name());
    }
}
