package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import com.smart.rag.mcp.admin.service.McpSecurityConfigAccessor;
import com.smart.rag.mcp.admin.service.McpToolConfigAccessor;
import com.smart.rag.mcp.core.McpServerHealth;
import com.smart.rag.mcp.core.ServerId;
import com.smart.rag.mcp.mcpclient.SyncMcpToolCallbackProvider;
import com.smart.rag.mcp.policy.McpAuthorizer;
import com.smart.rag.mcp.policy.McpDescriptionSanitizer;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpServerRegistryImpl: snapshot mode + addServer/removeServer/replaceServer")
class McpServerRegistryImplTest {

    @Mock private ObjectProvider<SyncMcpToolCallbackProvider> providerProvider;

    private final McpAuthorizer authorizer = new McpAuthorizer(mock(McpToolConfigAccessor.class));
    private final McpCircuitBreakerRegistry registry =
            new McpCircuitBreakerRegistry(new CircuitBreakerProperties(null, null, null), Clock.systemUTC());
    private final FallbackEligibility eligibility = new FallbackEligibility();
    private final McpDescriptionSanitizer descriptionSanitizer =
            new McpDescriptionSanitizer(mock(McpToolConfigAccessor.class), mock(McpSecurityConfigAccessor.class));

    private McpServerRegistryImpl newRegistry() {
        return new McpServerRegistryImpl(authorizer, registry, eligibility, descriptionSanitizer, providerProvider);
    }

    private McpServerConfig config(Long id, String serverId) {
        McpServerConfig c = new McpServerConfig();
        c.setId(id);
        c.setServerId(serverId);
        c.setUrl("https://mcp.example.com");
        return c;
    }

    @Test
    @DisplayName("初始状态：list 为空、currentVersion=0、find 返回 empty")
    void initialState_empty() {
        McpServerRegistryImpl r = newRegistry();
        assertThat(r.list()).isEmpty();
        assertThat(r.currentVersion()).isZero();
        assertThat(r.find(new ServerId("any"))).isEmpty();
    }

    @Test
    @DisplayName("addServer(client)：list 含该 server、find 命中、currentVersion 递增")
    void addServer_withClient_succeeds() {
        McpServerRegistryImpl r = newRegistry();
        McpSyncClient client = mock(McpSyncClient.class);
        McpServerConfig cfg = config(1L, "weather");

        r.addServer(cfg, client);

        assertThat(r.list()).hasSize(1);
        assertThat(r.find(new ServerId("weather"))).isPresent();
        assertThat(r.currentVersion()).isEqualTo(1L);
    }


    @Test
    @DisplayName("addServer(serverId=null)：抛 IllegalArgumentException")
    void addServer_nullServerId_throws() {
        McpServerRegistryImpl r = newRegistry();
        McpServerConfig cfg = config(42L, null);

        assertThatThrownBy(() -> r.addServer(cfg, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("removeServer：list 不再含、version 递增")
    void removeServer_removesFromSnapshot() {
        McpServerRegistryImpl r = newRegistry();
        McpSyncClient client = mock(McpSyncClient.class);
        r.addServer(config(1L, "weather"), client);
        assertThat(r.currentVersion()).isEqualTo(1L);

        r.removeServer(new ServerId("weather"));

        assertThat(r.list()).isEmpty();
        assertThat(r.find(new ServerId("weather"))).isEmpty();
        assertThat(r.currentVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("replaceServer：同 serverId 替换，version 递增")
    void replaceServer_swapsAtomically() {
        McpServerRegistryImpl r = newRegistry();
        McpSyncClient oldClient = mock(McpSyncClient.class);
        McpSyncClient newClient = mock(McpSyncClient.class);
        McpServerConfig cfg = config(1L, "weather");

        r.addServer(cfg, oldClient);
        long v0 = r.currentVersion();

        r.replaceServer(cfg, newClient);

        assertThat(r.currentVersion()).isGreaterThan(v0);
        assertThat(r.list()).hasSize(1);
    }

    @Test
    @DisplayName("find(null) 抛 NPE")
    void find_null_throws() {
        McpServerRegistryImpl r = newRegistry();
        assertThatThrownBy(() -> r.find(null))
                .isInstanceOf(NullPointerException.class);
    }
}
