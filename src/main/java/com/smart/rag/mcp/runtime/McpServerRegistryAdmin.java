package com.smart.rag.mcp.runtime;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.ServerId;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.lang.Nullable;

/**
 * MCP server 注册表管理员写契约——{@link McpServerRegistryImpl} 同时实现读/写双接口。
 * <p>
 * 写操作由 Admin runtime boundary 调用；其他组件注入只读 {@link com.smart.rag.mcp.core.McpServerRegistry}。
 * <p>
 * <b>原子快照切换</b>（对齐 {@code LlmClientRegistry.snapshotRef}）：
 * 每次 mutate 构建 ImmutableMap → CAS → 旧 snapshot 中不再存在的 client 异步关闭。
 * <p>
 * <b>占位语义</b>：{@code client=null} + {@code initError != null} 表示握手失败的占位 server，
 * registry 中保留而非 remove，调用时返回友好错误。
 */
public interface McpServerRegistryAdmin {

    /**
     * 新增或替换 server（原子快照切换）。若该 serverId 已存在，旧 client 异步关闭。
     *
     * @param config    DB 配置（含 serverId / url / bearerTokenEncrypted 等）
     * @param client    MCP 同步客户端；{@code null} 表示占位（initError 必须非空）
     * @param initError 握手失败原因；非空时 client 应为 null（占位）
     */
    void addServer(McpServerConfig config, @Nullable McpSyncClient client, @Nullable String initError);

    /**
     * 移除 server 注册；实现负责异步关闭被移除的 client。
     */
    void removeServer(ServerId id);

    /**
     * 替换 server（语义等价于 removeServer + addServer，但单次原子切换）。用于 reconnect 场景。
     */
    void replaceServer(McpServerConfig config, McpSyncClient newClient);

    /**
     * 当前 registry 版本号（每次 mutate 递增）。
     * {@code SyncMcpToolCallbackProvider} 用作 cache key 一部分，检测到版本变更即失效内部缓存。
     */
    long currentVersion();
}
