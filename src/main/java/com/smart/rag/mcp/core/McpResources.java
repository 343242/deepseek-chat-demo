package com.smart.rag.mcp.core;

import java.net.URI;

/**
 * MCP resources 能力（路径 C / 出口②，内核接口）。
 * <p>
 * {@code read} 在内核内把 {@code URI} 包成 {@code new ReadResourceRequest(uri.toString())} 再委托本 server
 * 的 {@code McpSyncClient.readResource}（注意：{@code McpSyncClient.readResource} 接受 {@code ReadResourceRequest}，
 * <b>非</b> URI）。门面统一 authz + URI 白名单，无需另造 {@code AuthorizedMcpClient}（§6.3）。
 */
public interface McpResources {

    /**
     * 读取远端 resource。
     *
     * @param uri  resource URI（出站请求，需在白名单内）
     * @param subj 调用方主体
     * @return resource 内容
     * @throws com.smart.rag.infrastructure.exception.ClientException authz 拒绝 / URI 非白名单（A 类）
     */
    McpResource read(URI uri, Subject subj);
}
