package com.smart.rag.mcp.admin.service;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.mcp.admin.entity.McpSecurityConfigView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class McpSecurityConfigValidator {

    public McpSecurityConfigView validate(McpSecurityConfigView view) {
        if (view == null) {
            throw invalid("MCP 安全配置不能为空", null);
        }
        int defaultCap = view.defaultOutputCapChars();
        int highRiskCap = view.highRiskOutputCapChars();
        int descriptionCap = view.toolDescCharLimit();
        if (defaultCap < 1 || defaultCap > 100000
                || highRiskCap < 1 || highRiskCap > 100000
                || descriptionCap < 1 || descriptionCap > 10000) {
            throw invalid("MCP 安全配置上限必须在允许范围内", null);
        }
        if (highRiskCap > defaultCap) {
            throw invalid("高风险工具输出上限不能大于默认输出上限", null);
        }
        List<String> patterns = view.sensitiveArgPatterns() == null
                ? List.of()
                : view.sensitiveArgPatterns().stream().map(String::trim).toList();
        if (patterns.size() > 100 || patterns.stream().anyMatch(value -> value.isEmpty() || value.length() > 512)) {
            throw invalid("敏感参数正则数量或长度超出限制", null);
        }
        for (String expression : patterns) {
            try {
                Pattern.compile(expression);
            } catch (PatternSyntaxException e) {
                throw invalid("敏感参数正则格式非法", e);
            }
        }
        return new McpSecurityConfigView(patterns, defaultCap, highRiskCap, descriptionCap);
    }

    private static ClientException invalid(String message, Throwable cause) {
        return new ClientException(ClientErrorCode.VALIDATION_ERROR, message, cause);
    }
}
