package com.smart.rag.modelconfig.service;

import com.smart.rag.common.snowflake.SnowflakeIdGenerator;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.crypto.ApiKeyCipher;
import com.smart.rag.infrastructure.llm.config.BaseUrlValidator;
import com.smart.rag.modelconfig.dto.UpsertLlmConfigRequest;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import com.smart.rag.modelconfig.mapper.LlmModelConfigMapper;
import com.smart.rag.modelconfig.service.impl.LlmModelConfigServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LlmModelConfigServiceImpl 单元测试（design §4 / §6 / §12，AC3 / AC4 / AC5 / AC21）。
 * <p>
 * Mapper/ApiKeyCipher/BaseUrlValidator/SnowflakeIdGenerator 全 mock；验证同步落库流程、
 * owner-only delete、P1-8 仅 CHAT、is_default 清旧快速路径、脱敏与解密失败兜底。
 */
@ExtendWith(MockitoExtension.class)
class LlmModelConfigServiceImplTest {

    @Mock private LlmModelConfigMapper mapper;
    @Mock private ApiKeyCipher apiKeyCipher;
    @Mock private BaseUrlValidator baseUrlValidator;
    @Mock private SnowflakeIdGenerator idGenerator;

    @InjectMocks private LlmModelConfigServiceImpl service;

    private UpsertLlmConfigRequest chatRequest(String baseUrl) {
        UpsertLlmConfigRequest r = new UpsertLlmConfigRequest();
        r.setCapabilityType("CHAT");
        r.setProviderCode("deepseek");
        r.setBaseUrl(baseUrl);
        r.setApiKey("sk-abcd1234efgh5678");
        r.setModelName("deepseek-chat");
        return r;
    }

    private void stubEncrypt() {
        when(idGenerator.nextId()).thenReturn(100L);
        when(apiKeyCipher.encrypt(anyString()))
            .thenReturn(new ApiKeyCipher.CipherText(new byte[]{1, 2, 3}, new byte[]{4, 5, 6}));
    }

    // ===== upsert =====

