package com.smart.rag.mcp.mcpclient;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.CollectionUtils;

/**
 * 策略接口：把 Spring AI {@link ToolContext} 转换为 MCP tool call 的 meta map。
 * <p>
 * 默认实现排除 {@link McpToolUtils#TOOL_CONTEXT_MCP_EXCHANGE_KEY} 和 null 值。
 * 参照 Spring AI 2.0.0 {@code ToolContextToMcpMetaConverter}。
 *
 * @author Christian Tzolov, YunKui Lu（原 Spring AI）
 */
@FunctionalInterface
public interface ToolContextToMcpMetaConverter {

    Map<String, Object> convert(ToolContext toolContext);

    static ToolContextToMcpMetaConverter defaultConverter() {
        return toolContext -> {
            if (toolContext == null || CollectionUtils.isEmpty(toolContext.getContext())) {
                return Map.of();
            }
            return toolContext.getContext().entrySet().stream()
                    .filter(entry -> !McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY.equals(entry.getKey())
                            && entry.getValue() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        };
    }
}
