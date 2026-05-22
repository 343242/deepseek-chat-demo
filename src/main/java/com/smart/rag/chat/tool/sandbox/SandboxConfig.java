package com.smart.rag.chat.tool.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * 沙箱配置属性
 * <p>
 * 通过 {@code app.sandbox.*} 配置，支持运行时调整。
 *
 * @param enabled          是否启用沙箱（Docker 不可用时自动禁用）
 * @param maxConcurrency   最大并发沙箱数
 * @param timeout          单次执行超时
 * @param maxMemoryMB      容器内存上限（MB）
 * @param maxCpus          容器 CPU 限制
 * @param maxOutputBytes   输出截断上限（字节）
 * @param languageImages   语言 → Docker 镜像映射
 */
@ConfigurationProperties(prefix = "app.sandbox")
public record SandboxConfig(
        boolean enabled,
        int maxConcurrency,
        Duration timeout,
        int maxMemoryMB,
        double maxCpus,
        int maxOutputBytes,
        Map<String, String> languageImages
) {

    /** 默认值 */
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_CONCURRENCY = 3;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_MEMORY_MB = 128;
    public static final double DEFAULT_CPUS = 1.0;
    public static final int DEFAULT_MAX_OUTPUT = 10240; // 10KB

    public SandboxConfig() {
        this(DEFAULT_ENABLED, DEFAULT_CONCURRENCY, DEFAULT_TIMEOUT,
                DEFAULT_MEMORY_MB, DEFAULT_CPUS, DEFAULT_MAX_OUTPUT, defaultImages());
    }

    /** 语言 → 镜像的默认映射 */
    private static Map<String, String> defaultImages() {
        return Map.of(
                "PYTHON", "sandbox-python:bookworm",
                "JAVASCRIPT", "sandbox-node:bookworm",
                "TYPESCRIPT", "sandbox-node:bookworm",
                "JAVA", "sandbox-java:bookworm"
        );
    }

    /**
     * 获取指定语言的 Docker 镜像名
     */
    public String getImage(Language language) {
        return languageImages.getOrDefault(language.name(),
                languageImages.get(language.name().toUpperCase()));
    }
}
