package com.smart.rag.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HostSafetyValidator.isLoopbackEndpoint 单元测试 — host 字面回环判定
 * （design llm-client-stateless §1 决策 4 / §8 AC1）。
 * <p>
 * 纯字面解析、不发 DNS（无网络依赖）；子串反例（x.localhost.evil.com、path 含 localhost）
 * 必须拒绝——取代旧的 contains 子串匹配（fail-safe 收紧）。
 */
@DisplayName("HostSafetyValidator.isLoopbackEndpoint host 字面回环判定")
class HostSafetyValidatorLoopbackTest {

    @Nested
    @DisplayName("放行：回环字面")
    class Allowed {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://localhost:11434/v1",
            "http://LOCALHOST:11434/v1",
            "http://LocalHost/v1",
            "https://127.0.0.1:11434/v1",
            "http://127.0.0.1/v1",
            "http://127.42.0.8:8080",     // 127.0.0.0/8 任意 IPv4 字面
            "http://[::1]:11434/v1",      // URI.getHost() 返回带方括号形式
        })
        void loopbackLiterals(String url) {
            assertThat(HostSafetyValidator.isLoopbackEndpoint(url)).isTrue();
        }
    }

    @Nested
    @DisplayName("拒绝：非回环 / 子串反例 / 形态非法（fail-safe）")
    class Rejected {

        @Test
        @DisplayName("子串反例：x.localhost.evil.com 不是 localhost host")
        void subdomainOfLocalhost() {
            assertThat(HostSafetyValidator.isLoopbackEndpoint("https://x.localhost.evil.com")).isFalse();
        }

        @Test
        @DisplayName("子串反例：path 含 localhost 但 host 非回环")
        void localhostInPathOnly() {
            assertThat(HostSafetyValidator.isLoopbackEndpoint("https://api.example.com/localhost/proxy")).isFalse();
        }

        @Test
        @DisplayName("其他回环段字面（如 127.256.0.1 非法段 / 126.x）不豁免")
        void nonLoopbackIpv4Literal() {
            assertThat(HostSafetyValidator.isLoopbackEndpoint("http://126.0.0.1/v1")).isFalse();
            assertThat(HostSafetyValidator.isLoopbackEndpoint("http://128.0.0.1/v1")).isFalse();
        }

        @Test
        @DisplayName("公网域名 / 内网网关域名不走豁免（字面判定不解析 DNS）")
        void nonLoopbackHosts() {
            assertThat(HostSafetyValidator.isLoopbackEndpoint("https://api.deepseek.com")).isFalse();
            assertThat(HostSafetyValidator.isLoopbackEndpoint("https://my-gateway.internal/v1")).isFalse();
            assertThat(HostSafetyValidator.isLoopbackEndpoint("https://ollama.lan")).isFalse();
        }

        @Test
        @DisplayName("IPv6 非回环字面（[::2]）拒绝")
        void nonLoopbackIpv6() {
            assertThat(HostSafetyValidator.isLoopbackEndpoint("http://[::2]:11434/v1")).isFalse();
        }

        @Test
        @DisplayName("解析失败 / host 缺失 / null / blank → false（fail-safe）")
        void malformedUrls() {
            assertThat(HostSafetyValidator.isLoopbackEndpoint(null)).isFalse();
            assertThat(HostSafetyValidator.isLoopbackEndpoint("")).isFalse();
            assertThat(HostSafetyValidator.isLoopbackEndpoint("   ")).isFalse();
            assertThat(HostSafetyValidator.isLoopbackEndpoint("not a url")).isFalse();
            assertThat(HostSafetyValidator.isLoopbackEndpoint("http:///no-host")).isFalse();
        }
    }
}
