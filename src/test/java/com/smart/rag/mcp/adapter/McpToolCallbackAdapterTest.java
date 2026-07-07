package com.smart.rag.mcp.adapter;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.mcp.core.McpArgs;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.McpServer;
import com.smart.rag.mcp.core.McpServerRegistry;
import com.smart.rag.mcp.core.McpTool;
import com.smart.rag.mcp.core.McpToolResult;
import com.smart.rag.mcp.core.McpTools;
import com.smart.rag.mcp.core.Subject;
import com.smart.rag.mcp.policy.McpSecurityGuard;
import com.smart.rag.mcp.admin.service.McpSecurityConfigAccessor;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import org.junit.jupiter.api.BeforeEach;
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
@DisplayName("McpToolCallbackAdapter: inputSchema 透传(AC10) + inputType=Map(B1) + isError + authz 不可绕过 + 多 server 聚合")
class McpToolCallbackAdapterTest {

    private static final String MCP_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}";

    @Mock
    private McpServerRegistry registry;

    @Mock
    private McpTools tools;

    private final Subject subj = new Subject(1L, 1L);

    private final McpSecurityGuard securityGuard = buildGuard();

    private static McpSecurityGuard buildGuard() {
        McpSecurityConfigAccessor accessor = mock(McpSecurityConfigAccessor.class);
        when(accessor.patterns()).thenReturn(java.util.List.of());
        when(accessor.get()).thenReturn(com.smart.rag.mcp.admin.entity.McpSecurityConfigView.defaults());
        return new McpSecurityGuard(mock(McpToolConfigAccessor.class), accessor);
    }

    private McpToolCallbackAdapter adapter;

    @BeforeEach
    void setUp() {
        // registry 由 MockitoExtension 在 @BeforeEach 前注入；adapter 现需持 registry 聚合多 server
        adapter = new McpToolCallbackAdapter(registry, securityGuard);
    }

    private void stubOneVisibleTool() {
        when(tools.visibleTo(subj, McpIntent.RETRIEVAL))
                .thenReturn(List.of(new McpTool("knowledge_search", "search kb", MCP_SCHEMA)));
    }

    // === 既有：单 server toCallbacks（B1 / inputSchema / isError / authz） ===

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

    // === 新增：toCallbacksForAllServers 多 server 聚合（出口① 接线） ===

    @Test
    @DisplayName("AC4：聚合多 server 可见工具，名称/数量正确")
    void aggregate_multipleServers() {
        McpServer s1 = mock(McpServer.class);
        McpServer s2 = mock(McpServer.class);
        McpTools t1 = mock(McpTools.class);
        McpTools t2 = mock(McpTools.class);
        when(registry.list()).thenReturn(List.of(s1, s2));
        when(s1.tools()).thenReturn(t1);
        when(s2.tools()).thenReturn(t2);
        when(t1.visibleTo(subj, McpIntent.RETRIEVAL))
                .thenReturn(List.of(new McpTool("kb_search", "kb", MCP_SCHEMA)));
        when(t2.visibleTo(subj, McpIntent.RETRIEVAL))
                .thenReturn(List.of(new McpTool("ops_run", "ops", MCP_SCHEMA)));

        ToolCallback[] all = adapter.toCallbacksForAllServers(McpIntent.RETRIEVAL, subj);
        assertEquals(2, all.length);
        assertEquals("kb_search", all[0].getToolDefinition().name());
        assertEquals("ops_run", all[1].getToolDefinition().name());
    }

    @Test
    @DisplayName("AC5/AC6：空 registry → 空数组（默认零行为变更）")
    void aggregate_emptyRegistry() {
        when(registry.list()).thenReturn(List.of());
        assertEquals(0, adapter.toCallbacksForAllServers(McpIntent.RETRIEVAL, subj).length);
    }

    @Test
    @DisplayName("AC4/AC5：某 server visibleTo 空集（down/熔断/未授权）→ 跳过，其他 server 工具仍包含")
    void aggregate_skipsEmptyServer() {
        McpServer s1 = mock(McpServer.class);
        McpServer s2 = mock(McpServer.class);
        McpTools t1 = mock(McpTools.class);
        McpTools t2 = mock(McpTools.class);
        when(registry.list()).thenReturn(List.of(s1, s2));
        when(s1.tools()).thenReturn(t1);
        when(s2.tools()).thenReturn(t2);
        when(t1.visibleTo(subj, McpIntent.RETRIEVAL)).thenReturn(List.of()); // fail-soft
        when(t2.visibleTo(subj, McpIntent.RETRIEVAL))
                .thenReturn(List.of(new McpTool("ops_run", "ops", MCP_SCHEMA)));

        ToolCallback[] all = adapter.toCallbacksForAllServers(McpIntent.RETRIEVAL, subj);
        assertEquals(1, all.length);
        assertEquals("ops_run", all[0].getToolDefinition().name());
    }

    @Test
    @DisplayName("AC5：未认证 subj → 各 server visibleTo 空集 → 空数组（authz）")
    void aggregate_unauthenticatedSubject() {
        McpServer s1 = mock(McpServer.class);
        McpTools t1 = mock(McpTools.class);
        Subject anon = new Subject(0L, null);
        when(registry.list()).thenReturn(List.of(s1));
        when(s1.tools()).thenReturn(t1);
        when(t1.visibleTo(anon, McpIntent.RETRIEVAL)).thenReturn(List.of()); // authz 拒绝

        assertEquals(0, adapter.toCallbacksForAllServers(McpIntent.RETRIEVAL, anon).length);
    }
}
