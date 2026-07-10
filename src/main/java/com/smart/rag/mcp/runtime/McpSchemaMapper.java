package com.smart.rag.mcp.runtime;

import com.smart.rag.mcp.core.McpPrompt;
import com.smart.rag.mcp.core.McpResource;
import com.smart.rag.mcp.core.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** Pure mappings from MCP SDK schema objects to the module's core models. */
public final class McpSchemaMapper {

    private McpSchemaMapper() {
    }

    public static McpToolResult toToolResult(McpSchema.CallToolResult result) {
        String text = "";
        if (result.content() != null) {
            text = result.content().stream()
                    .filter(content -> content instanceof McpSchema.TextContent)
                    .map(content -> ((McpSchema.TextContent) content).text())
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
        }
        return new McpToolResult(text, Boolean.TRUE.equals(result.isError()));
    }

    public static McpResource toResource(URI uri, McpSchema.ReadResourceResult result) {
        String text = null;
        String mimeType = null;
        if (result.contents() != null) {
            for (McpSchema.ResourceContents contents : result.contents()) {
                if (contents instanceof McpSchema.TextResourceContents textContents) {
                    text = textContents.text();
                    mimeType = textContents.mimeType();
                    break;
                }
            }
        }
        return new McpResource(uri, text, mimeType);
    }

    public static McpPrompt toPrompt(String name, McpSchema.GetPromptResult result) {
        List<McpPrompt.PromptMessage> messages = new ArrayList<>();
        if (result.messages() != null) {
            for (McpSchema.PromptMessage message : result.messages()) {
                String role = message.role() == null
                        ? "user"
                        : message.role().name().toLowerCase(Locale.ROOT);
                String content = message.content() instanceof McpSchema.TextContent textContent
                        ? textContent.text()
                        : "";
                messages.add(new McpPrompt.PromptMessage(role, content));
            }
        }
        return new McpPrompt(name, result.description(), messages);
    }
}
