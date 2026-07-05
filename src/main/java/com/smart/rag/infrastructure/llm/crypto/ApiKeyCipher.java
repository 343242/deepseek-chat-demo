package com.smart.rag.infrastructure.llm.crypto;

import com.smart.rag.infrastructure.llm.config.LlmByokProperties;
import com.smart.rag.infrastructure.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BYOK api_key 加密器 — {@link SecretCipher} 的 LLM 专属薄包装，加 BYOK enabled 门控。
 * <p>
 * <b>BYOK enabled=false（紧急回滚路径）</b>：{@link #isAvailable()} 返回 false；
 * encrypt/decrypt 抛 {@link IllegalStateException}（<b>不</b>调 SecretCipher，不需要 master-key）。
 * <p>
 * <b>BYOK enabled=true</b>：构造时若 {@link SecretCipher#isAvailable()} 为 false → fail-fast
 * （master-key 缺失/非 base64/长度错）；运行时 encrypt/decrypt 委托 SecretCipher。
 * <p>
 * <b>设计</b>：{@link SecretCipher} 本身无 BYOK 概念（通用加密器，MCP 等模块直接用）。
 * 本类把 LLM-specific 的 BYOK enabled 门控叠加到通用加密器之上，保持 LLM 模块原语义不变。
 */
@Component
public class ApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCipher.class);

    private final SecretCipher secretCipher;
    private final LlmByokProperties byokProperties;

    public ApiKeyCipher(SecretCipher secretCipher, LlmByokProperties byokProperties) {
        this.secretCipher = secretCipher;
        this.byokProperties = byokProperties;
        if (byokProperties.isEnabled() && !secretCipher.isAvailable()) {
            throw new IllegalStateException(
                "app.llm.byok.enabled=true 但 SecretCipher 不可用（master-key 缺失/非法）；"
                    + "配置 env SECURITY_CRYPTO_MASTER_KEY 或设 LLM_BYOK_ENABLED=false 回滚");
        }
        if (!byokProperties.isEnabled()) {
            log.warn("app.llm.byok.enabled=false → ApiKeyCipher 不可用（纯 yml 回滚模式）");
        }
    }

    public CipherText encrypt(String plain) {
        requireAvailable();
        SecretCipher.CipherText ct = secretCipher.encrypt(plain);
        return new CipherText(ct.cipher(), ct.iv());
    }

    public String decrypt(byte[] cipherText, byte[] iv) {
        requireAvailable();
        return secretCipher.decrypt(cipherText, iv);
    }

    public boolean isAvailable() {
        return byokProperties.isEnabled() && secretCipher.isAvailable();
    }

    private void requireAvailable() {
        if (!byokProperties.isEnabled()) {
            throw new IllegalStateException(
                "ApiKeyCipher 不可用（app.llm.byok.enabled=false），BYOK 加解密被拒绝");
        }
    }

    public record CipherText(byte[] cipher, byte[] iv) {
    }
}
