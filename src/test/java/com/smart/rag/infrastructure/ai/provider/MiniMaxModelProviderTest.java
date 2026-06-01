package com.smart.rag.infrastructure.ai.provider;

import com.smart.rag.infrastructure.ai.model.ModelInfo;
import com.smart.rag.infrastructure.ai.model.ModelOptionSettings;
import com.smart.rag.config.MiniMaxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MiniMaxModelProvider 单元测试")
class MiniMaxModelProviderTest {

    @Mock private RestClient restClient;

    private MiniMaxModelProvider providerWithKey;
    private MiniMaxModelProvider providerWithoutKey;

    @BeforeEach
    void setUp() {
        MiniMaxProperties propsWithKey = new MiniMaxProperties(
                "https://api.minimaxi.com/v1", "test-api-key",
                new MiniMaxProperties.ChatOptions("MiniMax-M2.1", 0.7, null, null));
        MiniMaxProperties propsWithoutKey = new MiniMaxProperties(
                "https://api.minimaxi.com/v1", "",
                new MiniMaxProperties.ChatOptions("MiniMax-M2.1", 0.7, null, null));

        providerWithKey = new MiniMaxModelProvider(propsWithKey, restClient);
        providerWithoutKey = new MiniMaxModelProvider(propsWithoutKey, restClient);
    }

    @Nested
    @DisplayName("基本属性")
    class BasicTests {

        @Test
        @DisplayName("getProviderId → minimax")
        void providerId() { assertEquals("minimax", providerWithKey.getProviderId()); }

        @Test
        @DisplayName("getDisplayName → MiniMax")
        void displayName() { assertEquals("MiniMax", providerWithKey.getDisplayName()); }
    }

    @Nested
    @DisplayName("isAvailable")
    class AvailabilityTests {

        @Test
        @DisplayName("apiKey 非空 → true")
        void available() { assertTrue(providerWithKey.isAvailable()); }

        @Test
        @DisplayName("apiKey 为空 → false")
        void notAvailable() { assertFalse(providerWithoutKey.isAvailable()); }
    }

    @Nested
    @DisplayName("fetchModels")
    class FetchModelsTests {

        @Test
        @DisplayName("RestClient 异常 → 回退到 fallback 列表")
        void fallsBackOnApiError() {
            when(restClient.get()).thenThrow(new RuntimeException("connection failed"));

            List<ModelInfo> models = providerWithKey.fetchModels();
            assertNotNull(models);
            assertFalse(models.isEmpty());
        }

        @Test
        @DisplayName("fallback 列表包含 MiniMax-M2.5")
        void fallbackContainsM25() {
            when(restClient.get()).thenThrow(new RuntimeException("connection failed"));

            List<ModelInfo> models = providerWithKey.fetchModels();
            assertTrue(models.stream()
                    .anyMatch(m -> "MiniMax-M2.5".equals(m.id())));
        }
    }

    @Nested
    @DisplayName("createClient")
    class CreateClientTests {

        @Test
        @DisplayName("创建 ChatClient 不为 null")
        void createClient_notNull() {
            ChatClient client = providerWithKey.createClient("MiniMax-M2.1", null);
            assertNotNull(client);
        }
    }

    @Nested
    @DisplayName("buildOptions")
    class BuildOptionsTests {

        @Test
        @DisplayName("params 为 null → 返回 null")
        void nullParams() { assertNull(providerWithKey.buildOptions(null)); }

        @Test
        @DisplayName("映射 temperature")
        void mapsTemperature() {
            ModelOptionSettings params = new ModelOptionSettings(0.5, null, null, null, null);
            ChatOptions options = providerWithKey.buildOptions(params);
            assertNotNull(options);
            assertEquals(0.5, options.getTemperature());
        }
    }
}
