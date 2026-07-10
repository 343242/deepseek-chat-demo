package com.smart.rag.mcp.admin.dto;

import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateSecurityConfigRequest(
        @Size(max = 100, message = "敏感参数正则最多 100 条")
        List<@NotBlank(message = "敏感参数正则不能为空")
                @Size(max = 512, message = "敏感参数正则不能超过 512 个字符") String> sensitiveArgPatterns,
        @Min(value = 1, message = "默认输出上限必须为正数")
        @Max(value = 100000, message = "默认输出上限不能超过 100000") Integer defaultOutputCapChars,
        @Min(value = 1, message = "高风险输出上限必须为正数")
        @Max(value = 100000, message = "高风险输出上限不能超过 100000") Integer highRiskOutputCapChars,
        @Min(value = 1, message = "工具描述上限必须为正数")
        @Max(value = 10000, message = "工具描述上限不能超过 10000") Integer toolDescCharLimit
) {
    public McpSecurityConfigView toView() {
        int def = defaultOutputCapChars != null ? defaultOutputCapChars : 4000;
        int high = highRiskOutputCapChars != null ? highRiskOutputCapChars : 2000;
        int desc = toolDescCharLimit != null ? toolDescCharLimit : 500;
        List<String> patterns = sensitiveArgPatterns != null ? sensitiveArgPatterns : List.of();
        return new McpSecurityConfigView(patterns, def, high, desc);
    }
}
