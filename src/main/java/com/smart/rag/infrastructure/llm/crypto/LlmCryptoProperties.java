package com.smart.rag.infrastructure.llm.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 加密配置 — 对应 YAML {@code app.llm.crypto}。
 * <p>
 * 持有 BYOK api_key 加密所用 master-key（AES/GCM/NoPadding，256-bit）。
 * key 经 env（{@code LLM_MASTER_KEY}）注入，base64 编码 32B，<b>不入库不入 git</b>。
 * <p>
 * 启动校验由 {@link ApiKeyCipher} 构造时执行：仅 {@code app.llm.byok.enabled=true} 时
 * fail-fast（缺失/非法 key → 启动失败）；{@code enabled=false} 跳过校验，保证紧急回滚路径可达（P0-3）。
 *
 * @see ApiKeyCipher
 * @see com.smart.rag.infrastructure.llm.config.LlmByokProperties
 */
@Component
@ConfigurationProperties(prefix = "app.llm.crypto")
public class LlmCryptoProperties {

    /** AES/GCM master-key，base64 编码 32B（256-bit）；env {@code LLM_MASTER_KEY} 注入 */
    private String masterKey;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