    @Test
    void upsert_chat_valid_encrypts_and_persists() {
        stubEncrypt();

        service.upsert(7L, chatRequest("https://api.deepseek.com"));

        verify(baseUrlValidator).validate("https://api.deepseek.com");
        verify(apiKeyCipher).encrypt("sk-abcd1234efgh5678");
        ArgumentCaptor<LlmModelConfig> captor = ArgumentCaptor.forClass(LlmModelConfig.class);
        verify(mapper).upsert(captor.capture());
        LlmModelConfig saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(100L);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getApiKeyCipher()).containsExactly(1, 2, 3);
        assertThat(saved.getApiKeyIv()).containsExactly(4, 5, 6);
        assertThat(saved.getCreatedBy()).isEqualTo("7");
        assertThat(saved.getUpdatedBy()).isEqualTo("7");
        assertThat(saved.getStatus()).isEqualTo(1);
        assertThat(saved.getPriority()).isEqualTo(100);
        assertThat(saved.getIsDefault()).isFalse();
        verify(mapper, never()).clearOtherDefaults(anyLong(), anyString(), any());
    }

    @Test
    void upsert_default_true_clears_other_defaults_first() {
        stubEncrypt();
        UpsertLlmConfigRequest r = chatRequest("https://api.x.com");
        r.setIsDefault(true);

        service.upsert(7L, r);

        verify(mapper).clearOtherDefaults(7L, "CHAT", null);
        verify(mapper).upsert(any(LlmModelConfig.class));
    }

    @Test
    void upsert_non_chat_rejected_unsupported() {
        UpsertLlmConfigRequest r = chatRequest("https://x.com");
        r.setCapabilityType("EMBEDDING");

        assertThatThrownBy(() -> service.upsert(7L, r))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("CHAT");

        verifyNoInteractions(mapper, apiKeyCipher, baseUrlValidator);
    }

    @Test
    void upsert_reranking_rejected_unsupported() {
        UpsertLlmConfigRequest r = chatRequest("https://x.com");
        r.setCapabilityType("RERANKING");

        assertThatThrownBy(() -> service.upsert(7L, r))
            .isInstanceOf(ClientException.class);
        verifyNoInteractions(mapper, apiKeyCipher, baseUrlValidator);
    }

    @Test
    void upsert_missing_required_fields_rejected_before_side_effects() {
        UpsertLlmConfigRequest r = new UpsertLlmConfigRequest();
        r.setCapabilityType("CHAT");
        r.setProviderCode("deepseek");
        // baseUrl / apiKey / modelName 空

        assertThatThrownBy(() -> service.upsert(7L, r)).isInstanceOf(ClientException.class);
        verifyNoInteractions(mapper, apiKeyCipher, baseUrlValidator);
    }

    @Test
    void upsert_default_priority_and_status_applied() {
        stubEncrypt();
        UpsertLlmConfigRequest r = chatRequest("https://x.com");

        service.upsert(7L, r);

        ArgumentCaptor<LlmModelConfig> captor = ArgumentCaptor.forClass(LlmModelConfig.class);
        verify(mapper).upsert(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo(100);
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getSupportsStreaming()).isFalse();
    }

    // ===== delete（owner-only）=====

    @Test
    void delete_owner_succeeds_soft_deletes() {
        LlmModelConfig e = new LlmModelConfig();
        e.setId(10L);
        e.setUserId(7L);
        when(mapper.selectById(10L)).thenReturn(e);

        service.delete(7L, 10L);

        verify(mapper).deleteById(10L);
    }

    @Test
    void delete_non_owner_throws_forbidden() {
        LlmModelConfig e = new LlmModelConfig();
        e.setId(10L);
        e.setUserId(7L);
        when(mapper.selectById(10L)).thenReturn(e);

        assertThatThrownBy(() -> service.delete(99L, 10L))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("owner");
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void delete_not_found_throws_bad_request() {
        when(mapper.selectById(10L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(7L, 10L))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("不存在");
        verify(mapper, never()).deleteById(anyLong());
    }

    // ===== getOwned（owner-only 单条读）=====

    @Test
    void getOwned_owner_returns_entity() {
        LlmModelConfig e = new LlmModelConfig();
        e.setId(10L);
        e.setUserId(7L);
        when(mapper.selectById(10L)).thenReturn(e);

        assertThat(service.getOwned(7L, 10L)).isSameAs(e);
    }

    @Test
    void getOwned_non_owner_throws_forbidden() {
        LlmModelConfig e = new LlmModelConfig();
        e.setId(10L);
        e.setUserId(7L);
        when(mapper.selectById(10L)).thenReturn(e);

        assertThatThrownBy(() -> service.getOwned(99L, 10L))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("owner");
    }

    @Test
    void getOwned_not_found_throws_bad_request() {
        when(mapper.selectById(10L)).thenReturn(null);

        assertThatThrownBy(() -> service.getOwned(7L, 10L))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("不存在");
    }

    // ===== 读 / 解密 / 脱敏 =====

    @Test
    void resolveUserChain_delegates_selectEnabled() {
        service.resolveUserChain(7L, LlmCapability.CHAT);
        verify(mapper).selectEnabled(7L, "CHAT");
    }

    @Test
    void selectAll_delegates_selectAll() {
        service.selectAll(7L, LlmCapability.CHAT);
        verify(mapper).selectAll(7L, "CHAT");
    }

    @Test
    void decryptKey_delegates_cipher() {
        LlmModelConfig e = new LlmModelConfig();
        e.setApiKeyCipher(new byte[]{1});
        e.setApiKeyIv(new byte[]{2});
        when(apiKeyCipher.decrypt(new byte[]{1}, new byte[]{2})).thenReturn("plain");

        assertThat(service.decryptKey(e)).isEqualTo("plain");
    }

    @Test
    void maskKey_normal_returns_prefix_stars_last4() {
        LlmModelConfig e = new LlmModelConfig();
        when(apiKeyCipher.decrypt(any(), any())).thenReturn("sk-abcd1234efgh5678");

        assertThat(service.maskKey(e)).isEqualTo("sk-***5678");
    }

    @Test
    void maskKey_short_key_returns_full_mask() {
        when(apiKeyCipher.decrypt(any(), any())).thenReturn("short");

        assertThat(service.maskKey(new LlmModelConfig())).isEqualTo("****");
    }

    @Test
    void maskKey_decrypt_failure_returns_mask_without_leaking() {
        when(apiKeyCipher.decrypt(any(), any())).thenThrow(new IllegalStateException("tag mismatch"));

        assertThat(service.maskKey(new LlmModelConfig())).isEqualTo("****");
    }
}
