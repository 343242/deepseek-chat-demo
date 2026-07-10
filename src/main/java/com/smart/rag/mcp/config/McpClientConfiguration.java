package com.smart.rag.mcp.config;

import com.smart.rag.mcp.mcpclient.McpToolNamePrefixGenerator;
import com.smart.rag.mcp.mcpclient.McpToolUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具名前缀策略——产出 {@code <serverInfo.name()>_<tool.name>}（§9 命名空间隔离）。
 * <p>
 * 默认实现和本 bean 都委托 {@link McpToolUtils}，统一清洗、长度限制和 server 命名空间。
 * <p>
 * <b>同一 bean 共享</b>（§7 C1）：{@link com.smart.rag.mcp.config.DatabaseToolFilter} 注入本 bean 反算前缀键，
 * {@code McpClientTransportConfiguration} 的 provider 也注入本 bean 命名 callback——
 * 保证 yaml 键 / callback 名 / 内核 id 三者 1:1 同源。
 * <p>
 * Bearer auth customizer 由 runtime client builder 负责。
 */
@Configuration
public class McpClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(McpToolNamePrefixGenerator.class)
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return McpToolUtils::prefixedToolName;
    }
}
