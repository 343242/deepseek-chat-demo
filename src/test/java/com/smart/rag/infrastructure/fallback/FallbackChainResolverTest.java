package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.provider.ModelProvider;
import com.smart.rag.infrastructure.provider.ProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FallbackChainResolver")
class FallbackChainResolverTest {

    @Test
    @DisplayName("default-chain 未配置时从已注册 Provider 聚合默认候选")
    void resolve_blankModel_usesProviderFallbackCandidatesWhenDefaultChainAbsent() {
        ModelProvider deepseek = provider("deepseek", List.of("deepseek/deepseek-v4-flash"));
        ModelProvider zhipu = provider("zhipu", List.of("zhipu/glm-4.7-flash"));
        var resolver = new FallbackChainResolver(
                new ChatFallbackProperties(true, 3, List.of(), Map.of()),
                new ProviderRegistry(List.of(deepseek, zhipu))
        );

        assertThat(resolver.resolve(null))
                .containsExactly("deepseek/deepseek-v4-flash", "zhipu/glm-4.7-flash");
    }

    @Test
    @DisplayName("显式 default-chain 优先于 Provider 默认候选")
    void resolve_blankModel_prefersConfiguredDefaultChain() {
        ModelProvider deepseek = provider("deepseek", List.of("deepseek/deepseek-v4-flash"));
        var resolver = new FallbackChainResolver(
                new ChatFallbackProperties(true, 3, List.of("minimax/MiniMax-M2.1"), Map.of()),
                new ProviderRegistry(List.of(deepseek))
        );

        assertThat(resolver.resolve(null))
                .containsExactly("minimax/MiniMax-M2.1");
    }

    private static ModelProvider provider(String providerId, List<String> fallbackCandidates) {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.getProviderId()).thenReturn(providerId);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.getFallbackCandidates()).thenReturn(fallbackCandidates);
        return provider;
    }
}
