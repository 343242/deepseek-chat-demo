package com.smart.rag.mcp.core;

import java.util.Objects;

/**
 * 项目侧净化的 MCP 工具领域模型（<b>非</b> starter {@code McpSchema.Tool}）。
 * <ul>
 *   <li>{@code name} — <b>前缀后</b>全名（{@code <serverInfo>_<tool>}），adapter 直接用作
 *       {@code ToolCallback} 名；{@code McpTools.call(prefixedName, ...)} 据此剥前缀路由回本 server</li>
 *   <li>{@code inputSchema} — <b>JSON 字符串</b>：runtime 从 provider 产出的
 *       {@code ToolCallback.getToolDefinition().inputSchema()} 直接取（不 re-serialize
 *       {@code McpSchema.JsonSchema}）；adapter 喂 {@code FunctionToolCallback.builder(...).inputSchema(...)}（B1）</li>
 * </ul>
 */
public record McpTool(String name, String description, String inputSchema) {

    public McpTool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
