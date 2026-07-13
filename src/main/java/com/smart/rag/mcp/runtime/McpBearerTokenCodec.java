package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.security.SecretCipher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Encodes the MCP bearer token as a versioned v2 key-ID AES-GCM envelope (design §R8).
 * <p>
 * Format: {@code v2:<keyId>:<base64 cipher+tag>:<base64 12-byte IV>}
 * <p>
 * V19 deletes all existing MCP rows, so v1 ciphertexts are never read.
 * After V19, v1/unknown key IDs are rejected.
 */
@Component
public class McpBearerTokenCodec {

    private static final String VERSION = "v2";
    private static final int GCM_IV_BYTES = 12;

    private final SecretCipher secretCipher;

    public McpBearerTokenCodec(SecretCipher secretCipher) {
        this.secretCipher = secretCipher;
    }

    public String encode(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "Bearer Token 不能为空");
        }
        if (!secretCipher.isAvailable()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST,
                    "Bearer Token 无法保存：安全 master key 未配置");
        }
        SecretCipher.CipherText encrypted = secretCipher.encrypt(bearerToken);
        Base64.Encoder encoder = Base64.getEncoder();
        return VERSION + ":" + encrypted.keyId()
                + ":" + encoder.encodeToString(encrypted.cipher())
                + ":" + encoder.encodeToString(encrypted.iv());
    }

    @Nullable
    public String decode(@Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (!secretCipher.isAvailable()) {
            throw unreadable(null);
        }
        String[] parts = stored.split(":", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])
                || parts[1].isBlank() || parts[2].isBlank() || parts[3].isBlank()) {
            throw unreadable(null);
        }
        try {
            String keyId = parts[1];
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] cipher = decoder.decode(parts[2]);
            byte[] iv = decoder.decode(parts[3]);
            if (iv.length != GCM_IV_BYTES) {
                throw unreadable(null);
            }
            return secretCipher.decrypt(cipher, iv, keyId);
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    private static ServiceException unreadable(@Nullable Throwable cause) {
        return new ServiceException(ServiceErrorCode.INTERNAL_ERROR,
                "MCP Bearer Token 配置不可解密，请重新设置", cause);
    }
}
