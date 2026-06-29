package com.smart.rag.mcp.core;

/**
 * MCP 工具调用结果（领域模型）。
 * <p>
 * {@code isError} 取自 {@code CallToolResult.isError()}，<b>不可抹平</b>（C5）：为 true 时
 * adapter {@code render()} 前缀 {@code [TOOL_ERROR]} 回流 LLM——直接取 {@code text()} 会把工具
 * 业务错误当正常结果，误导 LLM。{@code isError=true} 是<b>工具业务层</b>错误（工具被正常调用后返回
 * 失败语义），<b>非</b> server 故障，既不重试也不计熔断（§11.1）。
 * <p>
 * Phase 1 仅落 {@code text}；image/resource 等非文本 content 留扩展位。
 */
public record McpToolResult(String text, boolean isError) {

    public static McpToolResult success(String text) {
        return new McpToolResult(text, false);
    }

    public static McpToolResult error(String text) {
        return new McpToolResult(text == null ? "" : text, true);
    }
}
