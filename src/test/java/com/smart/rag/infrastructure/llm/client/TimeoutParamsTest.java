package com.smart.rag.infrastructure.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TimeoutParams 解析（WS2：candidate params 超时配置化）")
class TimeoutParamsTest {

    @Test
    @DisplayName("未配置 → 全部默认值")
    void missingParamsUseDefaults() {
        TimeoutParams t = TimeoutParams.chatDefaults().mergeWithParams(null);

        assertThat(t.connectTimeoutMs()).isEqualTo(10_000);
        assertThat(t.readTimeoutMs()).isEqualTo(120_000);
        assertThat(t.callTimeoutMs()).isEqualTo(150_000);
        assertThat(t.streamReadTimeoutMs()).isEqualTo(120_000);
        assertThat(t.streamCallTimeoutMs()).isEqualTo(300_000);
    }

    @Test
    @DisplayName("合法键（Number 与可解析 String）→ 覆盖默认")
    void validKeysOverride() {
        Map<String, Object> params = new HashMap<>();
        params.put("connect-timeout-ms", 5000);
        params.put("read-timeout-ms", "60000");
        params.put("call-timeout-ms", 90000);
        params.put("stream-read-timeout-ms", 45000L);
        params.put("stream-call-timeout-ms", "600000");

        TimeoutParams t = TimeoutParams.chatDefaults().mergeWithParams(params);

        assertThat(t.connectTimeoutMs()).isEqualTo(5_000);
        assertThat(t.readTimeoutMs()).isEqualTo(60_000);
        assertThat(t.callTimeoutMs()).isEqualTo(90_000);
        assertThat(t.streamReadTimeoutMs()).isEqualTo(45_000);
        assertThat(t.streamCallTimeoutMs()).isEqualTo(600_000);
    }

    @Test
    @DisplayName("非法值（不可解析字符串 / 负数 / 0 连接超时除外的非数字对象）→ 对应键回落默认")
    void invalidValuesFallBack() {
        Map<String, Object> params = new HashMap<>();
        params.put("read-timeout-ms", "abc");
        params.put("call-timeout-ms", -5);
        params.put("stream-read-timeout-ms", java.util.List.of(1));

        TimeoutParams t = TimeoutParams.chatDefaults().mergeWithParams(params);

        assertThat(t.readTimeoutMs()).isEqualTo(120_000);
        assertThat(t.callTimeoutMs()).isEqualTo(150_000);
        assertThat(t.streamReadTimeoutMs()).isEqualTo(120_000);
        // 未涉及的键不受影响
        assertThat(t.connectTimeoutMs()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("0 = 显式禁用（call-timeout 0 → OkHttp 不限总时长）")
    void zeroDisablesCallTimeout() {
        Map<String, Object> params = Map.of("call-timeout-ms", 0, "stream-call-timeout-ms", 0);
        TimeoutParams t = TimeoutParams.chatDefaults().mergeWithParams(params);

        assertThat(t.callTimeoutMs()).isZero();
        assertThat(t.streamCallTimeoutMs()).isZero();
    }

    @Test
    @DisplayName("部分覆盖：仅配置的键生效，其余保持默认")
    void partialOverride() {
        TimeoutParams t = TimeoutParams.chatDefaults().mergeWithParams(Map.of("read-timeout-ms", 180000));

        assertThat(t.readTimeoutMs()).isEqualTo(180_000);
        assertThat(t.callTimeoutMs()).isEqualTo(150_000);
        assertThat(t.streamReadTimeoutMs()).isEqualTo(120_000);
    }

    @Test
    @DisplayName("sharedOkHttpClient 缓存键含 call：不同 callTimeout 即不同实例，同签名同实例")
    void okHttpCacheKeyIncludesCallTimeout() {
        HttpClientFactory factory = new HttpClientFactory();
        try {
            var a = factory.sharedOkHttpClient(java.time.Duration.ofSeconds(10),
                java.time.Duration.ofSeconds(120), java.time.Duration.ofMillis(150_000));
            var a2 = factory.sharedOkHttpClient(java.time.Duration.ofSeconds(10),
                java.time.Duration.ofSeconds(120), java.time.Duration.ofMillis(150_000));
            var b = factory.sharedOkHttpClient(java.time.Duration.ofSeconds(10),
                java.time.Duration.ofSeconds(120), java.time.Duration.ofMillis(300_000));
            // 旧两参签名 = callTimeout 0，与任何有限 call 签名不同实例
            var legacy = factory.sharedOkHttpClient(java.time.Duration.ofSeconds(10),
                java.time.Duration.ofSeconds(120));

            assertThat(a).isSameAs(a2);
            assertThat(a).isNotSameAs(b);
            assertThat(a).isNotSameAs(legacy);
        } finally {
            factory.closeAll();
        }
    }
}
