package com.smart.rag.agent.config;

import com.smart.rag.config.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * RAG 检索线程池配置
 * <p>
 * 注册 {@code ragSearchExecutor} Bean，供 HybridSearchService 做 vector + BM25 并行检索。
 * <p>
 * 遵循与 ETL 线程池相同的标准：完整 7 参数配置、NamedThreadFactory、CallerRunsPolicy。
 */
@Configuration
@EnableConfigurationProperties(RagSearchExecutorProperties.class)
public class RagSearchExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(RagSearchExecutorConfig.class);

    @Bean("ragSearchExecutor")
    public ThreadPoolTaskExecutor ragSearchExecutor(RagSearchExecutorProperties cfg) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.getCorePoolSize());
        executor.setMaxPoolSize(cfg.getMaxPoolSize());
        executor.setKeepAliveSeconds(cfg.getKeepAliveSeconds());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setThreadFactory(new NamedThreadFactory(cfg.getThreadNamePrefix()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        log.info("RAG search executor: core={}, max={}, queue={}, prefix={}",
                cfg.getCorePoolSize(), cfg.getMaxPoolSize(), cfg.getQueueCapacity(), cfg.getThreadNamePrefix());
        return executor;
    }
}
