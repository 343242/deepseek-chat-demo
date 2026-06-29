package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.mcp.core.McpArgs;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.McpServerHealth;
import com.smart.rag.mcp.core.McpTool;
import com.smart.rag.mcp.core.McpToolResult;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.core.Subject;
import com.smart.rag.mcp.policy.McpAuthorizer;
import com.smart.rag.mcp.policy.McpToolPolicy;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpServerImpl: A1 前缀过滤/剥离 + authz + circuit guard + fail-soft + isError（AC2/AC5/AC9）")
class McpServerImplTest {

    private static final ServerId KNOWLEDGE = new ServerId("knowledge");
    private static final Subject AUTHED = new Subject(1L, 1L);
    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Mock private McpSyncClient client;
    @Mock private SyncMcpToolCallbackProvider provider;
    @Mock private ToolCallback cbSearch;
    @Mock private ToolCallback cbOps;
    @Mock private ToolDefinition defSearch;
    @Mock private ToolDefinition defOps;

    private McpServerImpl server;
    private McpCircuitBreakerRegistry registry;

    /** policy 允许 knowledge_search(intent=RETRIEVAL)，failureThreshold=1 便于熔断测试。 */
    @BeforeEach
    void setUp() {
        McpToolPolicy policy = new McpToolPolicy();
        McpToolPolicy.ToolRule rule = new McpToolPolicy.ToolRule();
        rule.setIntent(McpIntent.RETRIEVAL);
        policy.getTools().put("knowledge_search", rule);
        McpAuthorizer authorizer = new McpAuthorizer(policy);

        registry = new McpCircuitBreakerRegistry(new CircuitBreakerProperties(1, 30000L, 1),
                java.time.Clock.systemUTC());
        server = new McpServerImpl(KNOWLEDGE, client, authorizer, registry,
                new FallbackEligibility(), provider, null);
    }

