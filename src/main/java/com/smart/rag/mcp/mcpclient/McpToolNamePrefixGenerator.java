package com.smart.rag.mcp.mcpclient;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * 策略接口：基于 MCP 连接信息和 Tool 元数据生成前缀名（防多 server 工具名冲突）。
 * <p>
 * 参照 Spring AI 2.0.0 {@code McpToolNamePrefixGenerator}（自实现：脱离 spring-ai-mcp jar）。
 * 默认实现在 {@link DefaultMcpToolNamePrefixGenerator}（去重 prefix）。
 *
 * @author Christian Tzolov（原 Spring AI）
 */
public interface McpToolNamePrefixGenerator {

    /**
     * @param connectionInfo MCP 连接信息（client + server）
     * @param tool           远端工具元数据
     * @return 前缀全名（如 {@code serverName_toolName}）
     */
    String prefixedToolName(McpConnectionInfo connectionInfo, Tool tool);

    /**
     * 不加前缀——直接返回 {@code tool.name()}。
     *
     * @return no-op prefix generator
     */
    static McpToolNamePrefixGenerator noPrefix() {
        return (connectionInfo, tool) -> tool.name();
    }
}
