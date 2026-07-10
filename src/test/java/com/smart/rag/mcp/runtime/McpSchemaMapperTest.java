package com.smart.rag.mcp.runtime;

import com.smart.rag.mcp.core.McpPrompt;
import com.smart.rag.mcp.core.McpResource;
import com.smart.rag.mcp.core.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpSchemaMapperTest {

    @Test
    void mapsToolTextAndErrorFlag() {
        McpSchema.CallToolResult source = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("first"), new McpSchema.TextContent("second")))
                .isError(true)
                .build();

        McpToolResult result = McpSchemaMapper.toToolResult(source);

        assertThat(result.text()).isEqualTo("first\nsecond");
        assertThat(result.isError()).isTrue();
    }

    @Test
    void mapsFirstTextResource() {
        URI uri = URI.create("https://mcp.example.com/resource");
        McpSchema.ReadResourceResult source = new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri.toString(), "text/plain", "content")));

        McpResource result = McpSchemaMapper.toResource(uri, source);

        assertThat(result.uri()).isEqualTo(uri);
        assertThat(result.mimeType()).isEqualTo("text/plain");
        assertThat(result.text()).isEqualTo("content");
    }

    @Test
    void mapsPromptRoleDescriptionAndContent() {
        McpSchema.GetPromptResult source = new McpSchema.GetPromptResult("description", List.of(
                new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("answer"))));

        McpPrompt result = McpSchemaMapper.toPrompt("summarize", source);

        assertThat(result.name()).isEqualTo("summarize");
        assertThat(result.description()).isEqualTo("description");
        assertThat(result.messages()).containsExactly(new McpPrompt.PromptMessage("assistant", "answer"));
    }
}
