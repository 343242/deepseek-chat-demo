package com.smart.rag.mcp.mcpclient;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.lang.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认前缀生成器——确保跨 client/server 连接的工具名唯一。
 * <p>
 * 对每个 (client, server, tool) 组合只生成一次工具名。若工具名已被使用，追加计数器前缀
 * （如 {@code alt_1_toolName}）。参照 Spring AI 2.0.0
 * {@code DefaultMcpToolNamePrefixGenerator}（自实现，线程安全）。
 * <p>
 * 注意：本类<b>不加 server 名前缀</b>（与项目 {@code McpClientConfiguration.mcpToolNamePrefixGenerator}
 * 的 {@code serverName + "_" + toolName} 策略不同）。项目自定义 bean 覆盖本默认实现。
 *
 * @author Christian Tzolov（原 Spring AI）
 */
public class DefaultMcpToolNamePrefixGenerator implements McpToolNamePrefixGenerator {

    private static final Log logger = LogFactory.getLog(DefaultMcpToolNamePrefixGenerator.class);

    private final Set<ConnectionId> existingConnections = ConcurrentHashMap.newKeySet();

    private final Set<String> allUsedToolNames = ConcurrentHashMap.newKeySet();

    private final AtomicInteger counter = new AtomicInteger(1);

    @Override
    public String prefixedToolName(McpConnectionInfo connectionInfo, Tool tool) {
        String uniqueToolName = McpToolUtils.format(tool.name());

        if (this.existingConnections.add(new ConnectionId(
                connectionInfo.clientInfo(),
                connectionInfo.initializeResult() != null ? connectionInfo.initializeResult().serverInfo() : null,
                tool))) {
            if (!this.allUsedToolNames.add(uniqueToolName)) {
                uniqueToolName = "alt_" + this.counter.getAndIncrement() + "_" + uniqueToolName;
                this.allUsedToolNames.add(uniqueToolName);
                if (logger.isWarnEnabled()) {
                    logger.warn("Tool name '" + tool.name() + "' already exists. Using unique tool name '"
                            + uniqueToolName + "'");
                }
            }
        }
        return uniqueToolName;
    }

    private record ConnectionId(@Nullable McpSchema.Implementation clientInfo,
                                @Nullable McpSchema.Implementation serverInfo, Tool tool) {
    }
}
