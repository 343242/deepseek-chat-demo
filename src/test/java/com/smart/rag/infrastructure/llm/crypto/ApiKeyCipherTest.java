package com.smart.rag.infrastructure.llm.crypto;

import com.smart.rag.infrastructure.llm.config.LlmByokProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ApiKeyCipher 单元测试 — 覆盖 AES/GCM 往返、IV 唯一性、密文完整性、
 * 启动 fail-fast（P0-3：enabled=true 缺/非法 key）与回滚路径（enabled=false 跳过校验）。
 * <p>
 * canary 自检（P2-10）依赖 Mapper，见 Step 3 Mapper 就绪后独立 runner 测试。
 */
class ApiKeyCipherTest {

    /** 全 0 的 32B key（base64），仅测试用；生产应随机生成 */
    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static LlmCryptoProperties cryptoWith(String key) {
        LlmCryptoProperties p = new LlmCryptoProperties();
        p.setMasterKey(key);
        return p;
    }

    private static LlmByokProperties byok(boolean enabled) {
        LlmByokProperties p = new LlmByokProperties();
        p.setEnabled(enabled);
        return p;
    }

    @Test
    void encryptThenDecrypt_roundTripsOriginal() {
        ApiKeyCipher cipher = new ApiKeyCipher(cryptoWith(VALID_KEY), byok(true));
        String plain = "sk-abcd1234efgh5678";

        ApiKeyCipher.CipherText ct = cipher.encrypt(plain);

        assertThat(cipher.decrypt(ct.cipher(), ct.iv())).isEqualTo(plain);
    }

    @Test
    void encrypt_producesDifferentIvAndCipherForEachCall() {
        ApiKeyCipher cipher = new ApiKeyCipher(cryptoWith(VALID_KEY), byok(true));
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
        ApiKeyCipher cipher = new ApiKeyCipher(cryptoWith(VALID_KEY), byok(true));
        String plain = "0123456789"; // 10 字节

        ApiKeyCipher.CipherText ct = cipher.encrypt(plain);

        assertThat(ct.iv()).hasSize(12);
        // GCM 密文 = 明文长度 + 16B auth tag
        assertThat(ct.cipher()).hasSize(plain.getBytes().length + 16);
    }

    @Test
    void decrypt_wrongIv_failsWithTagMismatch() {
        ApiKeyCipher cipher = new ApiKeyCipher(cryptoWith(VALID_KEY), byok(true));
        ApiKeyCipher.CipherText ct = cipher.encrypt("sk-key");

        // 用同一 key 但全 0 错误 IV 解密 → auth tag 校验失败
        assertThatThrownBy(() -> cipher.decrypt(ct.cipher(), new byte[12]))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("decrypt 失败")
            .hasCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void decrypt_tamperedCipher_failsWithTagMismatch() {
        ApiKeyCipher cipher = new ApiKeyCipher(cryptoWith(VALID_KEY), byok(true));
        ApiKeyCipher.CipherText ct = cipher.encrypt("sk-key");
        byte[] tampered = ct.cipher().clone();
        tampered[0] ^= 0x01; // 翻转一个 bit

        assertThatThrownBy(() -> cipher.decrypt(tampered, ct.iv()))
            .isInstanceOf(IllegalStateException.class)
            .hasCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void enabledTrue_missingKey_throwsAtConstruction() {
        assertThatThrownBy(() -> new ApiKeyCipher(cryptoWith(null), byok(true)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("master-key 缺失");
        assertThatThrownBy(() -> new ApiKeyCipher(cryptoWith("   "), byok(true)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("master-key 缺失");
    }

    @Test
    void enabledTrue_invalidBase64Key_throwsAtConstruction() {
        assertThatThrownBy(() -> new ApiKeyCipher(cryptoWith("not!!base64!!"), byok(true)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("非 base64");
    }

    @Test
    void enabledTrue_wrongLengthKey_throwsAtConstruction() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // 16B ≠ 32B

        assertThatThrownBy(() -> new ApiKeyCipher(cryptoWith(shortKey), byok(true)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32B");
    }

    @Test
    void enabledFalse_missingKey_constructsSuccessfullyAndUnavailable() {
        ApiKeyCipher cipher = new ApiKeyCipher(cryptoWith(null), byok(false));

        assertThat(cipher.isAvailable()).isFalse();
    }

    @Test
    void enabledFalse_encryptRejectsService() {
        ApiKeyCipher cipher = new ApiKeyCipher(cryptoWith(null), byok(false));

        assertThatThrownBy(() -> cipher.encrypt("sk-key"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不可用");
    }

    @Test
    void decrypt_garbageInput_fails() {
        ApiKeyCipher cipher = new ApiKeyCipher(cryptoWith(VALID_KEY), byok(true));

        assertThatThrownBy(() -> cipher.decrypt(new byte[]{1, 2, 3}, new byte[12]))
            .isInstanceOf(IllegalStateException.class);
    }
}
