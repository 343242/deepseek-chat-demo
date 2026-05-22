package com.smart.rag.config;

import com.smart.rag.chat.tool.CodeExecutionTool;
import com.smart.rag.chat.tool.sandbox.SandboxConfig;
import com.smart.rag.chat.tool.sandbox.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 沙箱自动配置
 * <p>
 * 注册 {@link SandboxService} Bean，供 {@link CodeExecutionTool} 使用。
 * Docker 不可用时 SandboxService.isAvailable() 返回 false，不影响其他功能。
 * <p>
 * 配置前缀：{@code app.sandbox.*}
 */
@Configuration
@EnableConfigurationProperties(SandboxConfig.class)
public class SandboxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SandboxAutoConfiguration.class);

    @Bean
    public SandboxService sandboxService(SandboxConfig config) {
        log.info("SandboxConfig: enabled={}, timeout={}s, maxMemory={}MB, maxCpus={}, maxConcurrency={}",
                config.enabled(), config.timeout().toSeconds(),
                config.maxMemoryMB(), config.maxCpus(), config.maxConcurrency());
        return new SandboxService(config);
    }
}
