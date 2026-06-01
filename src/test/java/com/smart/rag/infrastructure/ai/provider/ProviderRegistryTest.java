package com.smart.rag.infrastructure.ai.provider;

import com.smart.rag.exception.ProviderNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ProviderRegistry 单元测试")
class ProviderRegistryTest {

    private ModelProvider deepSeekProvider;
    private ModelProvider zhipuProvider;
    private ModelProvider unavailableProvider;

    @BeforeEach
    void setUp() {
        deepSeekProvider = mock(ModelProvider.class);
        when(deepSeekProvider.getProviderId()).thenReturn("deepseek");
        when(deepSeekProvider.getDisplayName()).thenReturn("DeepSeek");
        when(deepSeekProvider.isAvailable()).thenReturn(true);

        zhipuProvider = mock(ModelProvider.class);
        when(zhipuProvider.getProviderId()).thenReturn("zhipu");
        when(zhipuProvider.getDisplayName()).thenReturn("智谱 AI");
        when(zhipuProvider.isAvailable()).thenReturn(true);

        unavailableProvider = mock(ModelProvider.class);
        when(unavailableProvider.getProviderId()).thenReturn("minimax");
        when(unavailableProvider.getDisplayName()).thenReturn("MiniMax");
        when(unavailableProvider.isAvailable()).thenReturn(false);
    }

    @Nested
    @DisplayName("正常注册")
    class RegistrationTests {

        @Test
        @DisplayName("所有可用 Provider 被注册")
        void allAvailableProviders_registered() {
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(deepSeekProvider, zhipuProvider));

            assertEquals(2, registry.size());
            assertTrue(registry.isAvailable("deepseek"));
            assertTrue(registry.isAvailable("zhipu"));
        }

        @Test
        @DisplayName("不可用的 Provider 被过滤")
        void unavailableProviders_filtered() {
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(deepSeekProvider, unavailableProvider));

            assertEquals(1, registry.size());
            assertTrue(registry.isAvailable("deepseek"));
            assertFalse(registry.isAvailable("minimax"));
        }

        @Test
        @DisplayName("空列表 → 空注册表，不报错")
        void emptyList_emptyRegistry() {
            ProviderRegistry registry = new ProviderRegistry(List.of());

            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("全部不可用 → 空注册表")
        void allUnavailable_emptyRegistry() {
            ModelProvider unavailable1 = mock(ModelProvider.class);
            when(unavailable1.getProviderId()).thenReturn("a");
            when(unavailable1.isAvailable()).thenReturn(false);

            ModelProvider unavailable2 = mock(ModelProvider.class);
            when(unavailable2.getProviderId()).thenReturn("b");
            when(unavailable2.isAvailable()).thenReturn(false);

            ProviderRegistry registry = new ProviderRegistry(
                    List.of(unavailable1, unavailable2));

            assertEquals(0, registry.size());
        }
    }

    @Nested
    @DisplayName("查询")
    class QueryTests {

        @Test
        @DisplayName("get(存在的 providerId) → 返回对应 Provider")
        void get_existingProvider() {
            ProviderRegistry registry = new ProviderRegistry(List.of(deepSeekProvider));

            ModelProvider result = registry.get("deepseek");
            assertSame(deepSeekProvider, result);
        }

        @Test
        @DisplayName("get(不存在的 providerId) → ProviderNotFoundException")
        void get_nonExistentProvider_throws() {
            ProviderRegistry registry = new ProviderRegistry(List.of(deepSeekProvider));

            ProviderNotFoundException ex = assertThrows(ProviderNotFoundException.class,
                    () -> registry.get("zhipu"));
            assertEquals("zhipu", ex.getProviderId());
        }

        @Test
        @DisplayName("getAll() → 返回所有已注册 Provider")
        void getAll_returnsAllRegistered() {
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(deepSeekProvider, zhipuProvider));

            Collection<ModelProvider> all = registry.getAll();
            assertEquals(2, all.size());
        }

        @Test
        @DisplayName("getAvailableProviderIds() → 返回正确的 ID 集合")
        void getAvailableProviderIds_correct() {
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(deepSeekProvider, zhipuProvider, unavailableProvider));

            Set<String> ids = registry.getAvailableProviderIds();
            assertTrue(ids.contains("deepseek"));
            assertTrue(ids.contains("zhipu"));
            assertFalse(ids.contains("minimax"));
        }
    }

    @Nested
    @DisplayName("不变性")
    class ImmutabilityTests {

        @Test
        @DisplayName("getAll() 返回的集合不可修改")
        void getAll_isImmutable() {
            ProviderRegistry registry = new ProviderRegistry(List.of(deepSeekProvider));

            assertThrows(UnsupportedOperationException.class,
                    () -> registry.getAll().add(zhipuProvider));
        }
    }
}
