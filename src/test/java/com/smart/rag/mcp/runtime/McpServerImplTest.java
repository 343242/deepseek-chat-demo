package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import com.smart.rag.mcp.admin.mapper.McpToolConfigMapper;
import com.smart.rag.mcp.admin.service.McpSecurityConfigAccessor;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import com.smart.rag.mcp.core.McpArgs;
import com.smart.rag.mcp.core.McpIntent;
import com.smart.rag.mcp.core.McpServerHealth;
import com.smart.rag.mcp.core.McpTool;
import com.smart.rag.mcp.core.McpToolResult;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.core.Subject;
import com.smart.rag.mcp.policy.McpAuthorizer;
import com.smart.rag.mcp.policy.McpDescriptionSanitizer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpServerImpl: direct DB reads + authz + circuit guard + fail-soft")
class McpServerImplTest {

    private static final ServerId KNOWLEDGE = new ServerId("knowledge");
    private static final Subject AUTHED = new Subject(1L, 1L);
    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Mock private McpSyncClient client;
    @Mock private McpToolConfigMapper toolConfigMapper;

    private McpServerImpl server;
    private McpCircuitBreakerRegistry registry;
    private McpToolConfigAccessor toolConfigAccessor;
    private McpDescriptionSanitizer descriptionSanitizer;

    @BeforeEach
    void setUp() {
        toolConfigAccessor = mock(McpToolConfigAccessor.class);
        lenient().when(toolConfigAccessor.get(any())).thenAnswer(invocation -> enabledTool(invocation.getArgument(0)));
        McpSecurityConfigAccessor securityConfigAccessor = mock(McpSecurityConfigAccessor.class);
        lenient().when(securityConfigAccessor.get())
                .thenReturn(com.smart.rag.mcp.admin.entity.McpSecurityConfigView.defaults());
        descriptionSanitizer = new McpDescriptionSanitizer(toolConfigAccessor, securityConfigAccessor);
        McpAuthorizer authorizer = new McpAuthorizer(toolConfigAccessor);

        registry = new McpCircuitBreakerRegistry(new CircuitBreakerProperties(1, 30000L, 1),
                java.time.Clock.systemUTC());
        server = new McpServerImpl(KNOWLEDGE, client, authorizer, registry,
                new FallbackEligibility(), toolConfigMapper, descriptionSanitizer);
    }

    private void stubDbTools() {
        McpToolConfig search = enabledTool("knowledge_search");
        search.setInputSchema(SCHEMA);
        lenient().when(toolConfigMapper.selectVisibleByServerId("knowledge"))
                .thenReturn(List.of(search));
    }

    @Test
    @DisplayName("visibleTo: direct DB read + authz + intent")
    void visibleTo_filtersByAuthzIntent() {
        stubDbTools();
        List<McpTool> visible = server.tools().visibleTo(AUTHED, McpIntent.RETRIEVAL);
        assertEquals(1, visible.size());
        assertEquals("knowledge_search", visible.get(0).name());
        assertEquals(SCHEMA, visible.get(0).inputSchema());

        // No GENERAL_TOOL tools in DB mock → empty
        assertTrue(server.tools().visibleTo(AUTHED, McpIntent.GENERAL_TOOL).isEmpty());
    }

    @Test
    @DisplayName("visibleTo: unauthenticated → empty")
    void visibleTo_unauthenticated_empty() {
        stubDbTools();
        assertTrue(server.tools().visibleTo(new Subject(0L, null), McpIntent.RETRIEVAL).isEmpty());
    }

