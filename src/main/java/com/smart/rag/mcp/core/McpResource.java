package com.smart.rag.mcp.core;

import java.net.URI;

/**
 * MCP resource 读取结果（领域模型，路径 C / 出口②）。
 * <p>
 * runtime 经 {@code McpSyncClient.readResource(new ReadResourceRequest(uri.toString()))} 取回后净化
 * （starter 类型不跨出 runtime）。Phase 1 模型就位；业务 service 经 {@code McpServer.resources()} 消费属 Phase 3。
 *
 * @param uri      resource URI（出站请求，门面做白名单校验）
 * @param text     文本内容；二进制 resource 可能为 null
 * @param mimeType MIME 类型；可空
 */
public record McpResource(URI uri, String text, String mimeType) {
}
