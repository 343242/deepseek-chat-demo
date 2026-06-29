package com.smart.rag.mcp.adapter;

import com.smart.rag.mcp.core.McpArgs;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.McpTool;
import com.smart.rag.mcp.core.McpToolResult;
import com.smart.rag.mcp.core.McpTools;
import com.smart.rag.mcp.core.Subject;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 对外出口①：{@code core.McpTools} → Spring AI {@link ToolCallback}（mcp/adapter 是<b>唯一</b>可 import
 * {@code org.springframework.ai.tool..} 的包，ArchUnit 6.2）。
 * <p>
 * <b>B1（已单测坐实）</b>：必须 {@code .inputSchema(MCP 真实 schema)} <b>且</b> {@code inputType(Map<String,Object>)}。
 * {@code inputType(String.class)} 会让框架 {@code readValue} 遇 JSON object 抛 {@code MismatchedInputException}
 * （{@code FunctionToolCallback.call()} {@code :103} {@code JsonParser.fromJson}→{@code readValue}）。
 * <p>
 * BiFunction 的 {@code args} 已是 {@code Map}（框架按 {@code inputType} 反序列化），直接 {@code McpArgs.of(args)}
 * 包成 {@link McpArgs}（<b>无 {@code fromJson}</b>）。
 * <p>
 * <b>authz 不可绕过</b>：产的 {@link ToolCallback} 执行时委托回 {@link McpTools#call}（内核硬 authz 兜底）。
 * {@code subj} 由消费侧（per-request）传入并闭包捕获；{@code intent} 本 adapter 取 {@link McpIntent}（core），
 * {@code AgentIntent→McpIntent} 映射由消费侧完成（保持 adapter 只依赖 core + tool..）。
 * <p>
 * <b>出口① 已接线</b>：{@code AgentToolCallbackFactory} per-request 调 {@link #toCallbacksForAllServers}
 * 聚合所有 MCP server 的可见工具。
 */
@Component
public class McpToolCallbackAdapter {

    private final McpServerRegistry registry;

    /**
     * @param registry MCP server 注册表；{@link #toCallbacksForAllServers} 遍历它聚合多 server（空载→空数组）
     */
    public McpToolCallbackAdapter(McpServerRegistry registry) {
        this.registry = registry;
    }

    /**
     * 把 {@code tools} 对 {@code subj} 可见、且匹配 {@code intent} 的工具转成 {@link ToolCallback[]}。
     * <p>
     * 内核 {@link McpTools#visibleTo} 已做 authz + intent 双过滤，本方法只做 core→ToolCallback 类型转换。
     */
    public ToolCallback[] toCallbacks(McpTools tools, McpIntent intent, Subject subj) {
        List<McpTool> visible = tools.visibleTo(subj, intent);
        if (visible.isEmpty()) {
            return new ToolCallback[0];
        }
        ToolCallback[] callbacks = new ToolCallback[visible.size()];
        int i = 0;
        for (McpTool t : visible) {
            final String name = t.name();
            final String description = t.description();
            final String inputSchema = t.inputSchema();
            callbacks[i++] = FunctionToolCallback
                    .<Map<String, Object>, String>builder(name,
                            (args, ctx) -> render(tools.call(name, McpArgs.of(args != null ? args : Map.of()), subj)))
                    .description(description)
                    .inputSchema(inputSchema)
                    .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .build();
        }
        return callbacks;
    }

    /**
     * 聚合<b>所有</b> MCP server 对 {@code (intent, subj)} 可见的工具 → {@link ToolCallback[]}（出口① 多 server 拼合）。
     * <p>
     * 遍历 {@link McpServerRegistry#list()}，逐 server 委托 {@link #toCallbacks}；{@code McpServerImpl.visibleTo}
     * 已 fail-soft（down/熔断/未认证/provider 缺失/发现失败 → 空集），故<b>无需 try/catch</b>。registry 空载
     * （无 connections 或 {@code enabled=false}）→ 空数组（接线后默认零行为变更）。
     *
     * @param intent 本次请求意图（{@code AgentIntent→McpIntent} 映射由消费侧完成）
     * @param subj   调用方主体；未认证 → 各 server visibleTo 均空集 → 空数组
     */
    public ToolCallback[] toCallbacksForAllServers(McpIntent intent, Subject subj) {
        List<McpServer> servers = registry.list();
        if (servers.isEmpty()) {
            return new ToolCallback[0];
        }
        List<ToolCallback> all = new ArrayList<>();
        for (McpServer server : servers) {
            ToolCallback[] part = toCallbacks(server.tools(), intent, subj);
            if (part.length > 0) {
                Collections.addAll(all, part);
            }
        }
        return all.toArray(ToolCallback[]::new);
    }

    /**
     * 结果渲染：<b>isError 不抹平（C5）</b>——{@code true} 前缀 {@code [TOOL_ERROR]} 回流 LLM，避免把工具业务错误当正常结果。
     * 返回值经 Spring AI 默认 {@code ToolCallResultConverter} 再 JSON 序列化喂 LLM（Phase 1 可接受）。
     */
    private static String render(McpToolResult r) {
        String text = r.text() == null ? "" : r.text();
        return r.isError() ? "[TOOL_ERROR] " + text : text;
    }
}
