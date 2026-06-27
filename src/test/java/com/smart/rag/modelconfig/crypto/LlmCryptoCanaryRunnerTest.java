package com.smart.rag.modelconfig.crypto;

import com.smart.rag.infrastructure.llm.config.LlmByokProperties;
import com.smart.rag.infrastructure.llm.crypto.ApiKeyCipher;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import com.smart.rag.modelconfig.mapper.LlmModelConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmCryptoCanaryRunner 单元测试（P2-10）— canary 仅 WARN 不抛、跳过条件、解密自检调用。
 */
@ExtendWith(MockitoExtension.class)
class LlmCryptoCanaryRunnerTest {

    @Mock private ApiKeyCipher apiKeyCipher;
    @Mock private LlmModelConfigMapper mapper;
    @Mock private LlmByokProperties byokProperties;

    @InjectMocks private LlmCryptoCanaryRunner runner;

    @Test
    void skip_when_byok_disabled() {
        when(byokProperties.isEnabled()).thenReturn(false);

        runner.canary();

        verify(mapper, never()).selectOneAny();
    }

    @Test
    void skip_when_cipher_unavailable() {
        when(byokProperties.isEnabled()).thenReturn(true);
        when(apiKeyCipher.isAvailable()).thenReturn(false);

        runner.canary();

        verify(mapper, never()).selectOneAny();
    }

    @Test
    void skip_when_no_existing_rows() {
        when(byokProperties.isEnabled()).thenReturn(true);
        when(apiKeyCipher.isAvailable()).thenReturn(true);
        when(mapper.selectOneAny()).thenReturn(null);

        runner.canary();

        verify(apiKeyCipher, never()).decrypt(any(byte[].class), any(byte[].class));
    }

    @Test
    void ok_when_existing_row_decrypts() {
        when(byokProperties.isEnabled()).thenReturn(true);
        when(apiKeyCipher.isAvailable()).thenReturn(true);
        LlmModelConfig row = new LlmModelConfig();
        row.setApiKeyCipher(new byte[]{1});
        row.setApiKeyIv(new byte[]{2});
        when(mapper.selectOneAny()).thenReturn(row);

        runner.canary();

        verify(apiKeyCipher).decrypt(any(byte[].class), any(byte[].class));
    }

    @Test
    void warn_only_no_throw_when_decrypt_fails() {
        when(byokProperties.isEnabled()).thenReturn(true);
        when(apiKeyCipher.isAvailable()).thenReturn(true);
        LlmModelConfig row = new LlmModelConfig();
        row.setApiKeyCipher(new byte[]{1});
        row.setApiKeyIv(new byte[]{2});
        when(mapper.selectOneAny()).thenReturn(row);
        when(apiKeyCipher.decrypt(any(byte[].class), any(byte[].class)))
            .thenThrow(new IllegalStateException("tag mismatch"));

        // 不抛（WARN only，不阻断启动）
        runner.canary();

        verify(apiKeyCipher).decrypt(any(byte[].class), any(byte[].class));
    }
}
