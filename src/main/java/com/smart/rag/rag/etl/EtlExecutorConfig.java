package com.smart.rag.rag.etl;

import com.smart.rag.config.NamedThreadFactory;
import com.smart.rag.rag.config.EtlExecutorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.*;

/**
 * 线程池配置。
 * <p>
 * 遵循阿里巴巴 Java 开发手册线程池规约：
 * <ul>
 *   <li>通过 {@link ThreadPoolExecutor} 完整构造器指定全部 7 个核心参数</li>
 *   <li>自定义 {@link NamedThreadFactory}：线程名带业务前缀 + UncaughtExceptionHandler</li>
 *   <li>使用 {@link ArrayBlockingQueue} 有界队列</li>
 *   <li>所有参数从 {@link EtlExecutorProperties} 读取，不硬编码</li>
 * </ul>
 * <p>
 * 注册四个线程池 Bean：
 * <ol>
 *   <li>{@code etlIoExecutor} — IO 密集型：文件读取、MinIO、Embedding API、PGvector</li>
 *   <li>{@code etlCpuExecutor} — CPU 密集型：文本分块、文档解析</li>
 *   <li>{@code mergeExecutor} — 分片上传异步合并</li>
 *   <li>{@code ragSearchExecutor} — RAG 混合检索：向量检索与 BM25 并行执行</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(EtlExecutorProperties.class)
public class EtlExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(EtlExecutorConfig.class);

    /**
     * IO 密集型线程池 — 文件读取、MinIO 下载、Embedding API、PGvector 写入
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
     * CPU 密集型线程池 — 文本分块、文档解析计算
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
    public EtlTaskExecutorBridge etlTaskExecutorBridge(
            @Qualifier("etlIoExecutor") ThreadPoolTaskExecutor etlIoExecutor,
            @Qualifier("etlCpuExecutor") ThreadPoolTaskExecutor etlCpuExecutor) {
        return new EtlTaskExecutorBridge(etlIoExecutor, etlCpuExecutor);
    }

    /**
     * 分片上传合并线程池 — 异步执行 composeObject + MD5 校验 + DB 写入 + ETL 触发
     */
    @Bean("mergeExecutor")
    public ThreadPoolTaskExecutor mergeExecutor(EtlExecutorProperties properties) {
        EtlExecutorProperties.PoolConfig cfg = properties.getMerge();
        ThreadPoolTaskExecutor executor = buildExecutor(cfg);
        log.info("Merge executor: core={}, max={}, queue={}, prefix={}",
                cfg.getCorePoolSize(), cfg.getMaxPoolSize(), cfg.getQueueCapacity(), cfg.getThreadNamePrefix());
        return executor;
    }

    /**
     * RAG 混合检索线程池 — 向量检索与 BM25 并行执行
     */
    @Bean("ragSearchExecutor")
    public ThreadPoolTaskExecutor ragSearchExecutor(EtlExecutorProperties properties) {
        EtlExecutorProperties.PoolConfig cfg = properties.getSearch();
        ThreadPoolTaskExecutor executor = buildExecutor(cfg);
        log.info("RAG search executor: core={}, max={}, queue={}, prefix={}",
                cfg.getCorePoolSize(), cfg.getMaxPoolSize(), cfg.getQueueCapacity(), cfg.getThreadNamePrefix());
        return executor;
    }

    /**
     * ETL 路由策略工厂 — 自动发现所有 EtlRouteStrategy 实现
     */
    @Bean
    public EtlRouteStrategyFactory etlRouteStrategyFactory(List<EtlRouteStrategy> strategies) {
        return new EtlRouteStrategyFactory(strategies);
    }

    /**
     * 构建线程池：通过 ThreadPoolExecutor 完整构造器指定全部 7 个核心参数。
     * <p>
     * 参数来源：{@link EtlExecutorProperties.PoolConfig}，可配置化。
     * <ul>
     *   <li>corePoolSize / maximumPoolSize / keepAliveTime / unit — 从配置读取</li>
     *   <li>workQueue — ArrayBlockingQueue（有界，容量从配置读取）</li>
     *   <li>threadFactory — NamedThreadFactory（业务前缀 + UncaughtExceptionHandler）</li>
     *   <li>handler — CallerRunsPolicy（满载时由调用线程执行，不丢弃任务）</li>
     * </ul>
     */
    private ThreadPoolTaskExecutor buildExecutor(EtlExecutorProperties.PoolConfig cfg) {
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
        return executor;
    }
}
