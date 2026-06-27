package com.smart.rag.infrastructure.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BYOK（Bring Your Own Key）总配置 — 对应 YAML {@code app.llm.byok}。
 * <p>
 * {@code enabled=false} 时 Registry 全走系统级 yml（等同改造前），便于灰度与紧急回滚；
 * 此时 {@link com.smart.rag.infrastructure.llm.crypto.ApiKeyCipher} 跳过 master-key 校验，
 * 保证 master-key 缺失场景下回滚指令不被 fail-fast 阻断（P0-3）。
 * <p>
 * 其余字段（user-cache-size / base-url.* 等）随对应 Step 逐步补充。
 */
@Component
@ConfigurationProperties(prefix = "app.llm.byok")
public class LlmByokProperties {

    /** BYOK 总开关；false 时全部走系统级 yml，master-key 校验跳过 */
    private boolean enabled = true;

    /** baseUrl 端口白名单（design §13.1）；默认 80/443，空/null 同默认 */
    private List<Integer> allowedPorts = List.of(80, 443);

    /** per-user 快照缓存容量（design §5.3 / R2：有界防 OOM/连接耗尽）；默认 1000 */
    private Integer userCacheSize = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Integer> getAllowedPorts() {
        return allowedPorts;
    }

    public void setAllowedPorts(List<Integer> allowedPorts) {
        this.allowedPorts = allowedPorts;
    }

    public Integer getUserCacheSize() {
        return userCacheSize;
    }

    public void setUserCacheSize(Integer userCacheSize) {
        this.userCacheSize = userCacheSize;
    }
}
