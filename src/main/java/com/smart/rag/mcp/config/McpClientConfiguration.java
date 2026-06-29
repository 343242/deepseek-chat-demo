package com.smart.rag.mcp.config;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP client starter 装配补充——提供默认 {@link McpToolNamePrefixGenerator} bean。
 * <p>
 * starter 默认 {@code DefaultMcpToolNamePrefixGenerator} <b>不加 server 前缀</b>（仅清洗/去重），多 server
 * 命名空间会冲突 → 自定义 bean 是<b>必须的</b>（§9）。产出 {@code <serverInfo.name()>_<tool.name()}，
 * 组件均经 {@link McpToolUtils#format} 清洗（合法集 {@code [a-zA-Z0-9_-]}，§9 E6）。
 * <p>
 * <b>同一 bean 共享</b>（§7 C1）：{@link AllowlistMcpToolFilter} 注入本 bean 反算前缀键，starter autoconfig
 * 亦注入本 bean 命名 provider callback——保证 yaml 键 / callback 名 / 内核 id 三者 1:1 同源。
 * <p>
 * 注：{@code McpSyncClientCustomizer} 本期<b>不提供</b>——request-timeout 经 starter 属性 {@code request-timeout}，
 * 无出站认证/sampling 需求；Phase 2 加 bearer auth / sampling 时再补 customizer bean（design D-1/D-8 记）。
 */
@Configuration
public class McpClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(McpToolNamePrefixGenerator.class)
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return (connInfo, tool) -> serverName(connInfo) + "_" + McpToolUtils.format(tool.name());
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
