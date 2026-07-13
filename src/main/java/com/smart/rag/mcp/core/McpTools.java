package com.smart.rag.mcp.core;

import java.util.List;

/**
 * MCP tools 能力（内核接口）。
 * <p>
 * <b>发现面</b> {@link #visibleTo} 委托聚合 {@code SyncMcpToolCallbackProvider} 按本 server 前缀过滤产出，
 * 再叠加内核 authz + intent 路由双过滤（A1 拼合：发现共享 provider，调用绑本 server client）。
 * <b>调用面</b> {@link #call} 绑本 server 的 {@code McpSyncClient.callTool}。
 * <p>
 * 两层 authz：{@code visibleTo} 剔除未授权工具（LLM 看不到 name/description），{@code call} 再硬判一次兜底
 * （§8）。任一层不过即拒。
 */
public interface McpTools {

    /**
     * 返回对调用方可见、且匹配 {@code intent} 路由的工具子集。
     * <p>
     * 既做 <b>authz</b>（未授权工具不暴露，纵深防御）又做 <b>intent 路由</b>
     * （按 {@code McpToolConfig.intent} 过滤）。两层都过才暴露。
     *
     * @param subj   调用方主体；未认证（{@code !subj.isAuthenticated()}）→ 空集
     * @param intent 本次请求意图
     * @return 可见工具列表（前缀后全名）；server 不可达/熔断时返回空集（fail-soft）
     */
    List<McpTool> visibleTo(Subject subj, McpIntent intent);

    /**
     * 调用工具（硬 authz 兜底）。
     * <p>
     * 校验 {@code name} 前缀 == 本 server {@code id()}（防跨 server 误调，R-11）→ 剥前缀 → authz →
     * {@code new CallToolRequest(rawName, args.asMap())} → {@code McpSyncClient.callTool}。
     *
     * @param name 前缀后全名（与 {@link McpTool#name} 一致）
     * @param args 调用参数
     * @param subj 调用方主体
     * @return 工具结果；{@code isError=true} 表示工具业务层错误（非 server 故障，不抹平，§6.1 C5）
     * @throws com.smart.rag.infrastructure.exception.ClientException authz 拒绝 / 工具不存在 / 前缀不符（A 类，不重试不计熔断）
     */
    McpToolResult call(String name, McpArgs args, Subject subj);
}
