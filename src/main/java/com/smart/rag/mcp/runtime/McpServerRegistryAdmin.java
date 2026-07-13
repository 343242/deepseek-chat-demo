package com.smart.rag.mcp.runtime;

import com.smart.rag.mcp.admin.entity.McpServerConfig;
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
 * <b>占位语义</b>：{@code client=null} 表示握手失败的占位 server，
 * registry 中保留而非 remove，调用时返回友好错误。DB 的 {@code error_code}/{@code error_message}
 * 列替代了已删除的 runtime initError 字段。
 */
public interface McpServerRegistryAdmin {

    /**
     * 新增或替换 server（原子快照切换）。若该 serverId 已存在，旧 client 异步关闭。
     *
     * @param config DB 配置（含 serverId / url / bearerTokenEncrypted 等）
     * @param client MCP 同步客户端
     */
    void addServer(McpServerConfig config, @Nullable McpSyncClient client);

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
     * 用于检测 registry 快照变更，消费方比对版本号即可判断是否需要重新读取。
     */
    long currentVersion();

    /**
     * Withdraw a server from the snapshot and mark its instance inactive.
     * Returns the withdrawn instance for potential restore, or null if not present.
     * Callbacks captured before withdrawal fail fast on next remote call.
     */
    ManagedMcpServer withdraw(ServerId id);

    /**
     * Restore a previously withdrawn instance back into the snapshot, marking it active.
     * Uses CAS: only succeeds if no other instance was published for this id.
     */
    void restore(ManagedMcpServer withdrawn);

    /**
     * Remove a server only if the current snapshot entry is the exact same instance.
     * Prevents stale cleanup from removing a newer replacement.
     */
    boolean removeIfSame(ServerId id, ManagedMcpServer instance);
}
