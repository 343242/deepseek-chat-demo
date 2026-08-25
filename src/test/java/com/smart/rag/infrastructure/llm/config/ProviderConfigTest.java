package com.smart.rag.infrastructure.llm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProviderConfig.isAvailable 免 key 豁免联动测试（design llm-client-stateless §1 决策 4）：
 * 豁免判定经 {@code HostSafetyValidator.isLoopbackEndpoint} host 字面回环判定，
 * 取代 contains 子串匹配（{@code x.localhost.evil.com}、path 含 localhost 反例不再豁免 → 候选跳过）。
 */
@DisplayName("ProviderConfig.isAvailable 免 key 豁免（字面回环判定联动）")
class ProviderConfigTest {

    @Test
    @DisplayName("url + apiKey 齐备 → 可用")
    void availableWithApiKey() {
        assertThat(ProviderConfig.of("https://api.deepseek.com", "sk-x").isAvailable()).isTrue();
    }

    @Test
    @DisplayName("url 缺失/blank → 不可用")
    void unavailableWithoutUrl() {
        assertThat(ProviderConfig.of(null, "sk-x").isAvailable()).isFalse();
        assertThat(ProviderConfig.of(" ", "sk-x").isAvailable()).isFalse();
    }

    @Test
    @DisplayName("免 key + 回环字面（localhost/127.x/[::1]）→ 豁免放行")
    void keylessLoopbackExempted() {
        assertThat(ProviderConfig.of("http://localhost:11434/v1", null).isAvailable()).isTrue();
        assertThat(ProviderConfig.of("http://LOCALHOST:11434/v1", "").isAvailable()).isTrue();
        assertThat(ProviderConfig.of("http://127.0.0.1:11434/v1", " ").isAvailable()).isTrue();
        assertThat(ProviderConfig.of("http://127.9.8.7:11434/v1", null).isAvailable()).isTrue();
        assertThat(ProviderConfig.of("http://[::1]:11434/v1", null).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("免 key + 非回环 URL → 不可用（含子串反例：x.localhost.evil.com / path 含 localhost）")
    void keylessNonLoopbackRejected() {
        assertThat(ProviderConfig.of("https://api.deepseek.com", null).isAvailable()).isFalse();
        assertThat(ProviderConfig.of("https://x.localhost.evil.com", null).isAvailable()).isFalse();
        assertThat(ProviderConfig.of("https://api.example.com/localhost", null).isAvailable()).isFalse();
        assertThat(ProviderConfig.of("https://my-gateway.internal/v1", null).isAvailable()).isFalse();
    }
}
