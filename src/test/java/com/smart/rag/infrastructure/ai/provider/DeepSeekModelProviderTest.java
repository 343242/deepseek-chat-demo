package com.smart.rag.infrastructure.ai.provider;

import com.smart.rag.infrastructure.ai.model.ModelInfo;
import com.smart.rag.infrastructure.ai.model.ModelOptionSettings;
import com.smart.rag.config.DeepSeekProperties;
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
@DisplayName("DeepSeekModelProvider 单元测试")
class DeepSeekModelProviderTest {

    @Mock private DeepSeekProperties properties;
    @Mock private DeepSeekProperties.ChatOptions chatOptions;
    @Mock private RestClient restClient;

    private DeepSeekModelProvider provider;

    @BeforeEach
    void setUp() {
        lenient().when(properties.baseUrl()).thenReturn("https://api.deepseek.com");
        lenient().when(properties.apiKey()).thenReturn("sk-test");
        provider = new DeepSeekModelProvider(properties, restClient);
    }

    @Nested
    @DisplayName("基本属性")
    class BasicTests {

        @Test
        @DisplayName("getProviderId → deepseek")
        void providerId() {
            assertEquals("deepseek", provider.getProviderId());
        }

        @Test
        @DisplayName("getDisplayName → DeepSeek")
        void displayName() {
            assertEquals("DeepSeek", provider.getDisplayName());
        }
    }

    @Nested
    @DisplayName("isAvailable")
    class AvailabilityTests {

        @Test
        @DisplayName("apiKey 非空 → true")
        void available_whenApiKeySet() {
            when(properties.apiKey()).thenReturn("sk-test");
            assertTrue(provider.isAvailable());
        }

        @Test
        @DisplayName("apiKey 为空 → false")
        void notAvailable_whenApiKeyBlank() {
            when(properties.apiKey()).thenReturn("");
            assertFalse(provider.isAvailable());
        }

        @Test
        @DisplayName("apiKey 为 null → false")
        void notAvailable_whenApiKeyNull() {
            when(properties.apiKey()).thenReturn(null);
            assertFalse(provider.isAvailable());
        }
    }

    @Nested
    @DisplayName("buildOptions")
    class BuildOptionsTests {

        @Test
        @DisplayName("params 为 null → 返回 null")
        void nullParams_returnsNull() {
            assertNull(provider.buildOptions(null));
        }

        @Test
        @DisplayName("映射 temperature + maxTokens + topP")
        void mapsCoreParams() {
            ModelOptionSettings params = new ModelOptionSettings(0.5, 1000, 0.9, null, null);

            ChatOptions options = provider.buildOptions(params);
            assertNotNull(options);
            assertEquals(0.5, options.getTemperature());
            assertEquals(1000, options.getMaxTokens());
            assertEquals(0.9, options.getTopP().doubleValue(), 0.001);
        }

        @Test
        @DisplayName("部分字段为 null → 不报错")
        void partialParams_ok() {
            ModelOptionSettings params = new ModelOptionSettings(0.8, null, null, null, null);

            ChatOptions options = provider.buildOptions(params);
            assertNotNull(options);
            assertEquals(0.8, options.getTemperature());
            assertNull(options.getMaxTokens());
        }
    }

    @Nested
    @DisplayName("createClient")
    class CreateClientTests {

        @Test
        @DisplayName("创建 ChatClient 不为 null")
        void createClient_notNull() {
            when(properties.chat()).thenReturn(chatOptions);
            when(chatOptions.temperature()).thenReturn(0.7);
            when(chatOptions.topP()).thenReturn(null);
            when(chatOptions.maxTokens()).thenReturn(null);

            ChatClient client = provider.createClient("deepseek-chat", null);
            assertNotNull(client);
        }
    }

    @Nested
    @DisplayName("fetchModels")
    class FetchModelsTests {

        @Test
        @DisplayName("RestClient 异常 → 回退到 fallback 列表")
        void fetchModels_error_returnsFallback() {
            when(restClient.get()).thenThrow(new RuntimeException("connection failed"));

            List<ModelInfo> result = provider.fetchModels();
            assertNotNull(result);
            assertFalse(result.isEmpty());
            // fallback 列表应包含 deepseek-v4-flash
            assertTrue(result.stream().anyMatch(m -> "deepseek-v4-flash".equals(m.id())));
        }
    }
}
