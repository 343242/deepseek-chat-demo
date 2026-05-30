package com.smart.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring MVC 配置。
 * <p>
 * 主要配置异步支持：为 SSE（Flux）响应提供专用线程池，
 * 替代默认的 {@code SimpleAsyncTaskExecutor}（每次创建新线程，不适合生产环境）。
 * <p>
 * 线程池参数通过 {@link MvcExecutorProperties} 外部化，
 * 遵循项目标准模式（对齐 EtlExecutorConfig）。
 */
@Configuration
@EnableConfigurationProperties(MvcExecutorProperties.class)
public class WebMvcConfig implements WebMvcConfigurer {

    private final MvcExecutorProperties mvcExecutorProperties;

    public WebMvcConfig(MvcExecutorProperties mvcExecutorProperties) {
        this.mvcExecutorProperties = mvcExecutorProperties;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncExecutor());
        // SSE 长连接超时：5 分钟（匹配 JWT Access Token 有效期）
        configurer.setDefaultTimeout(300_000);
    }

    @Bean("mvcAsyncExecutor")
    public ThreadPoolTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(mvcExecutorProperties.getCorePoolSize());
        executor.setMaxPoolSize(mvcExecutorProperties.getMaxPoolSize());
        executor.setQueueCapacity(mvcExecutorProperties.getQueueCapacity());
        executor.setKeepAliveSeconds(mvcExecutorProperties.getKeepAliveSeconds());
        executor.setThreadFactory(new NamedThreadFactory("mvc-async-"));
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
