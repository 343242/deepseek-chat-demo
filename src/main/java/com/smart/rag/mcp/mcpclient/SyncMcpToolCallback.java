package com.smart.rag.mcp.mcpclient;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 同步适配器：把 MCP {@link Tool} 桥接为 Spring AI {@link ToolCallback}。
 * <p>
 * {@code call()} 内委托 {@link McpSyncClient#callTool} 执行远端工具，处理 JSON↔Map 转换和异常。
 * 参照 Spring AI 2.0.0 {@code SyncMcpToolCallback}，适配差异：
 * <ul>
 *   <li>{@code JsonHelper}（2.0.0 有，1.1.6 无）→ Jackson {@link ObjectMapper}</li>
 *   <li>SDK 2.0.0 {@code CallToolRequest.builder().name().arguments().meta().build()}</li>
 * </ul>
 *
 * @author Christian Tzolov, YunKui Lu, Ilayaperumal Gopinathan（原 Spring AI）
 */
public class SyncMcpToolCallback implements ToolCallback {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> mapTypeRef = new TypeReference<>() {
    };

    private static final Log logger = LogFactory.getLog(SyncMcpToolCallback.class);

    private final McpSyncClient mcpClient;

    private final Tool tool;

    private final String prefixedToolName;

    private final ToolContextToMcpMetaConverter toolContextToMcpMetaConverter;

    SyncMcpToolCallback(McpSyncClient mcpClient, Tool tool, String prefixedToolName,
            ToolContextToMcpMetaConverter toolContextToMcpMetaConverter) {
        Assert.notNull(mcpClient, "MCP client must not be null");
        Assert.notNull(tool, "MCP tool must not be null");
        Assert.hasText(prefixedToolName, "Prefixed tool name must not be empty");
        Assert.notNull(toolContextToMcpMetaConverter, "ToolContextToMcpMetaConverter must not be null");
        this.mcpClient = mcpClient;
        this.tool = tool;
        this.prefixedToolName = prefixedToolName;
        this.toolContextToMcpMetaConverter = toolContextToMcpMetaConverter;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return McpToolUtils.createToolDefinition(this.prefixedToolName, this.tool);
    }

    /**
     * 返回<b>不带前缀</b>的原始 MCP 工具名（callTool 时用原名，非 prefixedToolName）。
     */
    public String getOriginalToolName() {
        return this.tool.name();
    }

    @Override
    public String call(String toolCallInput) {
        return this.call(toolCallInput, null);
    }

    @Override
    public String call(String toolCallInput, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(toolCallInput)) {
            if (logger.isWarnEnabled()) {
                logger.warn("Tool call arguments are null or empty for MCP tool: " + this.tool.name()
                        + ". Using empty JSON object as default.");
            }
            toolCallInput = "{}";
        }

        Map<String, Object> arguments;
        try {
            arguments = objectMapper.readValue(toolCallInput, mapTypeRef);
        } catch (JsonProcessingException e) {
            throw new ToolExecutionException(this.getToolDefinition(), e);
        }

        CallToolResult response;
        try {
            Map<String, Object> mcpMeta = toolContext != null ? this.toolContextToMcpMetaConverter.convert(toolContext)
                    : null;
            CallToolRequest request = CallToolRequest.builder(this.tool.name())
                    .arguments(arguments)
                    .meta(mcpMeta)
                    .build();
            response = this.mcpClient.callTool(request);
        } catch (Exception ex) {
            logger.error("Exception while tool calling: ", ex);
            throw new ToolExecutionException(this.getToolDefinition(), ex);
        }

        if (response.isError() != null && response.isError()) {
            if (logger.isErrorEnabled()) {
                logger.error("Error calling tool: " + response.content());
            }
            throw new ToolExecutionException(this.getToolDefinition(),
                    new IllegalStateException("Error calling tool: " + response.content()));
        }
        return toJson(response.content());
    }

    private static String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool response: " + value, e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private @Nullable McpSyncClient mcpClient;

        private @Nullable Tool tool;

        private @Nullable String prefixedToolName;

        private ToolContextToMcpMetaConverter toolContextToMcpMetaConverter = ToolContextToMcpMetaConverter
                .defaultConverter();

        public Builder mcpClient(McpSyncClient mcpClient) {
            this.mcpClient = mcpClient;
            return this;
        }

        public Builder tool(Tool tool) {
            this.tool = tool;
            return this;
        }

        public Builder prefixedToolName(String prefixedToolName) {
            this.prefixedToolName = prefixedToolName;
            return this;
        }

        public Builder toolContextToMcpMetaConverter(ToolContextToMcpMetaConverter converter) {
            Assert.notNull(converter, "ToolContextToMcpMetaConverter must not be null");
            this.toolContextToMcpMetaConverter = converter;
            return this;
        }

        public SyncMcpToolCallback build() {
            Assert.notNull(this.mcpClient, "MCP client must not be null");
            Assert.notNull(this.tool, "MCP tool must not be null");
            if (this.prefixedToolName == null) {
                this.prefixedToolName = McpToolUtils.format(this.tool.name());
            }
            return new SyncMcpToolCallback(this.mcpClient, this.tool, this.prefixedToolName,
                    this.toolContextToMcpMetaConverter);
        }
    }
}
