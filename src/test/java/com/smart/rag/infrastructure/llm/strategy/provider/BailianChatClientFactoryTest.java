package com.smart.rag.infrastructure.llm.strategy.provider;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.ChatCandidate;
import com.smart.rag.infrastructure.llm.client.bailian.BailianChatClient;
import com.smart.rag.infrastructure.llm.client.generic.GenericChatClient;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * BailianChatClientFactory 单元测试（设计 §4.3 engagement 守卫）
 * <p>
 * DB/BYOK 行 {@code provider_code='bailian'} + 自定义 baseUrl 不得被 SDK 客户端劫持
 * （DashScope 原生协议打私有网关会静默打挂）——域名白名单 + 显式 sdk-client 开关。
 */
@DisplayName("BailianChatClientFactory engagement 守卫")
class BailianChatClientFactoryTest {

    private final BailianChatClientFactory factory = new BailianChatClientFactory(mock(HttpClientFactory.class));

    private static ChatCandidate candidate(Map<String, Object> params) {
        ChatCandidate c = new ChatCandidate();
        c.setId("qwen3-max");
        c.setProvider("bailian");
        c.setModel("qwen3-max");
        c.setPriority(1);
        c.setParams(params);
        return c;
    }

    @Nested
    @DisplayName("sdkEngaged — 域名白名单 + 显式开关")
    class SdkEngaged {

        @Test
        @DisplayName("DashScope 共享域名与业务空间专属域名命中")
        void officialDomains() {
            assertThat(BailianChatClientFactory.sdkEngaged(
                "https://dashscope.aliyuncs.com/compatible-mode/v1", Map.of())).isTrue();
            assertThat(BailianChatClientFactory.sdkEngaged(
                "https://llm-xxx.cn-beijing.maas.aliyuncs.com", Map.of())).isTrue();
        }

        @Test
        @DisplayName("自定义 baseUrl（私有网关/代理）不命中 → 回落 GenericChatClient")
        void customBaseUrlRejected() {
            assertThat(BailianChatClientFactory.sdkEngaged(
                "https://my-gateway.internal/v1", Map.of())).isFalse();
            assertThat(BailianChatClientFactory.sdkEngaged(
                "https://evil-maas.aliyuncs.com.example.com", Map.of())).isFalse();
        }

        @Test
        @DisplayName("params.sdk-client: true 显式强制启用")
        void explicitOverride() {
            assertThat(BailianChatClientFactory.sdkEngaged(
                "https://my-gateway.internal/v1", Map.of("sdk-client", true))).isTrue();
        }
    }

    @Nested
    @DisplayName("sdkBaseUrl — provider.url 归一化为 SDK 前缀")
    class SdkBaseUrl {

        @Test
        @DisplayName("无路径域名追加 /api/v1")
        void bareDomain() {
            assertThat(BailianChatClientFactory.sdkBaseUrl("https://llm-x.cn-beijing.maas.aliyuncs.com"))
                .isEqualTo("https://llm-x.cn-beijing.maas.aliyuncs.com/api/v1");
        }

        @Test
        @DisplayName("stable 兼容层路径（/compatible-mode/v1）剥离后重组")
        void stripsCompatibleModePath() {
            assertThat(BailianChatClientFactory.sdkBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1");
        }

        @Test
        @DisplayName("非法 URL fail-fast")
        void invalidUrl() {
            assertThatThrownBy(() -> BailianChatClientFactory.sdkBaseUrl("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("create：守卫不命中回落 GenericChatClient，命中产出 BailianChatClient")
    void createGuardRouting() {
        CapabilityClient fallback = factory.create(
            "https://my-gateway.internal/v1", "/chat/completions", "k", candidate(Map.of()));
        assertThat(fallback).isInstanceOf(GenericChatClient.class);

        CapabilityClient sdk = factory.create(
            "https://llm-x.cn-beijing.maas.aliyuncs.com", null, "k", candidate(Map.of()));
        assertThat(sdk).isInstanceOf(BailianChatClient.class);
    }
}
