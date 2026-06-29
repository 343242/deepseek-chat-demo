package com.smart.rag.mcp.adapter;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.mcp.core.McpArgs;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.McpTool;
import com.smart.rag.mcp.core.McpToolResult;
import com.smart.rag.mcp.core.McpTools;
import com.smart.rag.mcp.core.Subject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolCallbackAdapter: inputSchema 透传(AC10) + inputType=Map(B1) + isError + authz 不可绕过")
class McpToolCallbackAdapterTest {

    private static final String MCP_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}";

    @Mock
    private McpTools tools;

    private final Subject subj = new Subject(1L, 1L);
    private final McpToolCallbackAdapter adapter = new McpToolCallbackAdapter();

    private void stubOneVisibleTool() {
        when(tools.visibleTo(subj, McpIntent.RETRIEVAL))
                .thenReturn(List.of(new McpTool("knowledge_search", "search kb", MCP_SCHEMA)));
    }

    @Test
    @DisplayName("AC10：adapter 产 ToolCallback.inputSchema() == MCP 原始 schema（非退化）")
    void inputSchema_transparentFromMcp() {
        stubOneVisibleTool();
        ToolCallback[] cbs = adapter.toCallbacks(tools, McpIntent.RETRIEVAL, subj);
        assertEquals(1, cbs.length);
        assertEquals(MCP_SCHEMA, cbs[0].getToolDefinition().inputSchema());
        assertEquals("knowledge_search", cbs[0].getToolDefinition().name());
        assertEquals("search kb", cbs[0].getToolDefinition().description());
    }

    @Test
    @DisplayName("B1：inputType=Map，JSON object args 执行不抛 + 委托回 McpTools.call")
    void mapInputType_executesAndDelegates() {
        stubOneVisibleTool();
        when(tools.call(eq("knowledge_search"), any(McpArgs.class), eq(subj)))
                .thenReturn(McpToolResult.success("ok-result"));
        ToolCallback cb = adapter.toCallbacks(tools, McpIntent.RETRIEVAL, subj)[0];

        // 框架按 inputType(Map) 反序列化 JSON object → 喂 BiFunction → tools.call（不抛 MismatchedInputException）
        String out = cb.call("{\"query\":\"hello\"}");
        assertNotNull(out);
        assertTrue(out.contains("ok-result"));
        verify(tools).call(eq("knowledge_search"), any(McpArgs.class), eq(subj));
    }

    @Test
    @DisplayName("C5：isError=true → 结果含 [TOOL_ERROR] 前缀")
    void isError_prefixedInResult() {
        stubOneVisibleTool();
        when(tools.call(eq("knowledge_search"), any(McpArgs.class), eq(subj)))
                .thenReturn(McpToolResult.error("boom"));
        ToolCallback cb = adapter.toCallbacks(tools, McpIntent.RETRIEVAL, subj)[0];

        String out = cb.call("{}");
        assertTrue(out.contains("[TOOL_ERROR]"));
    }

    @Test
    @DisplayName("authz 不可绕过：ToolCallback 执行必经内核 call（拒绝时抛/委托）")
    void authzNotBypassable_delegatesToKernel() {
        stubOneVisibleTool();
        when(tools.call(eq("knowledge_search"), any(McpArgs.class), eq(subj)))
                .thenThrow(new ClientException(ClientErrorCode.FORBIDDEN, "denied"));
        ToolCallback cb = adapter.toCallbacks(tools, McpIntent.RETRIEVAL, subj)[0];

        // 执行必委托回 tools.call（内核 authz），拒绝传播
        assertThrows(Exception.class, () -> cb.call("{}"));
        verify(tools).call(eq("knowledge_search"), any(McpArgs.class), eq(subj));
    }

    @Test
    @DisplayName("空可见集 → 空 ToolCallback[]")
    void emptyVisible_emptyCallbacks() {
        when(tools.visibleTo(subj, McpIntent.GENERAL_TOOL)).thenReturn(List.of());
        assertEquals(0, adapter.toCallbacks(tools, McpIntent.GENERAL_TOOL, subj).length);
    }
}
