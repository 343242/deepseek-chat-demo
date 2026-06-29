package com.smart.rag.mcp.core;

import java.util.List;
import java.util.Optional;

/**
 * MCP server 注册表（内核接口）。
 * <p>
 * per {@code McpSyncClient} 建 {@link McpServer}（逻辑一一对应：调用面绑各自 client）；tools 发现面
 * 共享聚合 provider（A1 拼合）。实现（runtime）注入 {@code ObjectProvider<List<McpSyncClient>>} +
 * provider + prefixGen（全可选，无 connections 时空载不抛）；因 {@code spring.ai.mcp.client.initialized=false}
 * 建期对每个 client per-client {@code initialize()} + try/catch（不可达→down 跳过，不影响其他 client）。
 * <p>
 * 多个 client 同 {@code serverInfo.name()} → 显式抛配置错误（不静默合并，R-10）。
 */
public interface McpServerRegistry {

    /** 所有已建 McpServer（含 down 的；不可达的也建实例以暴露 health=down）。 */
    List<McpServer> list();

    /** 按 id 查找。 */
    Optional<McpServer> find(ServerId id);
}
