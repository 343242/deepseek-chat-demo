package com.smart.rag.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 模式配置
 * <p>
 * 对应 application.yml 中 app.agent.* 配置项。
 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentRagProperties(
    // 开关
    boolean enabled,
    // 意图识别
    String intentModel,
    Double intentTemperature,
    int intentRetries,
    int intentTimeoutMs,
    // ReAct 循环护栏
    int maxToolIterations,
    int maxConsecutiveSameTool,
    double contextWindowRatio,
    // 容错
    int toolTimeoutMs,
    boolean degradeOnFailure
) {
    /**
     * 紧凑构造器 — 参数校验与默认值兜底
     */
    public AgentRagProperties {
        if (maxToolIterations <= 0) maxToolIterations = 10;
        if (maxConsecutiveSameTool <= 0) maxConsecutiveSameTool = 3;
        if (contextWindowRatio <= 0 || contextWindowRatio > 1) contextWindowRatio = 0.8;
        if (intentRetries < 0) intentRetries = 2;
        if (intentTimeoutMs <= 0) intentTimeoutMs = 5000;
        if (toolTimeoutMs <= 0) toolTimeoutMs = 10000;
        if (intentTemperature == null) intentTemperature = 0.1;
    }
}
