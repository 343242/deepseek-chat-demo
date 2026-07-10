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
    void encodeThenDecodeRoundTripsAndUsesVersionedEnvelope() {
        McpBearerTokenCodec codec = codec(MASTER_KEY);

        String first = codec.encode("bearer-secret");
        String second = codec.encode("bearer-secret");

        assertThat(first).startsWith("v1:");
        assertThat(first.split(":", -1)).hasSize(3);
        assertThat(first).isNotEqualTo(second);
        assertThat(codec.decode(first)).isEqualTo("bearer-secret");
        assertThat(codec.decode(second)).isEqualTo("bearer-secret");
    }

    @Test
    void decodeNullOrBlankMeansNoToken() {
        McpBearerTokenCodec codec = codec(MASTER_KEY);

        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    void encodeRejectsBlankTokenAndUnavailableMasterKey() {
        assertThatThrownBy(() -> codec(MASTER_KEY).encode("  "))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> codec(null).encode("bearer-secret"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("master key");
    }

    @Test
    void decodeRejectsUnknownOrMalformedEnvelope() {
        McpBearerTokenCodec codec = codec(MASTER_KEY);

        assertThatThrownBy(() -> codec.decode("legacy-cipher-text"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可解密");
        assertThatThrownBy(() -> codec.decode("v2:YWJj:YWJj"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> codec.decode("v1:only-one-segment"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> codec.decode("v1:not!base64:also!bad"))
                .isInstanceOf(ServiceException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("v1:YWJj:YWJj"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可解密");
    }

    @Test
    void decodeRejectsTamperedCipherInsteadOfFallingBackToAnonymous() {
        McpBearerTokenCodec codec = codec(MASTER_KEY);
        String stored = codec.encode("bearer-secret");
        String[] parts = stored.split(":", -1);
        byte[] cipher = Base64.getDecoder().decode(parts[1]);
        cipher[0] ^= 1;
        String tampered = parts[0] + ":" + Base64.getEncoder().encodeToString(cipher) + ":" + parts[2];

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可解密")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private static McpBearerTokenCodec codec(String key) {
        SecurityCryptoProperties properties = new SecurityCryptoProperties();
        properties.setMasterKey(key);
        return new McpBearerTokenCodec(new SecretCipher(properties));
    }
}
