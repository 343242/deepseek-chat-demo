package com.smart.rag.mcp.admin.dto;

import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;

import java.util.List;

public record UpdateSecurityConfigRequest(
        List<String> sensitiveArgPatterns,
        Integer defaultOutputCapChars,
        Integer highRiskOutputCapChars,
        Integer toolDescCharLimit
) {
    public McpSecurityConfigView toView() {
        int def = defaultOutputCapChars != null ? defaultOutputCapChars : 4000;
        int high = highRiskOutputCapChars != null ? highRiskOutputCapChars : 2000;
        int desc = toolDescCharLimit != null ? toolDescCharLimit : 500;
        List<String> patterns = sensitiveArgPatterns != null ? sensitiveArgPatterns : List.of();
        return new McpSecurityConfigView(patterns, def, high, desc);
    }
}
