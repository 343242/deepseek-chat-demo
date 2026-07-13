package com.smart.rag.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 通用 AES/GCM/NoPadding 加密器（256-bit key + 12B 随机 IV + 128-bit auth tag）。
 * <p>
 * Supports exactly one current key and one optional previous key (design §R8).
 * New writes use current; reads accept current/previous.
 * CipherText includes keyId so MCP v2 envelopes record which key was used.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final String ALGORITHM = "AES";
    private static final int KEY_BYTES = 32;

    private final SecretKey currentKey;
    private final String currentKeyId;
    private final SecretKey previousKey;
    private final String previousKeyId;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(SecurityCryptoProperties props) {
        this.currentKey = resolveKey(props.getMasterKey(), "master-key");
        this.currentKeyId = props.getKeyId();
        this.previousKey = resolveKey(props.getPreviousMasterKey(), "previous-master-key");
        this.previousKeyId = props.getPreviousKeyId();

        if (previousKey != null && (previousKeyId == null || previousKeyId.isBlank())) {
            throw new IllegalStateException("previous-master-key 配置了但 previous-key-id 缺失");
        }
        if (currentKeyId != null && previousKeyId != null && currentKeyId.equals(previousKeyId)) {
            throw new IllegalStateException("key-id 与 previous-key-id 不能相同");
        }
    }

    private static SecretKey resolveKey(@Nullable String base64, String label) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.security.crypto." + label + " 非 base64 编码", e);
        }
        try {
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalStateException(
                    "app.security.crypto." + label + " 解码后必须 " + KEY_BYTES + "B（256-bit），实际 " + keyBytes.length + "B");
            }
            return new SecretKeySpec(keyBytes, ALGORITHM);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    public CipherText encrypt(String plain) {
        requireAvailable();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return new CipherText(cipherText, iv, currentKeyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM encrypt 失败", e);
        }
    }

    public String decrypt(byte[] cipherText, byte[] iv) {
        return decrypt(cipherText, iv, null);
    }

    /**
     * Decrypt with an optional key selector. If keyId is null or matches current,
     * try current key first. If keyId matches previousKeyId, try previous key.
     * If keyId is unknown, try current then previous (bounded to two attempts).
     */
    public String decrypt(byte[] cipherText, byte[] iv, @Nullable String keyId) {
        requireAvailable();
        // If keyId explicitly matches previous, try previous only
        if (previousKey != null && keyId != null && keyId.equals(previousKeyId)) {
            return doDecrypt(previousKey, cipherText, iv);
        }
        // Try current first
        try {
            return doDecrypt(currentKey, cipherText, iv);
        } catch (IllegalStateException e) {
            if (previousKey != null && (keyId == null || keyId.equals(currentKeyId))) {
                // Fall back to previous key
                return doDecrypt(previousKey, cipherText, iv);
            }
            throw e;
        }
    }

    private static String doDecrypt(SecretKey key, byte[] cipherText, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM decrypt 失败（密文/IV 篡改或 master-key 不匹配）", e);
        }
    }

    public boolean isAvailable() {
        return currentKey != null;
    }

    public String currentKeyId() {
        return currentKeyId;
    }

    private void requireAvailable() {
        if (currentKey == null) {
            throw new IllegalStateException("SecretCipher 不可用（master-key 缺失）");
        }
    }

    public record CipherText(byte[] cipher, byte[] iv, String keyId) {
        /** Legacy constructor for backward compatibility (keyId defaults to current) */
        public CipherText(byte[] cipher, byte[] iv) {
            this(cipher, iv, null);
        }
    }
}
