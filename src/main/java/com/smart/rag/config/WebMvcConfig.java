package com.smart.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
        configurer.setTaskExecutor(new ConcurrentTaskExecutor(mvcAsyncExecutor()));
        // SSE 长连接超时：5 分钟（匹配 JWT Access Token 有效期）
        configurer.setDefaultTimeout(300_000);
    }

    @Bean(name = "mvcAsyncExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor mvcAsyncExecutor() {
        MvcExecutorProperties props = mvcExecutorProperties;
        return new ThreadPoolExecutor(
                props.getCorePoolSize(),
                props.getMaxPoolSize(),
                props.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(props.getQueueCapacity()),
                new NamedThreadFactory("mvc-async-"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
