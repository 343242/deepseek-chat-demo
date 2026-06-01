package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.client.ChatClientRegistry;
import com.smart.rag.infrastructure.model.ModelInfo;
import com.smart.rag.infrastructure.provider.ModelProvider;
import com.smart.rag.infrastructure.provider.ProviderRegistry;
import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModelRegistryRefresher")
class ModelRegistryRefresherTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("refresh")
    class RefreshTests {

        @Test
        @DisplayName("注册成功 Provider 的纯模型 ID 和复合 ID，并跳过失败 Provider")
        void refresh_registersSuccessfulProviderAndSkipsFailedProvider() {
            ModelProvider deepseek = provider("deepseek");
            ModelProvider zhipu = provider("zhipu");
            ModelInfo model = model("deepseek-chat");
            ChatClient chatClient = mock(ChatClient.class);
            ChatModel chatModel = mock(ChatModel.class);

            when(deepseek.fetchModels()).thenReturn(List.of(model));
            when(deepseek.createClientWithModel("deepseek-chat", null))
                    .thenReturn(new ModelProvider.ClientAndModel(chatClient, chatModel));
            when(zhipu.fetchModels()).thenThrow(new IllegalStateException("provider down"));

            ChatClientRegistry chatClientRegistry = new ChatClientRegistry();
            ModelRegistryRefresher refresher = new ModelRegistryRefresher(
                    new ProviderRegistry(List.of(deepseek, zhipu)),
                    chatClientRegistry,
                    new DefaultScopedTasks()
            );

            boolean refreshed = refresher.refresh();

            assertThat(refreshed).isTrue();
            assertThat(chatClientRegistry.get("deepseek-chat")).isSameAs(chatClient);
            assertThat(chatClientRegistry.get("deepseek/deepseek-chat")).isSameAs(chatClient);
            assertThat(chatClientRegistry.getChatModel("deepseek-chat")).isSameAs(chatModel);
            assertThat(chatClientRegistry.getCachedModels()).containsExactly(model);
            assertThat(refresher.getProviderIdForModel("deepseek-chat")).isEqualTo("deepseek");
            assertThat(refresher.getProviderIdForModel("deepseek/deepseek-chat")).isEqualTo("deepseek");
            assertThat(refresher.getProviderIdForModel("glm-4-air")).isNull();
            verify(zhipu).fetchModels();
            verify(zhipu, never()).createClientWithModel("glm-4-air", null);
        }

        @Test
        @DisplayName("所有 Provider 拉取失败时返回 false 且不替换已有 registry")
        void refresh_allProvidersFail_keepsExistingRegistry() {
            ModelProvider deepseek = provider("deepseek");
            ChatClient existingClient = mock(ChatClient.class);
            ChatClientRegistry chatClientRegistry = new ChatClientRegistry();
            chatClientRegistry.register("existing-model", existingClient);

            when(deepseek.fetchModels()).thenThrow(new IllegalStateException("provider down"));

            ModelRegistryRefresher refresher = new ModelRegistryRefresher(
                    new ProviderRegistry(List.of(deepseek)),
                    chatClientRegistry,
                    new DefaultScopedTasks()
            );

            boolean refreshed = refresher.refresh();

            assertThat(refreshed).isFalse();
            assertThat(chatClientRegistry.get("existing-model")).isSameAs(existingClient);
            assertThat(chatClientRegistry.getAvailableModelIds()).containsExactly("existing-model");
        }

        @Test
        @DisplayName("Provider 拉取任务通过 ScopedTasks 执行并继承 MDC")
        void refresh_fetchesProvidersThroughScopedTasksAndInheritsMdc() {
            ModelProvider deepseek = provider("deepseek");
            ModelInfo model = model("deepseek-chat");
            ChatClient chatClient = mock(ChatClient.class);
            ChatModel chatModel = mock(ChatModel.class);
            AtomicReference<String> traceIdInFetch = new AtomicReference<>();
            RecordingScopedTasks scopedTasks = new RecordingScopedTasks();

            when(deepseek.fetchModels()).thenAnswer(invocation -> {
                traceIdInFetch.set(MDC.get("traceId"));
                return List.of(model);
            });
            when(deepseek.createClientWithModel("deepseek-chat", null))
                    .thenReturn(new ModelProvider.ClientAndModel(chatClient, chatModel));

            MDC.put("traceId", "trace-001");
            ModelRegistryRefresher refresher = new ModelRegistryRefresher(
                    new ProviderRegistry(List.of(deepseek)),
                    new ChatClientRegistry(),
                    scopedTasks
            );

            boolean refreshed = refresher.refresh();

            assertThat(refreshed).isTrue();
            assertThat(traceIdInFetch).hasValue("trace-001");
            assertThat(scopedTasks.openOptionsCount()).isEqualTo(1);
            assertThat(scopedTasks.lastOptions().name()).isEqualTo("model-registry-refresh");
            assertThat(MDC.get("traceId")).isEqualTo("trace-001");
        }

        @Test
        @DisplayName("Provider fatal Error 不应被容错结果吞掉")
        void refresh_providerFatalErrorPropagates() {
            ModelProvider deepseek = provider("deepseek");
            when(deepseek.fetchModels()).thenThrow(new OutOfMemoryError("fatal"));
            ModelRegistryRefresher refresher = new ModelRegistryRefresher(
                    new ProviderRegistry(List.of(deepseek)),
                    new ChatClientRegistry(),
                    new DefaultScopedTasks()
            );

            assertThatThrownBy(refresher::refresh)
                    .isInstanceOf(OutOfMemoryError.class)
                    .hasMessage("fatal");
        }
    }

    private static ModelProvider provider(String providerId) {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.getProviderId()).thenReturn(providerId);
        when(provider.getDisplayName()).thenReturn(providerId);
        when(provider.isAvailable()).thenReturn(true);
        return provider;
    }

    private static ModelInfo model(String id) {
        return new ModelInfo(id, "model", 0L, "test");
    }

    private static final class RecordingScopedTasks implements ScopedTasks {

        private final ScopedTasks delegate = new DefaultScopedTasks();
        private final AtomicInteger openOptionsCount = new AtomicInteger();
        private ScopeOptions lastOptions;

        @Override
        public TaskScope open(String name) {
            return delegate.open(name);
        }

        @Override
        public TaskScope open(String name, ScopePolicy policy) {
            return delegate.open(name, policy);
        }

        @Override
        public TaskScope open(String name, ScopeOptions options) {
            openOptionsCount.incrementAndGet();
            lastOptions = options;
            return delegate.open(name, options);
        }

        int openOptionsCount() {
            return openOptionsCount.get();
        }

        ScopeOptions lastOptions() {
            return lastOptions;
        }
    }
}
