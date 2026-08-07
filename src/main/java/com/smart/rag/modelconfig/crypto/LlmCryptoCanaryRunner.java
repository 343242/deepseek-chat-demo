package com.smart.rag.modelconfig.crypto;

import com.smart.rag.infrastructure.llm.config.LlmByokProperties;
import com.smart.rag.infrastructure.llm.crypto.ApiKeyCipher;
import com.smart.rag.modelconfig.entity.LlmModelConfig;
import com.smart.rag.modelconfig.mapper.LlmModelConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * BYOK 加密 canary 自检（design §3，对抗审查 P2-10）。
 * <p>
 * 启动时（{@link ApplicationReadyEvent}）若 {@code llm_config} 有存量行，取一行做解密自检。
 * 失败 → WARN（<b>不 fail-fast</b>，让运维感知 master-key 误改但不阻断启动；BYOK 静默回落系统级）。
 * <p>
 * 触发条件：{@code app.llm.byok.enabled=true} 且 master-key 已就绪（{@link ApiKeyCipher#isAvailable()}）。
 * <p>
 * 放 modelconfig 包避免循环依赖（modelconfig.service → infrastructure.llm.crypto.ApiKeyCipher 单向）。
 */
@Component
public class LlmCryptoCanaryRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmCryptoCanaryRunner.class);

    private final ApiKeyCipher apiKeyCipher;
    private final LlmModelConfigMapper mapper;
    private final LlmByokProperties byokProperties;

    public LlmCryptoCanaryRunner(ApiKeyCipher apiKeyCipher, LlmModelConfigMapper mapper,
                                 LlmByokProperties byokProperties) {
        this.apiKeyCipher = apiKeyCipher;
        this.mapper = mapper;
        this.byokProperties = byokProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void canary() {
        if (!byokProperties.isEnabled()) {
            return;
        }
        if (!apiKeyCipher.isAvailable()) {
            return;
        }
        try {
            LlmModelConfig row = mapper.selectOneAny();
            if (row == null) {
                return; // 无存量行，无需自检
            }
            apiKeyCipher.decrypt(row.getApiKeyCipher(), row.getApiKeyIv());
            log.info("BYOK crypto canary OK: 存量密文可正常解密");
        } catch (Exception e) {
            // master-key 误改 / 密文损坏 → WARN（不抛，不阻断启动）；BYOK 静默回落系统级
            log.warn("BYOK crypto canary FAILED: 存量密文无法解密，疑似 master-key 误改（env SECURITY_CRYPTO_MASTER_KEY）。"
                + "BYOK 将静默回落系统级，请核对 master-key。原因: {}", e.getMessage());
        }
    }
}
