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

@DisplayName("MiniMaxModelProvider 单元测试")
class MiniMaxModelProviderTest {

    private MiniMaxModelProvider providerWithKey;
    private MiniMaxModelProvider providerWithoutKey;

    @BeforeEach
    void setUp() {
        providerWithKey = new MiniMaxModelProvider("test-api-key");
        providerWithoutKey = new MiniMaxModelProvider("");
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
        @DisplayName("返回硬编码模型列表")
        void returnsModels() { assertEquals(3, providerWithKey.fetchModels().size()); }

        @Test
        @DisplayName("包含 MiniMax-Text-01")
        void containsText01() {
            assertTrue(providerWithKey.fetchModels().stream()
                    .anyMatch(m -> "MiniMax-Text-01".equals(m.id())));
        }
    }

    @Nested
    @DisplayName("createClient")
    class CreateClientTests {

        @Test
        @DisplayName("创建 ChatClient 不为 null")
        void createClient_notNull() {
            ChatClient client = providerWithKey.createClient("MiniMax-Text-01", null);
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
            ModelParams params = new ModelParams("MiniMax-Text-01");
            params.setTemperature(0.5);
            ChatOptions options = providerWithKey.buildOptions(params);
            assertNotNull(options);
            assertEquals(0.5, options.getTemperature());
        }
    }
}
