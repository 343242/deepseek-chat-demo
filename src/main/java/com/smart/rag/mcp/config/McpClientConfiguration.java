package com.smart.rag.mcp.config;

import com.smart.rag.mcp.mcpclient.McpConnectionInfo;
import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import com.smart.rag.mcp.mcpclient.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具名前缀策略——产出 {@code <serverInfo.name()>_<tool.name>}（§9 命名空间隔离）。
 * <p>
 * 自实现的 {@link DefaultMcpToolNamePrefixGenerator}（mcpclient 包）<b>不加 server 前缀</b>（仅去重），
 * 多 server 命名空间会冲突 → 本 bean 是<b>必须的</b>。组件均经 {@link McpToolUtils#format} 清洗。
 * <p>
 * <b>同一 bean 共享</b>（§7 C1）：{@link AllowlistMcpToolFilter} 注入本 bean 反算前缀键，
 * {@code McpClientTransportConfiguration} 的 provider 也注入本 bean 命名 callback——
 * 保证 yaml 键 / callback 名 / 内核 id 三者 1:1 同源。
 * <p>
 * Bearer auth customizer 已迁至 {@link McpClientTransportConfiguration}（与 transport 装配同处）。
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
