package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmClientRegistry 系统级单元测试 — 纯系统快照（design llm-client-stateless v3.0 决策 5
 * registry 收窄后的全量回归）：查询 API、refresh 失效替换、disable/enable 禁用集。
 */
@ExtendWith(MockitoExtension.class)
class LlmClientRegistryTest {

    @Mock private LlmClientFactory factory;
    @Mock private ScopedTasks scopedTasks;
    @Mock private com.smart.rag.infrastructure.llm.resilience.AdmissionControlRegistry admissionControlRegistry;
    @Mock private com.smart.rag.infrastructure.llm.resilience.LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry;

    private LlmClientRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new LlmClientRegistry(factory, scopedTasks, admissionControlRegistry, circuitBreakerRegistry);
    }

    private void initSystem(RegistrySnapshot systemSnap) {
        when(factory.buildSnapshot()).thenReturn(systemSnap);
        registry.init();
    }

    private static CapabilityClient client(String candidateId) {
        CapabilityClient c = mock(CapabilityClient.class);
        when(c.candidateId()).thenReturn(candidateId);
        return c;
    }

    private static RegistrySnapshot snap(LlmCapability cap, String candidateId, CapabilityClient client) {
        return new RegistrySnapshot(
            Map.of(candidateId, client),
            Map.of(cap, List.of(client)),
            Map.of(cap, candidateId),
            Map.of(), Map.of(), Set.of());
    }

    // ===== 查询 API =====

    @Test
    void getChain_returns_system_chain() {
        CapabilityClient sys = client("sys-chat");
        initSystem(snap(LlmCapability.CHAT, "sys-chat", sys));

        assertThat(registry.getChain(LlmCapability.CHAT)).containsExactly(sys);
    }

    @Test
    void getDefault_returns_system_default() {
        CapabilityClient sys = client("sys-chat");
        initSystem(snap(LlmCapability.CHAT, "sys-chat", sys));

        assertThat(registry.getDefault(LlmCapability.CHAT)).isSameAs(sys);
    }

    @Test
    void get_unknownCandidate_throws() {
        initSystem(RegistrySnapshot.empty());

        assertThatThrownBy(() -> registry.get("missing"))
            .isInstanceOf(com.smart.rag.infrastructure.exception.RemoteException.class);
    }

    @Test
    void find_unknownCandidate_returnsNull() {
        initSystem(RegistrySnapshot.empty());

        assertThat(registry.find("missing")).isNull();
    }

    // ===== refresh 失效替换 =====

    @Test
    void refresh_replaces_snapshot_and_closes_removed_clients() {
        CapabilityClient old = client("m1");
        initSystem(snap(LlmCapability.CHAT, "m1", old));

        CapabilityClient fresh = client("m2");
        RegistrySnapshot freshSnap = snap(LlmCapability.CHAT, "m2", fresh);
        when(factory.buildSnapshot()).thenReturn(freshSnap);
        registry.refresh();

        assertThat(registry.find("m1")).isNull();
        assertThat(registry.find("m2")).isSameAs(fresh);
        verify(old).close(); // 不再存在的旧 client 被关闭
    }

    @Test
    void refresh_preserves_disabledSet() {
        CapabilityClient c = client("m1");
        initSystem(snap(LlmCapability.CHAT, "m1", c));
        registry.disable("m1");

        RegistrySnapshot rebuilt = snap(LlmCapability.CHAT, "m1", c);
        when(factory.buildSnapshot()).thenReturn(rebuilt);
        registry.refresh();

        // disabledSet 跨 refresh 保留：链被过滤
        assertThat(registry.getChain(LlmCapability.CHAT)).isEmpty();
    }

    // ===== disable/enable =====

    @Test
    void disable_filters_chain_until_enable() {
        CapabilityClient c = client("m1");
        initSystem(snap(LlmCapability.CHAT, "m1", c));

        registry.disable("m1");
        assertThat(registry.getChain(LlmCapability.CHAT)).isEmpty();

        registry.enable("m1");
        assertThat(registry.getChain(LlmCapability.CHAT)).containsExactly(c);
    }
}
