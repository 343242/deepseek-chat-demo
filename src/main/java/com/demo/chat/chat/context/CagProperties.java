package com.demo.chat.chat.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CAG 上下文增强配置
 * <p>
 * 对应 application.yml 中 {@code app.cag.*} 配置项。
 * 所有开关默认开启，可按需关闭。
 */
@Component
@ConfigurationProperties(prefix = "app.cag")
public class CagProperties {

    /** 是否启用 CAG（总开关，关闭后不构建上下文） */
    private volatile boolean enabled = true;

    /** 是否将上下文注入到 system prompt */
    private volatile boolean injectPrompt = true;

    /** 是否记录上下文组装日志（可观测性） */
    private volatile boolean logContext = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isInjectPrompt() {
        return injectPrompt;
    }

    public void setInjectPrompt(boolean injectPrompt) {
        this.injectPrompt = injectPrompt;
    }

    public boolean isLogContext() {
        return logContext;
    }

    public void setLogContext(boolean logContext) {
        this.logContext = logContext;
    }
}
