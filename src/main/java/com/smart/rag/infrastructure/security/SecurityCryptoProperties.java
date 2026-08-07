package com.smart.rag.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通用加密配置 — {@code app.security.crypto.*}。
 * <p>
 * 持有 {@link SecretCipher} AES/GCM 加密所用 master-key（base64 编码 32B = 256-bit）。
 * 经 env {@code SECURITY_CRYPTO_MASTER_KEY} 注入；<b>不入库不入 git</b>。
 * <p>
 * <b>缺失 master-key 时的行为</b>：{@link SecretCipher} 构造时 <b>不 fail-fast</b>，
 * {@code isAvailable()} 返回 false，encrypt/decrypt 抛 {@link ServiceException}。
 * 是否在启动期 fail-fast 由消费方决定（如 {@code ApiKeyCipher} 在 BYOK enabled=true 时 fail-fast）。
 */
@Component
@ConfigurationProperties(prefix = "app.security.crypto")
public class SecurityCryptoProperties {

    /** AES/GCM master-key，base64 编码 32B（256-bit）；env {@code SECURITY_CRYPTO_MASTER_KEY} 注入 */
    private String masterKey;

    /** Current key identifier used in v2 envelopes */
    private String keyId = "1";

    /** Optional previous master-key for rotation; base64 32B */
    private String previousMasterKey;

    /** Key identifier for previousMasterKey */
    private String previousKeyId;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getPreviousMasterKey() {
        return previousMasterKey;
    }

    public void setPreviousMasterKey(String previousMasterKey) {
        this.previousMasterKey = previousMasterKey;
    }

    public String getPreviousKeyId() {
        return previousKeyId;
    }

    public void setPreviousKeyId(String previousKeyId) {
        this.previousKeyId = previousKeyId;
    }
}
