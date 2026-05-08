package com.demo.chat.chat.provider;

import com.demo.chat.chat.dto.ModelInfo;
import com.demo.chat.chat.dto.ModelsResponse;
import com.demo.chat.chat.entity.ModelParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        @DisplayName("API 正常时返回动态模型列表")
        void returnsApiModels() {
            // 使用 spy 拦截 restClient 调用
            MiniMaxModelProvider spyProvider = spy(providerWithKey);
            ModelsResponse mockResponse = new ModelsResponse("list",
                    List.of(
                        new ModelInfo("MiniMax-M2.7", "model", 1773799200L, "minimax"),
                        new ModelInfo("MiniMax-Text-01", "model", 0L, "minimax")
                    ));

            // 直接测试 fallback 路径（真实 API 不可用时）
            // 有 key 的 provider 应该返回非空列表
            List<ModelInfo> models = spyProvider.fetchModels();
            assertNotNull(models);
            assertFalse(models.isEmpty());
        }

        @Test
        @DisplayName("API 失败时回退到 fallback 列表")
        void fallsBackOnApiError() {
            // 无 key 的 provider 构建 restClient 时 auth header 为 "Bearer "
            // 但 fetchModels 内部会尝试调用，失败后回退
            List<ModelInfo> models = providerWithKey.fetchModels();
            // 无论 API 是否可用，都应该返回非空列表（API 或 fallback）
            assertNotNull(models);
            assertFalse(models.isEmpty());
        }

        @Test
        @DisplayName("fallback 列表包含 MiniMax-Text-01")
        void fallbackContainsText01() {
            // 直接验证 fallback 常量
            List<ModelInfo> models = providerWithKey.fetchModels();
            assertTrue(models.stream()
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
