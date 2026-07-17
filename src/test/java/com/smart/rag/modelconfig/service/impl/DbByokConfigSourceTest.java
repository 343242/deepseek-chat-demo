package com.smart.rag.modelconfig.service.impl;

import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.registry.LlmClientFactory;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import com.smart.rag.modelconfig.service.LlmModelConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DbByokConfigSource 单元测试（design §5.4 / R1 / AC29）— 三态语义、命名空间 candidateId、endpoints 解析。
 */
@ExtendWith(MockitoExtension.class)
class DbByokConfigSourceTest {

    @Mock private LlmModelConfigService configService;
    @Mock private LlmMetrics metrics;

    @InjectMocks private DbByokConfigSource source;

    private LlmModelConfig row(long id, String model, Integer status) {
        LlmModelConfig r = new LlmModelConfig();
        r.setId(id);
        r.setUserId(7L);
        r.setCapabilityType("CHAT");
        r.setProviderCode("deepseek");
        r.setBaseUrl("https://api.deepseek.com");
        r.setModelName(model);
        r.setStatus(status);
        r.setPriority(100);
        r.setSupportsStreaming(true);
        r.setApiKeyCipher(new byte[]{1});
        r.setApiKeyIv(new byte[]{2});
        return r;
    }

    @Test
    void no_rows_returns_empty_no_metric() {
        when(configService.selectAll(7L, LlmCapability.CHAT)).thenReturn(List.of());

        assertThat(source.userChain(7L, LlmCapability.CHAT)).isEmpty();

        verifyNoInteractions(metrics); // 无行 = 正常 fallback，无 counter
    }

    @Test
    void all_disabled_returns_empty_with_counter() {
        when(configService.selectAll(7L, LlmCapability.CHAT)).thenReturn(List.of(row(1L, "m1", 0)));

        assertThat(source.userChain(7L, LlmCapability.CHAT)).isEmpty();

        verify(metrics).recordByokFallback("all_disabled");
    }

    @Test
    void enabled_returns_resolved_chain_with_namespaced_id() {
        LlmModelConfig r = row(1L, "deepseek-chat", 1);
        when(configService.selectAll(7L, LlmCapability.CHAT)).thenReturn(List.of(r));
        when(configService.decryptKey(r)).thenReturn("sk-xxx");

        List<LlmClientFactory.ResolvedCandidate> result = source.userChain(7L, LlmCapability.CHAT);

        assertThat(result).hasSize(1);
        LlmClientFactory.ResolvedCandidate rc = result.get(0);
        assertThat(rc.candidate().id()).isEqualTo("u:7:deepseek-chat"); // 命名空间 candidateId
        assertThat(rc.candidate().capability()).isEqualTo(LlmCapability.CHAT);
        assertThat(rc.apiKey()).isEqualTo("sk-xxx");
        assertThat(rc.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(rc.providerCode()).isEqualTo("deepseek");
        assertThat(rc.candidate().supportsStreaming()).isTrue();
    }

    @Test
    void enabled_and_disabled_returns_only_enabled() {
        LlmModelConfig on = row(1L, "m1", 1);
        LlmModelConfig off = row(2L, "m2", 0);
        when(configService.selectAll(7L, LlmCapability.CHAT)).thenReturn(List.of(on, off));
        when(configService.decryptKey(on)).thenReturn("k1");

        List<LlmClientFactory.ResolvedCandidate> result = source.userChain(7L, LlmCapability.CHAT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).candidate().model()).isEqualTo("m1");
    }

    @Test
    void endpoints_parsed_from_json() {
        LlmModelConfig r = row(1L, "m1", 1);
        r.setEndpoints("{\"chat\":\"/v1/chat\"}");
        when(configService.selectAll(7L, LlmCapability.CHAT)).thenReturn(List.of(r));
        when(configService.decryptKey(r)).thenReturn("k");

        List<LlmClientFactory.ResolvedCandidate> result = source.userChain(7L, LlmCapability.CHAT);

        assertThat(result.get(0).endpoints()).containsEntry("chat", "/v1/chat");
    }

    @Test
    void malformed_endpoints_falls_back_to_empty() {
        LlmModelConfig r = row(1L, "m1", 1);
        r.setEndpoints("not-json");
        when(configService.selectAll(7L, LlmCapability.CHAT)).thenReturn(List.of(r));
        when(configService.decryptKey(r)).thenReturn("k");

        List<LlmClientFactory.ResolvedCandidate> result = source.userChain(7L, LlmCapability.CHAT);

        assertThat(result.get(0).endpoints()).isEmpty(); // 防御性兜底
    }
}
