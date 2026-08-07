package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.security.SecretCipher;
import com.smart.rag.infrastructure.security.SecurityCryptoProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpBearerTokenCodecTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encodeThenDecodeRoundTripsAndUsesV2Envelope() {
        McpBearerTokenCodec codec = codec(MASTER_KEY, "1", null, null);

        String first = codec.encode("bearer-secret");
        String second = codec.encode("bearer-secret");

        assertThat(first).startsWith("v2:1:");
        assertThat(first.split(":", -1)).hasSize(4);
        assertThat(first).isNotEqualTo(second); // random IV
        assertThat(codec.decode(first)).isEqualTo("bearer-secret");
        assertThat(codec.decode(second)).isEqualTo("bearer-secret");
    }

    @Test
    void decodeNullOrBlankMeansNoToken() {
        McpBearerTokenCodec codec = codec(MASTER_KEY, "1", null, null);

        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    void encodeRejectsBlankTokenAndUnavailableMasterKey() {
        assertThatThrownBy(() -> codec(MASTER_KEY, "1", null, null).encode("  "))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> codec(null, "1", null, null).encode("bearer-secret"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("master key");
    }

    @Test
    void decodeRejectsV1AfterV19() {
        McpBearerTokenCodec codec = codec(MASTER_KEY, "1", null, null);

        // v1 format (3 parts) is rejected after V19
        assertThatThrownBy(() -> codec.decode("v1:YWJj:YWJj"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可解密");
    }

    @Test
    void decodeRejectsMalformedV2Envelope() {
        McpBearerTokenCodec codec = codec(MASTER_KEY, "1", null, null);

        assertThatThrownBy(() -> codec.decode("legacy-cipher-text"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可解密");
        assertThatThrownBy(() -> codec.decode("v2:YWJj:YWJj")) // only 3 parts
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> codec.decode("v2:keyId:not!base64:also!bad"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> codec.decode("v2:keyId:YWJj:YWJj")) // 12-byte IV expected, 3 bytes
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void decodeRejectsTamperedCipher() {
        McpBearerTokenCodec codec = codec(MASTER_KEY, "1", null, null);
        String stored = codec.encode("bearer-secret");
        String[] parts = stored.split(":", -1);
        byte[] cipher = Base64.getDecoder().decode(parts[2]);
        cipher[0] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(cipher) + ":" + parts[3];

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("decrypt 失败");
    }

    @Test
    void decodeWithPreviousKeyAfterRotation() {
        McpBearerTokenCodec oldCodec = codec(MASTER_KEY, "1", null, null);
        String stored = oldCodec.encode("bearer-secret");
        assertThat(stored).startsWith("v2:1:");

        // Rotate: current key changes, previous key is old current
        McpBearerTokenCodec newCodec = codec(
                Base64.getEncoder().encodeToString(new byte[32]),
                "2",
                MASTER_KEY,
                "1");

        // Previous-key ciphertext should still decrypt
        assertThat(newCodec.decode(stored)).isEqualTo("bearer-secret");
    }

    private static McpBearerTokenCodec codec(String currentKey, String currentKeyId,
                                              String previousKey, String previousKeyId) {
        SecurityCryptoProperties properties = new SecurityCryptoProperties();
        properties.setMasterKey(currentKey);
        properties.setKeyId(currentKeyId);
        properties.setPreviousMasterKey(previousKey);
        properties.setPreviousKeyId(previousKeyId);
        return new McpBearerTokenCodec(new SecretCipher(properties));
    }
}
