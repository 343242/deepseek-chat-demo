package com.demo.chat.chat.provider;

import com.demo.chat.chat.entity.ModelParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ZhipuModelProvider 单元测试")
class ZhipuModelProviderTest {

    private ZhipuModelProvider providerWithKey;
    private ZhipuModelProvider providerWithoutKey;

    @BeforeEach
    void setUp() {
        providerWithKey = new ZhipuModelProvider("test-api-key", "https://open.bigmodel.cn/api/paas");
        providerWithoutKey = new ZhipuModelProvider("", "https://open.bigmodel.cn/api/paas");
    }

    @Nested
    @DisplayName("基本属性")
    class BasicTests {

        @Test
        @DisplayName("getProviderId → zhipu")
        void providerId() {
            assertEquals("zhipu", providerWithKey.getProviderId());
        }

        @Test
        @DisplayName("getDisplayName → 智谱 AI")
        void displayName() {
            assertEquals("智谱 AI", providerWithKey.getDisplayName());
        }
    }

    @Nested
    @DisplayName("isAvailable")
    class AvailabilityTests {

        @Test
        @DisplayName("apiKey 非空 → true")
        void available() {
            assertTrue(providerWithKey.isAvailable());
        }

        @Test
        @DisplayName("apiKey 为空 → false")
        void notAvailable() {
            assertFalse(providerWithoutKey.isAvailable());
        }
    }

    @Nested
    @DisplayName("fetchModels")
    class FetchModelsTests {

        @Test
        @DisplayName("返回硬编码模型列表")
        void returnsHardcodedModels() {
            List<?> models = providerWithKey.fetchModels();
            assertEquals(4, models.size());
        }

        @Test
        @DisplayName("包含 glm-4-air")
        void containsGlm4Air() {
            assertTrue(providerWithKey.fetchModels().stream()
                    .anyMatch(m -> "glm-4-air".equals(m.id())));
        }
    }

    @Nested
    @DisplayName("createClient")
    class CreateClientTests {

        @Test
        @DisplayName("创建 ChatClient 不为 null")
        void createClient_notNull() {
            ChatClient client = providerWithKey.createClient("glm-4-air", 0.7);
            assertNotNull(client);
        }
    }

    @Nested
    @DisplayName("buildOptions")
    class BuildOptionsTests {

        @Test
        @DisplayName("params 为 null → 返回 null")
        void nullParams() {
            assertNull(providerWithKey.buildOptions(null));
        }

        @Test
        @DisplayName("映射 temperature + maxTokens")
        void mapsParams() {
            ModelParams params = new ModelParams("glm-4-air");
            params.setTemperature(0.5);
            params.setMaxTokens(500);

            ChatOptions options = providerWithKey.buildOptions(params);
            assertNotNull(options);
            assertEquals(0.5, options.getTemperature());
            assertEquals(500, options.getMaxTokens());
        }
    }
}
