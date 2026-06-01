package com.smart.rag.rag.config;

import com.smart.rag.infrastructure.provider.ModelProvider;
import com.smart.rag.infrastructure.provider.ProviderRegistry;
import com.smart.rag.chat.service.ModelRegistryRefresher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagConfig — resolveRewriteBuilder")
class RagConfigTest {

    @Mock
    private ChatClient.Builder defaultBuilder;

    @Mock
    private ProviderRegistry providerRegistry;

    @Mock
    private ModelRegistryRefresher refresher;

    @Mock
    private ModelProvider provider;

    @Mock
    private ChatClient mockChatClient;

    @Mock
    private ChatClient.Builder mockMutateBuilder;

    private RagConfig ragConfig;

    @BeforeEach
    void setUp() {
        ragConfig = new RagConfig();
    }

    @Nested
    @DisplayName("未配置 queryRewriteModel")
    class DefaultModelTest {

        @Test
        @DisplayName("model 为 null 时使用全局默认 Builder")
        void nullModelUsesDefault() {
            var props = defaultProps(null, null);

            // 使用反射或直接调用 private 方法不现实，通过行为验证
            // 这里验证 rewriteQueryTransformer Bean 能正常构建
            // 因 Bean 依赖较多，只验证核心逻辑：model null → 不查 ProviderRegistry
            var transformer = ragConfig.rewriteQueryTransformer(
                    defaultBuilder, props, providerRegistry, refresher);

            assertThat(transformer).isNotNull();
            verifyNoInteractions(providerRegistry);
            verifyNoInteractions(refresher);
        }

        @Test
        @DisplayName("model 为空字符串时使用全局默认 Builder")
        void blankModelUsesDefault() {
            var props = defaultProps("", null);

            var transformer = ragConfig.rewriteQueryTransformer(
                    defaultBuilder, props, providerRegistry, refresher);

            assertThat(transformer).isNotNull();
            verifyNoInteractions(providerRegistry);
        }
    }

    @Nested
    @DisplayName("配置了 queryRewriteModel")
    class CustomModelTest {

        @Test
        @DisplayName("复合格式 model 通过 Provider 路由创建 ChatClient")
        void compositeModelRoutesViaProvider() {
            var props = defaultProps("deepseek/deepseek-chat", 0.2);

            when(refresher.getProviderIdForModel("deepseek/deepseek-chat")).thenReturn("deepseek");
            when(providerRegistry.get("deepseek")).thenReturn(provider);
            when(provider.createClient(eq("deepseek-chat"), eq(0.2))).thenReturn(mockChatClient);
            when(mockChatClient.mutate()).thenReturn(mockMutateBuilder);

            var transformer = ragConfig.rewriteQueryTransformer(
                    defaultBuilder, props, providerRegistry, refresher);

            assertThat(transformer).isNotNull();
            verify(provider).createClient("deepseek-chat", 0.2);
            verify(mockChatClient).mutate();
        }

        @Test
        @DisplayName("纯 modelId 格式也能路由")
        void pureModelIdRoutes() {
            var props = defaultProps("deepseek-chat", 0.3);

            when(refresher.getProviderIdForModel("deepseek-chat")).thenReturn("deepseek");
            when(providerRegistry.get("deepseek")).thenReturn(provider);
            when(provider.createClient(eq("deepseek-chat"), eq(0.3))).thenReturn(mockChatClient);
            when(mockChatClient.mutate()).thenReturn(mockMutateBuilder);

            var transformer = ragConfig.rewriteQueryTransformer(
                    defaultBuilder, props, providerRegistry, refresher);

            assertThat(transformer).isNotNull();
            verify(provider).createClient("deepseek-chat", 0.3);
        }

        @Test
        @DisplayName("model 不在索引中时降级到默认")
        void unknownModelFallsBack() {
            var props = defaultProps("unknown/model", 0.2);

            when(refresher.getProviderIdForModel("unknown/model")).thenReturn(null);

            var transformer = ragConfig.rewriteQueryTransformer(
                    defaultBuilder, props, providerRegistry, refresher);

            assertThat(transformer).isNotNull();
            verifyNoInteractions(providerRegistry);
        }

        @Test
        @DisplayName("provider 不在 Registry 中时降级到默认")
        void missingProviderFallsBack() {
            var props = defaultProps("deepseek/deepseek-chat", 0.2);

            when(refresher.getProviderIdForModel("deepseek/deepseek-chat")).thenReturn("deepseek");
            when(providerRegistry.get("deepseek")).thenReturn(null);

            var transformer = ragConfig.rewriteQueryTransformer(
                    defaultBuilder, props, providerRegistry, refresher);

            assertThat(transformer).isNotNull();
            verifyNoInteractions(provider);
        }

        @Test
        @DisplayName("temperature 为 null 时传 null 给 createClient")
        void nullTemperature() {
            var props = defaultProps("deepseek/deepseek-chat", null);

            when(refresher.getProviderIdForModel("deepseek/deepseek-chat")).thenReturn("deepseek");
            when(providerRegistry.get("deepseek")).thenReturn(provider);
            when(provider.createClient(eq("deepseek-chat"), isNull())).thenReturn(mockChatClient);
            when(mockChatClient.mutate()).thenReturn(mockMutateBuilder);

            var transformer = ragConfig.rewriteQueryTransformer(
                    defaultBuilder, props, providerRegistry, refresher);

            assertThat(transformer).isNotNull();
            verify(provider).createClient("deepseek-chat", null);
        }
    }

    private RagRetrievalProperties defaultProps(String rewriteModel, Double rewriteTemp) {
        return new RagRetrievalProperties(
                true, true, "jiebacfg",
                30, 30, 60,
                false, "", "", "", 5,
                true, 0.5, 10, 0.5,
                rewriteModel, rewriteTemp
        );
    }
}
