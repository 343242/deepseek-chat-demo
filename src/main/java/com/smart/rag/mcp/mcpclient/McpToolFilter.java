package com.smart.rag.mcp.mcpclient;

import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.function.BiPredicate;

/**
 * 静态 allowlist 过滤器——决定远端 MCP 工具是否纳入 ToolCallbackProvider（inclusion 语义）。
 * <p>
 * 参照 Spring AI 2.0.0 {@code McpToolFilter}（自实现：脱离 spring-ai-mcp jar）。
 * 由 {@code DatabaseToolFilter}（mcp/config）实现，查 {@code mcp_tool_config}（DB）判定。
 *
 * @author Christian Tzolov（原 Spring AI）
 */
@FunctionalInterface
public interface McpToolFilter extends BiPredicate<McpConnectionInfo, Tool> {
}
