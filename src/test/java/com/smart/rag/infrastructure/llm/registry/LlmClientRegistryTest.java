package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.config.LlmByokProperties;
import com.smart.rag.infrastructure.llm.config.ByokConfigSource;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.LlmCircuitBreakerAdapterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LlmClientRegistry BYOK 单元测试（design §5.3 / Step 10）—
 * cache-aside lazy 构建、缓存命中、空链 delegate 不缓存、invalidate 重建、disabledSet 归一化。
 */
@ExtendWith(MockitoExtension.class)
class LlmClientRegistryTest {

    @Mock private LlmClientFactory factory;
    @Mock private ByokConfigSource configSource;
    @Mock private LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry;
    @Mock private ScopedTasks scopedTasks;
    @Mock private LlmMetrics metrics;

    private LlmByokProperties byokProperties;
    private LlmClientRegistry registry;

    @BeforeEach
    void setUp() {
        byokProperties = new LlmByokProperties();
        byokProperties.setEnabled(true);
        registry = new LlmClientRegistry(factory, scopedTasks, configSource,
            byokProperties, circuitBreakerRegistry, metrics);
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

    /** 非空 resolved（内容不重要，factory.buildSnapshot 已 mock 返回预设 userSnap） */
    private static List<LlmClientFactory.ResolvedCandidate> resolved() {
        return List.of(mock(LlmClientFactory.ResolvedCandidate.class));
    }

    // ===== delegate（非 BYOK 场景）=====

    @Test
    void getUserChain_byokDisabled_delegates_system() {
        byokProperties.setEnabled(false);
        CapabilityClient sys = client("sys-chat");
        initSystem(snap(LlmCapability.CHAT, "sys-chat", sys));

        List<CapabilityClient> result = registry.getUserChain(LlmCapability.CHAT, 7L);

        assertThat(result).containsExactly(sys);
        verifyNoInteractions(configSource);
    }

    @Test
    void getUserChain_nullUserId_delegates_system() {
        CapabilityClient sys = client("sys-chat");
        initSystem(snap(LlmCapability.CHAT, "sys-chat", sys));

        List<CapabilityClient> result = registry.getUserChain(LlmCapability.CHAT, null);

        assertThat(result).containsExactly(sys);
        verifyNoInteractions(configSource);
    }

    @Test
    void getUserChain_nonChat_delegates_system() {
        CapabilityClient sys = client("sys-embed");
        initSystem(snap(LlmCapability.EMBEDDING, "sys-embed", sys));

        List<CapabilityClient> result = registry.getUserChain(LlmCapability.EMBEDDING, 7L);

        assertThat(result).containsExactly(sys);
        verifyNoInteractions(configSource);
    }

    // ===== cache-aside lazy + 缓存命中 =====

    @Test
    void getUserChain_cacheMiss_lazy_builds_and_caches() {
        CapabilityClient userClient = client("u:7:deepseek-chat");
        RegistrySnapshot userSnap = snap(LlmCapability.CHAT, "u:7:deepseek-chat", userClient);
        when(configSource.userChain(7L, LlmCapability.CHAT)).thenReturn(resolved());
        when(factory.buildSnapshot(anyList())).thenReturn(userSnap);
        initSystem(RegistrySnapshot.empty());

        List<CapabilityClient> r1 = registry.getUserChain(LlmCapability.CHAT, 7L);
        List<CapabilityClient> r2 = registry.getUserChain(LlmCapability.CHAT, 7L);

        assertThat(r1).containsExactly(userClient);
        assertThat(r2).containsExactly(userClient);
        verify(configSource, times(1)).userChain(7L, LlmCapability.CHAT); // 第二次命中缓存
    }

    @Test
    void getUserChain_emptyResolved_delegates_system_without_caching() {
        when(configSource.userChain(7L, LlmCapability.CHAT)).thenReturn(List.of());
        CapabilityClient sys = client("sys-chat");
        initSystem(snap(LlmCapability.CHAT, "sys-chat", sys));

        List<CapabilityClient> r1 = registry.getUserChain(LlmCapability.CHAT, 7L);
        List<CapabilityClient> r2 = registry.getUserChain(LlmCapability.CHAT, 7L);

        assertThat(r1).containsExactly(sys);
        assertThat(r2).containsExactly(sys);
        verify(configSource, times(2)).userChain(7L, LlmCapability.CHAT); // 空链不缓存，每次调
    }

    @Test
    void invalidateUser_clears_cache_next_call_rebuilds() {
        CapabilityClient c1 = client("u:7:m1");
        CapabilityClient c2 = client("u:7:m1-v2");
        RegistrySnapshot snap1 = snap(LlmCapability.CHAT, "u:7:m1", c1);
        RegistrySnapshot snap2 = snap(LlmCapability.CHAT, "u:7:m1-v2", c2);
        when(configSource.userChain(7L, LlmCapability.CHAT)).thenReturn(resolved());
        when(factory.buildSnapshot(anyList())).thenReturn(snap1).thenReturn(snap2);
        initSystem(RegistrySnapshot.empty());

        List<CapabilityClient> before = registry.getUserChain(LlmCapability.CHAT, 7L);
        registry.invalidateUser(7L);
        List<CapabilityClient> after = registry.getUserChain(LlmCapability.CHAT, 7L);

        assertThat(before).containsExactly(c1);
        assertThat(after).containsExactly(c2); // invalidate 后重建新 client
        verify(configSource, times(2)).userChain(7L, LlmCapability.CHAT);
    }

    @Test
    void getUserChain_disabledSet_normalization_strips_user_prefix() {
        CapabilityClient userClient = client("u:7:deepseek-chat");
        RegistrySnapshot userSnap = snap(LlmCapability.CHAT, "u:7:deepseek-chat", userClient);
        when(configSource.userChain(7L, LlmCapability.CHAT)).thenReturn(resolved());
        when(factory.buildSnapshot(anyList())).thenReturn(userSnap);
        initSystem(RegistrySnapshot.empty());
        // 运行时紧急禁用 modelCode（系统级 disable 作用于所有用户同名 BYOK candidate，P1-5 归一化）
        registry.disable("deepseek-chat");

        List<CapabilityClient> result = registry.getUserChain(LlmCapability.CHAT, 7L);

        // u:7:deepseek-chat 剥前缀 → deepseek-chat ∈ 系统级 disabledSet → 被过滤（P1-5）
        assertThat(result).isEmpty();
    }

    // ===== stripUserPrefix 静态 =====

    @Test
    void stripUserPrefix_strips_namespaced_prefix() {
        assertThat(LlmClientRegistry.stripUserPrefix("u:7:deepseek-chat")).isEqualTo("deepseek-chat");
        assertThat(LlmClientRegistry.stripUserPrefix("u:12345:gpt-4")).isEqualTo("gpt-4");
        assertThat(LlmClientRegistry.stripUserPrefix("deepseek-chat")).isEqualTo("deepseek-chat");
    }
}
