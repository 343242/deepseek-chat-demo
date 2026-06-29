package com.smart.rag.mcp.core;

/**
 * 一个远端 MCP server 在本项目内的代理门面（领域内核一等对象）。
 * <p>
 * 统一 tools / resources / prompts 三能力 + health；authz 在本门面层收敛（§8）：
 * {@link McpTools#call} / {@link McpResources#read} / {@link McpPrompts#get} 均必经内核硬授权，
 * {@link McpTools#visibleTo} 再做 authz + intent 双过滤（纵深防御）。
 * <p>
 * <b>接口在 core</b>（零 starter 依赖）；持 {@code McpSyncClient}/provider 引用的<b>实现在 runtime</b>
 * （starter 类型不跨出 runtime+config）。{@code McpSyncClient} 三能力（callTool/listTools/readResource/getPrompt）
 * 由 runtime 实现类委托。
 */
public interface McpServer {

    /** 命名空间标识（前缀/路由/熔断 key 同源）。 */
    ServerId id();

    /** 健康状态（三态熔断器的只读投影）。 */
    McpServerHealth health();

    /** tools 能力（发现委托聚合 provider + authz/intent 过滤；调用绑本 server 的 McpSyncClient）。 */
    McpTools tools();

    /** resources 能力（路径 C）。 */
    McpResources resources();

    /** prompts 能力（路径 C）。 */
    McpPrompts prompts();
}
