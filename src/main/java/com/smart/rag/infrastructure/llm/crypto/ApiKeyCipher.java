package com.smart.rag.infrastructure.llm.crypto;

import com.smart.rag.infrastructure.llm.config.LlmByokProperties;
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
 * BYOK api_key 加密器 — AES/GCM/NoPadding（256-bit key + 12B 随机 IV + 128-bit auth tag）。
 * <p>
 * <b>转换</b>：{@code AES/GCM/NoPadding}（GCM 流认证模式，密文含 16B auth tag）。
 * <b>Key</b>：256-bit，来自 {@link LlmCryptoProperties#getMasterKey()}（base64 32B，env 注入）。
 * <b>IV</b>：每行独立 12B 随机（{@link SecureRandom}），不重复即满足 GCM 安全。
 * <b>存储</b>：密文（含 tag）与 IV 分存 {@code api_key_cipher}/{@code api_key_iv} 两列。
 * <p>
 * <b>启动校验（fail-fast，P0-3）</b>：构造时若 {@code app.llm.byok.enabled=true}，
 * master-key 缺失 / 非 base64 / 解码后非 32B → 抛 {@link IllegalStateException}，上下文启动失败；
 * {@code enabled=false}（紧急回滚）跳过校验，{@link #isAvailable()} 返回 false，encrypt/decrypt 拒绝服务。
 * <p>
 * master-key 旋转不纳入本期（密文无版本标记）；误改检测（canary 自检，P2-10）依赖 Mapper，
 * 见 Step 3 Mapper 就绪后独立 runner。
 *
 * @see LlmCryptoProperties
 */
@Component
public class ApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCipher.class);

    /** AES/GCM/NoPadding（JDK 内置，含 128-bit auth tag） */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** GCM auth tag 长度（bit） */
    private static final int GCM_TAG_BITS = 128;
    /** GCM 推荐 IV 长度 12B */
    private static final int IV_BYTES = 12;
    /** AES key 算法名 */
    private static final String ALGORITHM = "AES";
    /** master-key 解码后长度（256-bit = 32B） */
    private static final int KEY_BYTES = 32;

    /** null 表示 BYOK 关闭（enabled=false），加解密不可用 */
    private final SecretKey keySpec;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyCipher(LlmCryptoProperties cryptoProperties, LlmByokProperties byokProperties) {
        this.keySpec = resolveKey(cryptoProperties, byokProperties);
    }

    /**
     * 解析并校验 master-key。
     * <p>
     * enabled=false → 返回 null（跳过校验，回滚路径可达）；
     * enabled=true → 缺失/非 base64/长度错 → 抛 {@link IllegalStateException}（fail-fast）。
     */
    private static SecretKey resolveKey(LlmCryptoProperties props, LlmByokProperties byok) {
        if (!byok.isEnabled()) {
            log.warn("app.llm.byok.enabled=false → 跳过 master-key 校验，ApiKeyCipher 不可用（纯 yml 回滚模式）");
            return null;
        }
        String base64 = props.getMasterKey();
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                "app.llm.crypto.master-key 缺失：BYOK enabled=true 时必须配置 env LLM_MASTER_KEY（base64 编码 32B）");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.llm.crypto.master-key 非 base64 编码", e);
        }
        try {
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalStateException(
                    "app.llm.crypto.master-key 解码后必须 " + KEY_BYTES + "B（256-bit），实际 " + keyBytes.length + "B");
            }
            return new SecretKeySpec(keyBytes, ALGORITHM);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * 加密明文 api_key。
     *
     * @param plain 明文 api_key
     * @return 密文（含 16B auth tag）+ 独立 12B IV
     * @throws IllegalStateException BYOK 关闭（key 不可用）或 JCE 异常
     */
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

    /**
     * 解密 api_key 密文（SPI 取用时瞬态调用）。
     *
     * @param cipherText 密文（含 auth tag）
     * @param iv         12B IV
     * @return 明文 api_key
     * @throws IllegalStateException BYOK 关闭或 JCE 异常（含 auth tag 校验失败：密文/IV 篡改、key 不匹配）
     */
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

    /** BYOK 是否启用（master-key 已就绪） */
    public boolean isAvailable() {
        return keySpec != null;
    }

    private void requireAvailable() {
        if (keySpec == null) {
            throw new IllegalStateException(
                "ApiKeyCipher 不可用（app.llm.byok.enabled=false），BYOK 加解密被拒绝");
        }
    }

    /** 加密结果：密文（含 auth tag）+ IV，分存两列 */
    public record CipherText(byte[] cipher, byte[] iv) {
    }
}
