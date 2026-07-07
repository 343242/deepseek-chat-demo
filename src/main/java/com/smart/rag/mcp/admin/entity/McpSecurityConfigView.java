package com.smart.rag.mcp.admin.entity;

import java.util.List;

/**
 * MCP 安全配置的强类型视图（jsonb 反序列化目标）。
 * <p>
 * 字段对应原 {@code McpSecurityProperties}（已降级为 bootstrap）：
 * <ul>
 *   <li>{@code sensitiveArgPatterns}：扫 {@code McpArgs} 值的 regex 列表；命中即 DENY</li>
 *   <li>{@code defaultOutputCapChars}：risk=low/缺省 工具的输出字符上限</li>
 *   <li>{@code highRiskOutputCapChars}：risk=high 工具的输出字符上限（应 < default）</li>
 *   <li>{@code toolDescCharLimit}：工具描述字符上限（防 prompt-bombing）</li>
 * </ul>
 * DB 为空或反序列化失败时回退到 {@link #defaults()}。
 */
public record McpSecurityConfigView(
        List<String> sensitiveArgPatterns,
        int defaultOutputCapChars,
        int highRiskOutputCapChars,
        int toolDescCharLimit
) {
    public static McpSecurityConfigView defaults() {
        return new McpSecurityConfigView(List.of(), 4000, 2000, 500);
    }
}