    @Test
    @DisplayName("call: strip prefix → delegate to client.callTool(rawName)")
    void call_stripsPrefix_andDelegates() {
        when(client.callTool(any())).thenReturn(
                McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent("hello"))).isError(false).build());

        McpToolResult result = server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);

        org.mockito.ArgumentCaptor<McpSchema.CallToolRequest> cap =
                org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(cap.capture());
        assertEquals("search", cap.getValue().name());
        assertEquals("hello", result.text());
        assertFalse(result.isError());
    }

    @Test
    void callUsesPersistedRawNameWhenCanonicalNameDiffers() {
        McpToolConfig config = enabledTool("knowledge_search_docs");
        config.setToolName("search-docs");
        when(toolConfigAccessor.get("knowledge_search_docs")).thenReturn(config);
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("ok"))).isError(false).build());

        server.tools().call("knowledge_search_docs", McpArgs.empty(), AUTHED);

        org.mockito.ArgumentCaptor<McpSchema.CallToolRequest> request =
                org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(request.capture());
        assertEquals("search-docs", request.getValue().name());
    }

    @Test
    @DisplayName("call: isError=true → McpToolResult.isError=true")
    void call_isErrorNotFlattened() {
        when(client.callTool(any())).thenReturn(
                McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent("boom"))).isError(true).build());
        McpToolResult result = server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertTrue(result.isError());
        assertEquals("boom", result.text());
    }

    @Test
    @DisplayName("call: unauthorized tool → ClientException")
    void call_unauthorized_throws() {
        assertThrows(ClientException.class,
                () -> server.tools().call("knowledge_secret", McpArgs.empty(), AUTHED));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("call: prefix mismatch → ClientException")
    void call_prefixMismatch_throws() {
        assertThrows(ClientException.class,
                () -> server.tools().call("ops_search", McpArgs.empty(), AUTHED));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("call: server exception → fail-soft McpToolResult.error")
    void call_serverFailure_softened() {
        when(client.callTool(any())).thenThrow(new RuntimeException("connection reset"));
        McpToolResult result = server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertTrue(result.isError());
        assertEquals("MCP 工具调用失败，请稍后重试", result.text());
        assertFalse(result.text().contains("connection reset"));
    }

    @Test
    @DisplayName("call: circuit OPEN → fast fail error")
    void call_circuitOpen_fastFails() {
        when(client.callTool(any())).thenThrow(new RuntimeException("down"));
        server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        reset(client);
        McpToolResult result = server.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertTrue(result.isError());
        assertTrue(result.text().contains("circuit open"));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("health: CLOSED=alive")
    void health_projection() {
        assertEquals(McpServerHealth.Status.ALIVE, server.health().status());
    }

    @Test
    @DisplayName("call: HALF_OPEN non-eligible exception releases probe")
    void call_halfOpen_ineligibleException_releasesProbe() {
        MutableClock clock = new MutableClock(0);
        McpCircuitBreakerRegistry reg = new McpCircuitBreakerRegistry(
                new CircuitBreakerProperties(1, 1L, 1), clock);
        McpServerImpl s = new McpServerImpl(KNOWLEDGE, client, new McpAuthorizer(toolConfigAccessor), reg,
                new FallbackEligibility(), toolConfigMapper, descriptionSanitizer);

        when(client.callTool(any()))
                .thenThrow(new RuntimeException("net down"))
                .thenThrow(new IllegalArgumentException("bad arg"))
                .thenReturn(McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("ok"))).isError(false).build());

        s.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertEquals(CircuitBreakerState.OPEN, reg.stateOf(KNOWLEDGE.value()));
        clock.advance(5);
        assertEquals(CircuitBreakerState.HALF_OPEN, reg.stateOf(KNOWLEDGE.value()));

        McpToolResult probe = s.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertTrue(probe.isError());
        assertEquals(CircuitBreakerState.HALF_OPEN, reg.stateOf(KNOWLEDGE.value()));

        McpToolResult ok = s.tools().call("knowledge_search", McpArgs.empty(), AUTHED);
        assertFalse(ok.isError());
        assertEquals("ok", ok.text());
        verify(client, times(3)).callTool(any());
    }

    @Test
    void listToolsFromRemotePropagatesClassifiedFailure() {
        when(client.listTools()).thenThrow(new RuntimeException("connection refused"));
        assertThrows(RemoteException.class, () -> server.listToolsFromRemote());
    }

    @Test
    void resourceReadRejectsBlockedUriSchemeBeforeRemoteCall() {
        assertThrows(ClientException.class,
                () -> server.resources().read(URI.create("ldap://internal/secret"), AUTHED));
        verifyNoInteractions(client);
    }

    @Test
    void nullClientResourcesAndPromptsFailAsRemoteUnavailable() {
        McpServerImpl noClient = new McpServerImpl(new ServerId("dead"), null,
                new McpAuthorizer(toolConfigAccessor), registry, new FallbackEligibility(), toolConfigMapper,
                descriptionSanitizer);

        assertThrows(RemoteException.class,
                () -> noClient.resources().read(URI.create("https://remote/resource"), AUTHED));
        assertThrows(RemoteException.class,
                () -> noClient.prompts().get("summary", McpArgs.empty(), AUTHED));
    }

    private static final class MutableClock extends Clock {
        long millis;
        MutableClock(long start) { this.millis = start; }
        void advance(long ms) { millis += ms; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static McpToolConfig enabledTool(String name) {
        if (name.endsWith("secret")) {
            return null;
        }
        McpToolConfig config = new McpToolConfig();
        config.setPrefixedToolName(name);
        config.setToolName(name.substring(name.indexOf('_') + 1));
        config.setEnabled(true);
        config.setIntent(name.endsWith("search") ? "RETRIEVAL" : "GENERAL_TOOL");
        return config;
    }
}
