package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.mcp.core.McpServerHealth;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.policy.McpAuthorizer;
import com.smart.rag.mcp.policy.McpToolPolicy;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpServerRegistryImpl: 空载 / per-client init 隔离 / 同名冲突（AC2/R-10/§11.4）")
class McpServerRegistryImplTest {

    @Mock private ObjectProvider<List<McpSyncClient>> clientsProvider;
    @Mock private ObjectProvider<SyncMcpToolCallbackProvider> providerProvider;
    @Mock private McpSyncClient clientA;
    @Mock private McpSyncClient clientB;

    private final McpAuthorizer authorizer = new McpAuthorizer(new McpToolPolicy());
    private final McpCircuitBreakerRegistry registry =
            new McpCircuitBreakerRegistry(new CircuitBreakerProperties(null, null, null), Clock.systemUTC());
    private final FallbackEligibility eligibility = new FallbackEligibility();

    private McpServerRegistryImpl newRegistry() {
        return new McpServerRegistryImpl(clientsProvider, providerProvider, authorizer, registry, eligibility);
    }

    private McpSchema.InitializeResult initResult(String name) {
        McpSchema.InitializeResult ir = mock(McpSchema.InitializeResult.class);
        McpSchema.Implementation impl = mock(McpSchema.Implementation.class);
        when(impl.name()).thenReturn(name);
        when(ir.serverInfo()).thenReturn(impl);
        return ir;
    }

    @Test
    @DisplayName("无 connections → 空载（不抛，enabled=true 无 server 场景）")
    void emptyLoad_whenNoClients() {
        when(clientsProvider.getIfAvailable(any())).thenReturn(List.of());
        McpServerRegistryImpl r = newRegistry();
        r.init();
        assertTrue(r.list().isEmpty());
    }

    @Test
    @DisplayName("per-client init：成功→alive，失败→down，互不影响（§11.4 隔离）")
    void perClientInit_isolation() {
        McpSchema.InitializeResult ir = initResult("knowledge");
        when(clientA.isInitialized()).thenReturn(false);
        when(clientA.initialize()).thenReturn(ir);
        when(clientA.getCurrentInitializationResult()).thenReturn(ir);
        when(clientB.isInitialized()).thenReturn(false);
        when(clientB.initialize()).thenThrow(new RuntimeException("connection refused"));
        when(clientsProvider.getIfAvailable(any())).thenReturn(List.of(clientA, clientB));

        McpServerRegistryImpl r = newRegistry();
        r.init();

        assertEquals(2, r.list().size());
        assertEquals(McpServerHealth.Status.ALIVE,
                r.find(new ServerId("knowledge")).orElseThrow().health().status());
        // clientB 握手失败 → 合成 id + down，不影响 clientA
        assertTrue(r.find(new ServerId("unreachable-1")).isPresent());
        assertEquals(McpServerHealth.Status.DOWN,
                r.find(new ServerId("unreachable-1")).orElseThrow().health().status());
    }

    @Test
    @DisplayName("多个 client 同 serverInfo.name → 抛 ServiceException（R-10 配置冲突）")
    void sameNameConflict_throws() {
        McpSchema.InitializeResult irA = initResult("knowledge");
        McpSchema.InitializeResult irB = initResult("knowledge");
        when(clientA.isInitialized()).thenReturn(true);
        when(clientA.getCurrentInitializationResult()).thenReturn(irA);
        when(clientB.isInitialized()).thenReturn(true);
        when(clientB.getCurrentInitializationResult()).thenReturn(irB);
        when(clientsProvider.getIfAvailable(any())).thenReturn(List.of(clientA, clientB));

        McpServerRegistryImpl r = newRegistry();
        assertThrows(ServiceException.class, r::init);
    }
}
