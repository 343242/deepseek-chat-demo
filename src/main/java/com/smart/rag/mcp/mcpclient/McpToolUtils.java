package com.smart.rag.mcp.mcpclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.micrometer.common.util.StringUtils;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaUtils;

/**
 * MCP 工具工具类——工具名清洗 + ToolDefinition 组装。
 * <p>
 * 参照 Spring AI 2.0.0 {@code McpToolUtils}，适配差异：
 * <ul>
 *   <li>{@code JsonHelper}（2.0.0 有，1.1.6 无）→ Jackson {@link ObjectMapper} 替代</li>
 *   <li>{@code Tool.inputSchema()} 返回 {@code Map<String,Object>}（SDK 2.0.0）→ 序列化成 String 给 {@link ToolDefinition}</li>
 * </ul>
 * 仅保留项目需要的 {@link #format(String)} 和 {@link #createToolDefinition(String, McpSchema.Tool)}，
 * 省略 2.0.0 的 toSyncToolSpecification / getMcpExchange 等（server 侧，本项目不做 MCP server）。
 *
 * @author Christian Tzolov, Ilayaperumal Gopinathan（原 Spring AI）
 */
public final class McpToolUtils {

    /** Tool context key：存储 MCP exchange 对象（server 侧用，本项目 client 侧不填）。 */
    public static final String TOOL_CONTEXT_MCP_EXCHANGE_KEY = "exchange";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private McpToolUtils() {
    }

    /**
     * 工具名前缀拼接（含 title）。
     *
     * @param prefix   client 名（serverInfo.name）
     * @param title    server 连接名（可空）
     * @param toolName 原始工具名
     * @return 前缀全名（≤64 字符）
     */
    public static String prefixedToolName(String prefix, String title, String toolName) {
        if (StringUtils.isEmpty(prefix) || StringUtils.isEmpty(toolName)) {
            throw new IllegalArgumentException("Prefix or toolName cannot be null or empty");
        }
        String input = shorten(format(prefix));
        if (!StringUtils.isEmpty(title)) {
            input = input + "_" + format(title);
        }
        input = input + "_" + format(toolName);
        if (input.length() > 64) {
            input = input.substring(input.length() - 64);
        }
        return input;
    }

    public static String prefixedToolName(String prefix, String toolName) {
        return prefixedToolName(prefix, null, toolName);
    }

    /**
     * 清洗工具名为合法集 {@code [a-zA-Z0-9_-]}（含 CJK 块），替换 {@code -} 为 {@code _}。
     */
    public static String format(String input) {
        String formatted = input.replaceAll(
                "[^\\p{IsHan}\\p{InCJK_Unified_Ideographs}\\p{InCJK_Compatibility_Ideographs}a-zA-Z0-9_-]", "");
        return formatted.replaceAll("-", "_");
    }

    /**
     * 缩短：按下划线分词取首字母（如 {@code "my_cool_server" → "m_c_s"}）。
     */
    private static String shorten(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return java.util.stream.Stream.of(input.toLowerCase().split("_"))
                .filter(word -> !word.isEmpty())
                .map(word -> String.valueOf(word.charAt(0)))
                .collect(java.util.stream.Collectors.joining("_"));
    }

    /**
     * 从 MCP {@link McpSchema.Tool} 组装 Spring AI {@link ToolDefinition}。
     * <p>
     * SDK 2.0.0 {@code Tool.inputSchema()} 返回 {@code Map<String,Object>} → 序列化成 JSON String →
     * {@link JsonSchemaUtils#ensureValidInputSchema(String)} 校验后交给 {@link DefaultToolDefinition}。
     *
     * @param prefixedToolName 前缀工具名
     * @param tool             MCP 工具元数据
     * @return Spring AI ToolDefinition
     */
    public static ToolDefinition createToolDefinition(String prefixedToolName, McpSchema.Tool tool) {
        return DefaultToolDefinition.builder()
                .name(prefixedToolName)
                .description(tool.description())
                .inputSchema(JsonSchemaUtils.ensureValidInputSchema(toJson(tool.inputSchema())))
                .build();
    }

    private static String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize input schema: " + value, e);
        }
    }
}
