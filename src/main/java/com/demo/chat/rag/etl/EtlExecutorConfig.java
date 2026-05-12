package com.demo.chat.rag.etl;

import com.demo.chat.rag.config.EtlExecutorProperties;
import com.demo.chat.rag.etl.EtlRouteStrategy;
import com.demo.chat.rag.etl.EtlRouteStrategyFactory;
import org.slf4j.Logger;

import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * ETL 线程池配置
 * <p>
 * 注册 IO 密集型和 CPU 密集型两个线程池 Bean，
 * 以及统一的 {@link EtlTaskExecutorBridge} 门面。
 * <p>
 * 参数从 {@link EtlExecutorProperties} 读取，不硬编码。
 */
@Configuration
@EnableConfigurationProperties(EtlExecutorProperties.class)
public class EtlExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(EtlExecutorConfig.class);

    /**
     * IO 密集型线程池 — 用于文件读取、MinIO 下载、Embedding API、PGvector 写入
     */
    @Bean("etlIoExecutor")
    public ThreadPoolTaskExecutor etlIoExecutor(EtlExecutorProperties properties) {
        EtlExecutorProperties.PoolConfig cfg = properties.getIo();
        ThreadPoolTaskExecutor executor = buildExecutor(cfg);
        log.info("ETL IO executor: core={}, max={}, queue={}, prefix={}",
                cfg.getCorePoolSize(), cfg.getMaxPoolSize(), cfg.getQueueCapacity(), cfg.getThreadNamePrefix());
        return executor;
    }

    /**
     * CPU 密集型线程池 — 用于文本分块、文档解析计算
     */
    @Bean("etlCpuExecutor")
    public ThreadPoolTaskExecutor etlCpuExecutor(EtlExecutorProperties properties) {
        EtlExecutorProperties.PoolConfig cfg = properties.getCpu();
        ThreadPoolTaskExecutor executor = buildExecutor(cfg);
        log.info("ETL CPU executor: core={}, max={}, queue={}, prefix={}",
                cfg.getCorePoolSize(), cfg.getMaxPoolSize(), cfg.getQueueCapacity(), cfg.getThreadNamePrefix());
        return executor;
    }

    /**
     * ETL 任务执行器门面
     */
    @Bean
    public EtlTaskExecutorBridge etlTaskExecutorBridge(ThreadPoolTaskExecutor etlIoExecutor,
                                                        ThreadPoolTaskExecutor etlCpuExecutor) {
        return new EtlTaskExecutorBridge(etlIoExecutor, etlCpuExecutor);
    }

    /**
     * 分片上传合并线程池 — 异步执行 composeObject + MD5 校验 + DB 写入 + ETL 触发
     */
    @Bean("mergeExecutor")
    public ThreadPoolTaskExecutor mergeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("merge-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        log.info("Merge executor initialized: core=2, max=4, queue=20");
        return executor;
    }

    /**
     * ETL 路由策略工厂 — 自动发现所有 EtlRouteStrategy 实现
     */
    @Bean
    public EtlRouteStrategyFactory etlRouteStrategyFactory(List<EtlRouteStrategy> strategies) {
        return new EtlRouteStrategyFactory(strategies);
    }

    private ThreadPoolTaskExecutor buildExecutor(EtlExecutorProperties.PoolConfig cfg) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.getCorePoolSize());
        executor.setMaxPoolSize(cfg.getMaxPoolSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setThreadNamePrefix(cfg.getThreadNamePrefix());
        executor.setKeepAliveSeconds(cfg.getKeepAliveSeconds());
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
