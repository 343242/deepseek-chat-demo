package com.smart.rag.chat.context;

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

    /** 模型参数缓存 TTL（秒） */
    private volatile int modelParamsCacheTtlSeconds = 30;

    /** 模型参数缓存最大容量 */
    private volatile int modelParamsCacheMaxSize = 200;

    /** System Prompt 缓存 TTL（分钟） */
    private volatile int systemPromptCacheTtlMinutes = 5;

    /** System Prompt 缓存最大容量 */
    private volatile int systemPromptCacheMaxSize = 200;

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

    public int getModelParamsCacheTtlSeconds() {
        return modelParamsCacheTtlSeconds;
    }

    public void setModelParamsCacheTtlSeconds(int modelParamsCacheTtlSeconds) {
        this.modelParamsCacheTtlSeconds = modelParamsCacheTtlSeconds;
    }

    public int getModelParamsCacheMaxSize() {
        return modelParamsCacheMaxSize;
    }

    public void setModelParamsCacheMaxSize(int modelParamsCacheMaxSize) {
        this.modelParamsCacheMaxSize = modelParamsCacheMaxSize;
    }

    public int getSystemPromptCacheTtlMinutes() {
        return systemPromptCacheTtlMinutes;
    }

    public void setSystemPromptCacheTtlMinutes(int systemPromptCacheTtlMinutes) {
        this.systemPromptCacheTtlMinutes = systemPromptCacheTtlMinutes;
    }

    public int getSystemPromptCacheMaxSize() {
        return systemPromptCacheMaxSize;
    }

    public void setSystemPromptCacheMaxSize(int systemPromptCacheMaxSize) {
        this.systemPromptCacheMaxSize = systemPromptCacheMaxSize;
    }
}