    private void stubDiscovery() {
        // lenient：不同测试用不同子集（被前缀/authz 过滤的 callback 的部分方法可能不被读取）
        lenient().when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{cbSearch, cbOps});
        lenient().when(cbSearch.getToolDefinition()).thenReturn(defSearch);
        lenient().when(defSearch.name()).thenReturn("knowledge_search");
        lenient().when(defSearch.description()).thenReturn("search kb");
        lenient().when(defSearch.inputSchema()).thenReturn(SCHEMA);
        lenient().when(cbOps.getToolDefinition()).thenReturn(defOps);
        lenient().when(defOps.name()).thenReturn("ops_ticket");
        lenient().when(defOps.description()).thenReturn("ops");
        lenient().when(defOps.inputSchema()).thenReturn(SCHEMA);
    }

    @Test
    @DisplayName("visibleTo：按前缀过滤 + authz + intent（AC5）")
    void visibleTo_filtersByPrefixAuthzIntent() {
        stubDiscovery();
        // 仅 knowledge_search（前缀 knowledge_ + allowlist + intent RETRIEVAL）；ops_ticket 前缀不符
        List<McpTool> visible = server.tools().visibleTo(AUTHED, McpIntent.RETRIEVAL);
        assertEquals(1, visible.size());
        assertEquals("knowledge_search", visible.get(0).name());
        assertEquals(SCHEMA, visible.get(0).inputSchema());

        // intent 不匹配 → 空
        assertTrue(server.tools().visibleTo(AUTHED, McpIntent.GENERAL_TOOL).isEmpty());
    }

    @Test
    @DisplayName("visibleTo：未认证主体 → 空")
    void visibleTo_unauthenticated_empty() {
        stubDiscovery();
        assertTrue(server.tools().visibleTo(new Subject(0L, null), McpIntent.RETRIEVAL).isEmpty());
    }

    @Test
    @DisplayName("call：剥前缀 → 委托 client.callTool(rawName)，isError 透传")
    void call_stripsPrefix_andDelegates() throws Exception {
        when(client.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("hello")), false, java.util.Map.of()));

        McpToolResult result = server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);

        org.mockito.ArgumentCaptor<McpSchema.CallToolRequest> cap =
                org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(cap.capture());
        assertEquals("search", cap.getValue().name(), "剥前缀后传原始工具名给 server");
        assertEquals("hello", result.text());
        assertFalse(result.isError());
    }

    @Test
    @DisplayName("call：CallToolResult.isError=true → McpToolResult.isError=true（C5 不抹平）")
    void call_isErrorNotFlattened() {
        when(client.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("boom")), true, java.util.Map.of()));
        McpToolResult result = server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertTrue(result.isError());
        assertEquals("boom", result.text());
    }

    @Test
    @DisplayName("call：未授权工具 → 抛 ClientException（authz 硬兜底，AC3）")
    void call_unauthorized_throws() {
        assertThrows(ClientException.class,
                () -> server.tools().call("knowledge_secret", McpArgs.empty(), AUTHED));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("call：前缀不符（跨 server 误调）→ 抛 ClientException（R-11）")
    void call_prefixMismatch_throws() {
        // policy 允许 knowledge_search，但传入前缀不符的名字（即便 allowlist 想绕过也拒）
        McpToolPolicy policy = new McpToolPolicy();
        McpToolPolicy.ToolRule r = new McpToolPolicy.ToolRule();
        r.setIntent(McpIntent.RETRIEVAL);
        policy.getTools().put("ops_search", r); // 允许 ops_search，但 server id=knowledge → 前缀不符
        McpServerImpl s = new McpServerImpl(KNOWLEDGE, client, new McpAuthorizer(policy), registry,
                new FallbackEligibility(), provider, null);
        assertThrows(ClientException.class,
                () -> s.tools().call("ops_search", McpArgs.empty(), AUTHED));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("call：服务器异常 → fail-soft 降级为 McpToolResult.error，不抛（AC9）")
    void call_serverFailure_softened() {
        when(client.callTool(any())).thenThrow(new RuntimeException("connection reset"));
        McpToolResult result = server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertTrue(result.isError());
        assertTrue(result.text().contains("tool call failed"));
    }

    @Test
    @DisplayName("call：熔断器 OPEN → 快速失败返回 error，不打远端（§11.2）")
    void call_circuitOpen_fastFails() {
        // failureThreshold=1：一次失败即 OPEN
        when(client.callTool(any())).thenThrow(new RuntimeException("down"));
        server.tools().call("knowledge_search", McpArgs.empty(), AUTHED); // 触发 OPEN
        reset(client); // 第二次不应打远端
        McpToolResult result = server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertTrue(result.isError());
        assertTrue(result.text().contains("circuit open"));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("health：CLOSED=alive / initError=down（熔断器只读投影，D-6/D-7）")
    void health_projection() {
        assertEquals(McpServerHealth.Status.ALIVE, server.health().status());

        McpServerImpl down = new McpServerImpl(new ServerId("dead"), client,
                new McpAuthorizer(new McpToolPolicy()), registry, new FallbackEligibility(), provider,
                "connect refused");
        assertEquals(McpServerHealth.Status.DOWN, down.health().status());
    }

    @Test
    @DisplayName("call：HALF_OPEN 下非 eligible 异常释放探测槽，不卡死熔断器（H1）")
    void call_halfOpen_ineligibleException_releasesProbe() {
        // failureThreshold=1 / cooldown=1ms / halfOpenMaxCalls=1 + 可控时钟
        MutableClock clock = new MutableClock(0);
        McpCircuitBreakerRegistry reg = new McpCircuitBreakerRegistry(
                new CircuitBreakerProperties(1, 1L, 1), clock);
        McpToolPolicy policy = new McpToolPolicy();
        McpToolPolicy.ToolRule rule = new McpToolPolicy.ToolRule();
        rule.setIntent(McpIntent.RETRIEVAL);
        policy.getTools().put("knowledge_search", rule);
        McpServerImpl s = new McpServerImpl(KNOWLEDGE, client, new McpAuthorizer(policy), reg,
                new FallbackEligibility(), provider, null);

        // ① eligible 失败 → recordFailure → OPEN（threshold=1）
        // ② 过 cooldown → HALF_OPEN；探测抛非 eligible（IAE）→ 必须 releaseProbe，不计熔断
        // ③ 槽释放后下一次探测可再次放行打到 client（未修复则会 [circuit open] 不打 client）
        when(client.callTool(any()))
                .thenThrow(new RuntimeException("net down"))
                .thenThrow(new IllegalArgumentException("bad arg"))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("ok")), false, java.util.Map.of()));

        s.tools().call("knowledge_search", McpArgs.empty(), AUTHED); // ① → OPEN
        assertEquals(CircuitBreakerState.OPEN, reg.stateOf(KNOWLEDGE.value()));
        clock.advance(5); // > cooldown(1ms) → HALF_OPEN
        assertEquals(CircuitBreakerState.HALF_OPEN, reg.stateOf(KNOWLEDGE.value()));

        McpToolResult probe = s.tools().call("knowledge_search", McpArgs.empty(), AUTHED); // ② IAE
        assertTrue(probe.isError());
        assertEquals(CircuitBreakerState.HALF_OPEN, reg.stateOf(KNOWLEDGE.value()),
                "非 eligible 异常不应计熔断（仍 HALF_OPEN，未回 OPEN）");

        McpToolResult ok = s.tools().call("knowledge_search", McpArgs.empty(), AUTHED); // ③ 槽已释放 → 放行
        assertFalse(ok.isError());
        assertEquals("ok", ok.text());
        verify(client, times(3)).callTool(any()); // 3 次都打到 client（HALF_OPEN 未卡死）
    }

    /** 可控时钟（镜像 McpCircuitBreakerRegistryTest），推进 cooldown 进入 HALF_OPEN。 */
    private static final class MutableClock extends Clock {
        long millis;
        MutableClock(long start) { this.millis = start; }
        void advance(long ms) { millis += ms; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
