package com.smart.rag.mcp.core;

import java.util.List;
import java.util.Optional;

/**
 * MCP server 注册表（内核接口）。
 * <p>
 * runtime 通过原子快照发布已连接和占位 {@link McpServer}；同一 id 的显式替换会原子切换并关闭旧 client。
 * DB 的 {@code server_id} 唯一约束阻止两个配置持有同一远端身份。
 */
public interface McpServerRegistry {

    /** 所有已建 McpServer（含 down 的；不可达的也建实例以暴露 health=down）。 */
    List<McpServer> list();

    /** 按 id 查找。 */
    Optional<McpServer> find(ServerId id);
}
