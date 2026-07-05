package com.smart.rag.mcp.mcpclient;

import com.smart.rag.mcp.core.McpServer;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 从单个 {@link McpServer} 抽出经 filter / prefix 处理后的 Spring AI {@link ToolCallback}（v4 B2）。
 * <p>
 * <b>设计动机</b>：{@code SyncMcpToolCallbackProvider} 改造为注入 {@link com.smart.rag.mcp.core.McpServerRegistry}
 * 后，需要遍历 {@code registry.list()} 对每个 {@link McpServer} 调用工具发现逻辑。但 {@code ToolCallback}
 * 是 Spring AI starter 类型，不能进 {@code mcp/core/} 接口（违反"零 starter 依赖"铁律）。
 * <p>
 * 本 adapter 接口位于 {@code mcp/mcpclient/} runtime 层，由 {@code McpServerImpl}（runtime，已依赖 Spring AI）实现。
 * Core 层的 {@code McpServer} 不感知本接口（依赖方向 runtime → core）。
 */
public interface McpServerToolCallbacksAdapter {

    /**
     * 从单个 server 抽出工具 callbacks（已 filter + prefix）。
     * <p>
     * 占位 server（{@code initError != null}）应返回空列表。
     *
     * @param server        目标 MCP server
     * @param filter        工具过滤器（决定是否进 callback 列表）
     * @param prefixGen     工具名前缀生成器（与 {@code McpToolNamePrefixGenerator} 同 bean）
     * @param metaConverter ToolContext 到 MCP meta 的转换器
     */
    List<ToolCallback> toolCallbacks(McpServer server,
                                     McpToolFilter filter,
                                     McpToolNamePrefixGenerator prefixGen,
                                     ToolContextToMcpMetaConverter metaConverter);
}
