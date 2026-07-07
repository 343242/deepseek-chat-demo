package com.smart.rag.infrastructure.llm.crypto;

import com.smart.rag.infrastructure.llm.config.LlmByokProperties;
import com.smart.rag.infrastructure.security.SecretCipher;
import com.smart.rag.infrastructure.security.SecurityCryptoProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyCipherTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static SecurityCryptoProperties cryptoWith(String key) {
        SecurityCryptoProperties p = new SecurityCryptoProperties();
        p.setMasterKey(key);
        return p;
    }

    private static LlmByokProperties byok(boolean enabled) {
        LlmByokProperties p = new LlmByokProperties();
        p.setEnabled(enabled);
        return p;
    }

    private static ApiKeyCipher cipher(String key, boolean byokEnabled) {
        return new ApiKeyCipher(new SecretCipher(cryptoWith(key)), byok(byokEnabled));
    }

    @Test
    void encryptThenDecrypt_roundTripsOriginal() {
        ApiKeyCipher cipher = cipher(VALID_KEY, true);
        String plain = "sk-abcd1234efgh5678";

        ApiKeyCipher.CipherText ct = cipher.encrypt(plain);

        assertThat(cipher.decrypt(ct.cipher(), ct.iv())).isEqualTo(plain);
    }

    @Test
    void encrypt_producesDifferentIvAndCipherForEachCall() {
        ApiKeyCipher cipher = cipher(VALID_KEY, true);
        String plain = "sk-same-key";

        ApiKeyCipher.CipherText a = cipher.encrypt(plain);
        ApiKeyCipher.CipherText b = cipher.encrypt(plain);

        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.cipher()).isNotEqualTo(b.cipher());
        assertThat(cipher.decrypt(a.cipher(), a.iv())).isEqualTo(plain);
        assertThat(cipher.decrypt(b.cipher(), b.iv())).isEqualTo(plain);
    }

    @Test
    void encrypt_cipherIncludesAuthTagAndIvIs12Bytes() {
        ApiKeyCipher cipher = cipher(VALID_KEY, true);
        String plain = "0123456789";

        ApiKeyCipher.CipherText ct = cipher.encrypt(plain);

        assertThat(ct.iv()).hasSize(12);
        assertThat(ct.cipher()).hasSize(plain.getBytes().length + 16);
    }

    @Test
    void decrypt_wrongIv_failsWithTagMismatch() {
        ApiKeyCipher cipher = cipher(VALID_KEY, true);
        ApiKeyCipher.CipherText ct = cipher.encrypt("sk-key");

        assertThatThrownBy(() -> cipher.decrypt(ct.cipher(), new byte[12]))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("decrypt 失败")
            .hasCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void decrypt_tamperedCipher_failsWithTagMismatch() {
        ApiKeyCipher cipher = cipher(VALID_KEY, true);
        ApiKeyCipher.CipherText ct = cipher.encrypt("sk-key");
        byte[] tampered = ct.cipher().clone();
        tampered[0] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(tampered, ct.iv()))
            .isInstanceOf(IllegalStateException.class)
            .hasCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void enabledTrue_missingKey_throwsAtConstruction() {
        assertThatThrownBy(() -> cipher(null, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("master-key 缺失");
        assertThatThrownBy(() -> cipher("   ", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("master-key 缺失");
    }

    @Test
    void enabledTrue_invalidBase64Key_throwsAtConstruction() {
        assertThatThrownBy(() -> cipher("not!!base64!!", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("非 base64");
    }

    @Test
    void enabledTrue_wrongLengthKey_throwsAtConstruction() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> cipher(shortKey, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32B");
    }

    @Test
    void enabledFalse_missingKey_constructsSuccessfullyAndUnavailable() {
        ApiKeyCipher cipher = cipher(null, false);

        assertThat(cipher.isAvailable()).isFalse();
    }

    @Test
    void enabledFalse_encryptRejectsService() {
        ApiKeyCipher cipher = cipher(null, false);

        assertThatThrownBy(() -> cipher.encrypt("sk-key"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不可用");
    }

    @Test
    void decrypt_garbageInput_fails() {
        ApiKeyCipher cipher = cipher(VALID_KEY, true);

        assertThatThrownBy(() -> cipher.decrypt(new byte[]{1, 2, 3}, new byte[12]))
            .isInstanceOf(IllegalStateException.class);
    }
}
