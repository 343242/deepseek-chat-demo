package com.smart.rag.infrastructure.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * BYOK（Bring Your Own Key）总配置 — 对应 YAML {@code app.llm.byok}。
 * <p>
 * {@code enabled=false} 时 Registry 全走系统级 yml（等同改造前），便于灰度与紧急回滚；
 * 此时 {@link com.smart.rag.infrastructure.llm.crypto.ApiKeyCipher} 跳过 master-key 校验，
 * 保证 master-key 缺失场景下回滚指令不被 fail-fast 阻断（P0-3）。
 * <p>
 * <b>v4 变更</b>：{@code allowedPorts} 字段已迁到
 * {@link com.smart.rag.infrastructure.security.SecuritySsrProperties}
 * （{@code app.security.ssrf.allowed-ports}），由通用 {@code HostSafetyValidator} 读取。
 */
@Component
@ConfigurationProperties(prefix = "app.llm.byok")
public class LlmByokProperties {

    /** BYOK 总开关；false 时全部走系统级 yml，master-key 校验跳过 */
    private boolean enabled = true;

    /** per-user 快照缓存容量（design §5.3 / R2：有界防 OOM/连接耗尽）；默认 1000 */
    private Integer userCacheSize = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getUserCacheSize() {
        return userCacheSize;
    }

    public void setUserCacheSize(Integer userCacheSize) {
        this.userCacheSize = userCacheSize;
    }
}
