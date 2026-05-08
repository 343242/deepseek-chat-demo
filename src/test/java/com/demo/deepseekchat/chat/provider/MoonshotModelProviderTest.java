package com.demo.deepseekchat.chat.provider;

import com.demo.deepseekchat.chat.entity.ModelParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MoonshotModelProvider 单元测试")
class MoonshotModelProviderTest {

    private MoonshotModelProvider providerWithKey;
    private MoonshotModelProvider providerWithoutKey;

    @BeforeEach
    void setUp() {
        providerWithKey = new MoonshotModelProvider("test-key", "https://api.moonshot.cn/v1");
        providerWithoutKey = new MoonshotModelProvider("", "https://api.moonshot.cn/v1");
    }

    @Nested
    @DisplayName("基本属性")
    class BasicTests {

        @Test
        @DisplayName("getProviderId → moonshot")
        void providerId() { assertEquals("moonshot", providerWithKey.getProviderId()); }

        @Test
        @DisplayName("getDisplayName → Moonshot")
        void displayName() { assertEquals("Moonshot", providerWithKey.getDisplayName()); }
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
        @DisplayName("返回 3 个模型")
        void returnsModels() { assertEquals(3, providerWithKey.fetchModels().size()); }

        @Test
        @DisplayName("包含 moonshot-v1-8k 和 moonshot-v1-128k")
        void containsModels() {
            List<String> ids = providerWithKey.fetchModels().stream()
                    .map(m -> m.id()).toList();
            assertTrue(ids.contains("moonshot-v1-8k"));
            assertTrue(ids.contains("moonshot-v1-128k"));
        }
    }

    @Nested
    @DisplayName("createClient")
    class CreateClientTests {

        @Test
        @DisplayName("创建 ChatClient 不为 null")
        void createClient_notNull() {
            ChatClient client = providerWithKey.createClient("moonshot-v1-8k", 0.7);
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
        @DisplayName("映射全部参数")
        void mapsAllParams() {
            ModelParams params = new ModelParams("moonshot-v1-8k");
            params.setTemperature(0.5);
            params.setMaxTokens(2000);
            params.setTopP(0.8);
            params.setFrequencyPenalty(0.1);
            params.setPresencePenalty(0.2);

            ChatOptions options = providerWithKey.buildOptions(params);
            assertNotNull(options);
            assertEquals(0.5, options.getTemperature());
            assertEquals(2000, options.getMaxTokens());
            assertEquals(0.1, options.getFrequencyPenalty().doubleValue(), 0.001);
            assertEquals(0.2, options.getPresencePenalty().doubleValue(), 0.001);
        }
    }
}
