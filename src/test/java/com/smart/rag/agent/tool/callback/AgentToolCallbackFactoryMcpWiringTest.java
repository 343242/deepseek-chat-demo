package com.smart.rag.agent.tool.callback;

import com.smart.rag.agent.intent.AgentIntent;
import com.smart.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.chat.tool.ToolRegistry;
import com.smart.rag.mcp.adapter.McpToolCallbackAdapter;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.Subject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 出口① 接线单测：{@link AgentToolCallbackFactory#createToolCallbacks} per-request 追加 MCP 工具子集。
 * <p>
 * mock {@link McpToolCallbackAdapter}（隔离 MCP 内核），断言：local++mcp 拼接、{@code AgentIntent→McpIntent}
 * 映射、{@link Subject} 构造、mcp=0 返回 local（默认零行为变更）。
 * <p>
 * 9 个本地工具用 mock（构建期仅闭包捕获、不调用）；{@link ToolRegistry#getToolCallbacks()} 在构造期被调用，
 * stub 为空数组（GENERAL_TOOL 本地集为空，便于精确计数）。
 */
@DisplayName("AgentToolCallbackFactory 出口① 接线：local++mcp + intent 映射 + Subject 构造")
class AgentToolCallbackFactoryMcpWiringTest {

    /** 构造工厂：9 工具 mock（RETURNS_DEFAULTS）+ ToolRegistry（getToolCallbacks 返回空，构造期调用）+ adapter。 */
    private static AgentToolCallbackFactory newFactory(McpToolCallbackAdapter adapter) {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolCallbacks()).thenReturn(new ToolCallback[0]); // 构造期调用 → 非 null
        return new AgentToolCallbackFactory(
                mock(com.smart.rag.agent.tool.HybridSearchTool.class),
                mock(com.smart.rag.agent.tool.VectorSearchTool.class),
                mock(com.smart.rag.agent.tool.Bm25SearchTool.class),
                mock(com.smart.rag.agent.tool.RerankTool.class),
                mock(com.smart.rag.agent.tool.QueryRewriteTool.class),
                mock(com.smart.rag.agent.tool.ParentDocLookupTool.class),
                mock(com.smart.rag.agent.tool.DocDetailTool.class),
                mock(com.smart.rag.agent.tool.KnowledgeBaseInfoTool.class),
                mock(com.smart.rag.agent.tool.AgentEventLookupTool.class),
                toolRegistry,
                adapter);
    }

    @Test
    @DisplayName("AC1/AC2/AC3：RETRIEVAL → 5 local ++ 2 mcp = 7；adapter 收到 McpIntent.RETRIEVAL + Subject(42,7)")
    void retrieval_localPlusMcp_correctIntentAndSubject() {
        McpToolCallbackAdapter adapter = mock(McpToolCallbackAdapter.class);
        when(adapter.toCallbacksForAllServers(any(), any())).thenReturn(new ToolCallback[]{
                mock(ToolCallback.class), mock(ToolCallback.class)});
        AgentToolCallbackFactory factory = newFactory(adapter);

        ToolCallback[] all = factory.createToolCallbacks(AgentIntent.RETRIEVAL, new ToolWorkspace(42L, 7L));

        assertEquals(7, all.length); // 5 retrieval local + 2 mcp
        ArgumentCaptor<McpIntent> intentCap = ArgumentCaptor.forClass(McpIntent.class);
        ArgumentCaptor<Subject> subjCap = ArgumentCaptor.forClass(Subject.class);
        verify(adapter).toCallbacksForAllServers(intentCap.capture(), subjCap.capture());
        assertEquals(McpIntent.RETRIEVAL, intentCap.getValue());
        assertEquals(42L, subjCap.getValue().userId());
        assertEquals(7L, subjCap.getValue().teamId());
    }

    @Test
    @DisplayName("AC6：mcp=0（默认空 registry/空 allowlist）→ 返回 local（RETRIEVAL=5），零行为变更")
    void noMcpTools_returnsLocalOnly() {
        McpToolCallbackAdapter adapter = mock(McpToolCallbackAdapter.class);
        when(adapter.toCallbacksForAllServers(any(), any())).thenReturn(new ToolCallback[0]);
        AgentToolCallbackFactory factory = newFactory(adapter);

        ToolCallback[] all = factory.createToolCallbacks(AgentIntent.RETRIEVAL, new ToolWorkspace(1L, 1L));

        assertEquals(5, all.length); // local only
        verify(adapter).toCallbacksForAllServers(eq(McpIntent.RETRIEVAL), any());
    }

    @Test
    @DisplayName("AC2：DIRECT_ANSWER → local=0；adapter 仍以 McpIntent.DIRECT_ANSWER 查询")
    void directAnswer_localEmpty_mcpQueriedWithDirectAnswer() {
        McpToolCallbackAdapter adapter = mock(McpToolCallbackAdapter.class);
        when(adapter.toCallbacksForAllServers(any(), any())).thenReturn(new ToolCallback[0]);
        AgentToolCallbackFactory factory = newFactory(adapter);

        ToolCallback[] all = factory.createToolCallbacks(AgentIntent.DIRECT_ANSWER, new ToolWorkspace(1L, null));

        assertEquals(0, all.length);
        verify(adapter).toCallbacksForAllServers(eq(McpIntent.DIRECT_ANSWER), any());
    }

    @Test
    @DisplayName("AC2：GENERAL_TOOL → 0 local(空 registry) ++ 1 mcp = 1；adapter 收到 McpIntent.GENERAL_TOOL")
    void generalTool_mcpAppended() {
        McpToolCallbackAdapter adapter = mock(McpToolCallbackAdapter.class);
        when(adapter.toCallbacksForAllServers(any(), any())).thenReturn(new ToolCallback[]{mock(ToolCallback.class)});
        AgentToolCallbackFactory factory = newFactory(adapter);

        ToolCallback[] all = factory.createToolCallbacks(AgentIntent.GENERAL_TOOL, new ToolWorkspace(1L, 1L));

        assertEquals(1, all.length); // 0 local + 1 mcp
        verify(adapter).toCallbacksForAllServers(eq(McpIntent.GENERAL_TOOL), any());
    }

    @Test
    @DisplayName("AC2：DEEP_RETRIEVAL 映射 McpIntent.DEEP_RETRIEVAL")
    void deepRetrieval_intentMapping() {
        McpToolCallbackAdapter adapter = mock(McpToolCallbackAdapter.class);
        when(adapter.toCallbacksForAllServers(any(), any())).thenReturn(new ToolCallback[0]);
        AgentToolCallbackFactory factory = newFactory(adapter);

        factory.createToolCallbacks(AgentIntent.DEEP_RETRIEVAL, new ToolWorkspace(1L, 1L));

        verify(adapter).toCallbacksForAllServers(eq(McpIntent.DEEP_RETRIEVAL), any());
    }

    @Test
    @DisplayName("AC2：toMcpIntent 4 case 类型桥接（值集 1:1）")
    void toMcpIntent_allFourCases() {
        assertEquals(McpIntent.DIRECT_ANSWER, AgentToolCallbackFactory.toMcpIntent(AgentIntent.DIRECT_ANSWER));
        assertEquals(McpIntent.RETRIEVAL, AgentToolCallbackFactory.toMcpIntent(AgentIntent.RETRIEVAL));
        assertEquals(McpIntent.DEEP_RETRIEVAL, AgentToolCallbackFactory.toMcpIntent(AgentIntent.DEEP_RETRIEVAL));
        assertEquals(McpIntent.GENERAL_TOOL, AgentToolCallbackFactory.toMcpIntent(AgentIntent.GENERAL_TOOL));
    }

    @Test
    @DisplayName("AC3：Subject 取自 workspace.getUserId()/getTeamId()（teamId 可空）")
    void subject_builtFromWorkspace_nullTeamIdAllowed() {
        McpToolCallbackAdapter adapter = mock(McpToolCallbackAdapter.class);
        when(adapter.toCallbacksForAllServers(any(), any())).thenReturn(new ToolCallback[0]);
        AgentToolCallbackFactory factory = newFactory(adapter);

        factory.createToolCallbacks(AgentIntent.RETRIEVAL, new ToolWorkspace(99L, null));

        ArgumentCaptor<Subject> subjCap = ArgumentCaptor.forClass(Subject.class);
        verify(adapter).toCallbacksForAllServers(any(), subjCap.capture());
        assertEquals(99L, subjCap.getValue().userId());
        assertNull(subjCap.getValue().teamId());
    }
}
