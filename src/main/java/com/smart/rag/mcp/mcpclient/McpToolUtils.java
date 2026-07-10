package com.smart.rag.mcp.mcpclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final int MAX_SERVER_ID_LENGTH = 48;
    private static final int HASH_LENGTH = 12;

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
        String serverSegment = canonicalServerId(prefix);
        String toolSegment = requireSegment(toolName, "MCP 工具名称");
        String titleSegment = title == null || title.isBlank() ? null : requireSegment(title, "MCP 连接名称");
        String fullName = titleSegment == null
                ? serverSegment + "_" + toolSegment
                : serverSegment + "_" + titleSegment + "_" + toolSegment;
        return boundName(fullName);
    }

    public static String prefixedToolName(String prefix, String toolName) {
        return prefixedToolName(prefix, null, toolName);
    }

    public static String prefixedToolName(McpConnectionInfo connectionInfo, McpSchema.Tool tool) {
        if (connectionInfo == null || connectionInfo.initializeResult() == null
                || connectionInfo.initializeResult().serverInfo() == null || tool == null) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                    "MCP 工具命名缺少 Server 初始化信息");
        }
        return prefixedToolName(connectionInfo.initializeResult().serverInfo().name(), tool.name());
    }

    /**
     * 清洗工具名为合法集 {@code [a-zA-Z0-9_-]}（含 CJK 块），替换 {@code -} 为 {@code _}。
     */
    public static String format(String input) {
        if (input == null) {
            return "";
        }
        String formatted = input.trim()
                .replaceAll("[^\\p{IsHan}\\p{InCJK_Unified_Ideographs}\\p{InCJK_Compatibility_Ideographs}a-zA-Z0-9_-]+", "_")
                .replace('-', '_')
                .replaceAll("_+", "_");
        return formatted.replaceAll("^_+|_+$", "");
    }

    public static String canonicalServerId(String serverName) {
        return bound(requireSegment(serverName, "MCP Server 名称"), MAX_SERVER_ID_LENGTH);
    }

    private static String requireSegment(String value, String label) {
        String segment = format(value);
        if (segment.isEmpty()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, label + "清洗后不能为空");
        }
        return segment;
    }

    private static String boundName(String fullName) {
        return bound(fullName, MAX_TOOL_NAME_LENGTH);
    }

    private static String bound(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        String suffix = "_" + sha256Prefix(value);
        return value.substring(0, maxLength - suffix.length()) + suffix;
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, HASH_LENGTH / 2);
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "MCP 工具名称摘要生成失败", e);
        }
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
            throw new ServiceException(ServiceErrorCode.SERIALIZATION_FAILED,
                    "MCP 工具输入结构序列化失败", e);
        }
    }
}
