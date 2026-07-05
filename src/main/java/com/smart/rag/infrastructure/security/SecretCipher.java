package com.smart.rag.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <b>用途</b>：任意敏感字符串（API key、Bearer token、其他 secret）的对称加密存储。
 * 当前消费方：{@code ApiKeyCipher}（LLM BYOK，带 BYOK enabled 门控）、
 * {@code McpClientFactory}（MCP Bearer token，无门控）。
 * <p>
 * <b>Key</b>：256-bit，来自 {@link SecurityCryptoProperties#getMasterKey()}（base64 32B，env 注入）。
 * <b>IV</b>：每行独立 12B 随机（{@link SecureRandom}），不重复即满足 GCM 安全。
 * <b>存储</b>：密文（含 16B auth tag）与 IV 分存两列，由调用方决定列名。
 * <p>
 * <b>master-key 缺失时的行为</b>：构造时 <b>不 fail-fast</b>，
 * {@code keySpec=null} + {@link #isAvailable()} 返回 false；
 * encrypt/decrypt 抛 {@link IllegalStateException}。
 * 是否在启动期 fail-fast 由消费方决定（如 {@code ApiKeyCipher} 在 BYOK enabled=true 时 fail-fast）。
 * <p>
 * master-key 旋转不纳入本期（密文无版本标记）。
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final String ALGORITHM = "AES";
    private static final int KEY_BYTES = 32;

    private final SecretKey keySpec;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(SecurityCryptoProperties cryptoProperties) {
        this.keySpec = resolveKey(cryptoProperties);
    }

    private static SecretKey resolveKey(SecurityCryptoProperties props) {
        String base64 = props.getMasterKey();
        if (base64 == null || base64.isBlank()) {
            log.warn("app.security.crypto.master-key 缺失 → SecretCipher 不可用；消费方决定是否 fail-fast");
            return null;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.security.crypto.master-key 非 base64 编码", e);
        }
        try {
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalStateException(
                    "app.security.crypto.master-key 解码后必须 " + KEY_BYTES + "B（256-bit），实际 " + keyBytes.length + "B");
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
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return new CipherText(cipherText, iv);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM encrypt 失败", e);
        }
    }

    public String decrypt(byte[] cipherText, byte[] iv) {
        requireAvailable();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM decrypt 失败（密文/IV 篡改或 master-key 不匹配）", e);
        }
    }

    public boolean isAvailable() {
        return keySpec != null;
    }

    private void requireAvailable() {
        if (keySpec == null) {
            throw new IllegalStateException("SecretCipher 不可用（master-key 缺失）");
        }
    }

    public record CipherText(byte[] cipher, byte[] iv) {
    }
}
