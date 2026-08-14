package com.smart.rag.rag.etl;

import com.smart.rag.config.NamedThreadFactory;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.rag.config.EtlExecutorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
 * 注册三个线程池 Bean：
 * <ol>
 *   <li>{@code etlIoExecutor} — IO 密集型：文件读取、MinIO、Embedding API、PGvector</li>
 *   <li>{@code etlCpuExecutor} — CPU 密集型：文本分块、文档解析</li>
 *   <li>{@code mergeExecutor} — 分片上传异步合并</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(EtlExecutorProperties.class)
public class EtlExecutorConfig implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EtlExecutorConfig.class);

    private final List<ThreadPoolExecutor> managedExecutors = new ArrayList<>();

    /**
     * IO 密集型线程池 — 文件读取、MinIO 下载、Embedding API、PGvector 写入
     */
    @Lazy
    @Bean("etlIoExecutor")
    public ThreadPoolExecutor etlIoExecutor(EtlExecutorProperties properties) {
        EtlExecutorProperties.PoolConfig cfg = properties.getIo();
        ThreadPoolExecutor executor = buildExecutor(cfg);
        log.info("ETL IO executor: core={}, max={}, queue={}, prefix={}",
                cfg.getCorePoolSize(), cfg.getMaxPoolSize(), cfg.getQueueCapacity(), cfg.getThreadNamePrefix());
        return executor;
    }

    /**
     * CPU 密集型线程池 — 文本分块、文档解析计算
     */
    @Lazy
    @Bean("etlCpuExecutor")
    public ThreadPoolExecutor etlCpuExecutor(EtlExecutorProperties properties) {
        EtlExecutorProperties.PoolConfig cfg = properties.getCpu();
        ThreadPoolExecutor executor = buildExecutor(cfg);
        log.info("ETL CPU executor: core={}, max={}, queue={}, prefix={}",
                cfg.getCorePoolSize(), cfg.getMaxPoolSize(), cfg.getQueueCapacity(), cfg.getThreadNamePrefix());
        return executor;
    }

    /**
     * ETL 任务执行器门面
     */
    @Lazy
    @Bean
    public EtlTaskExecutorBridge etlTaskExecutorBridge(
            @Qualifier("etlIoExecutor") ThreadPoolExecutor etlIoExecutor,
            @Qualifier("etlCpuExecutor") ThreadPoolExecutor etlCpuExecutor) {
        return new EtlTaskExecutorBridge(etlIoExecutor, etlCpuExecutor);
    }

    /**
     * ETL 策略共享上下文 — 打包 IO/CPU 线程池 + ScopedTasks + 事件发布器，
     * 供 {@link com.smart.rag.rag.etl.StandardStrategy} / FastTrackStrategy 注入，
     * 避免策略构造器参数膨胀。
     */
    @Lazy
    @Bean
    public EtlStrategyContext etlStrategyContext(
            @Qualifier("etlIoExecutor") ThreadPoolExecutor etlIoExecutor,
            @Qualifier("etlCpuExecutor") ThreadPoolExecutor etlCpuExecutor,
            ScopedTasks scopedTasks,
            ApplicationEventPublisher eventPublisher) {
        return new EtlStrategyContext(etlIoExecutor, etlCpuExecutor, scopedTasks, eventPublisher);
    }

    /**
     * 分片上传合并线程池 — 异步执行 composeObject + 校验和（SHA-256）校验 + DB 写入 + ETL 触发
     */
    @Lazy
    @Bean("mergeExecutor")
    public ThreadPoolExecutor mergeExecutor(EtlExecutorProperties properties) {
        EtlExecutorProperties.PoolConfig cfg = properties.getMerge();
        ThreadPoolExecutor executor = buildExecutor(cfg);
        log.info("Merge executor: core={}, max={}, queue={}, prefix={}",
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
    private ThreadPoolExecutor buildExecutor(EtlExecutorProperties.PoolConfig cfg) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                cfg.getCorePoolSize(),
                cfg.getMaxPoolSize(),
                cfg.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cfg.getQueueCapacity()),
                new NamedThreadFactory(cfg.getThreadNamePrefix()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        managedExecutors.add(executor);
        return executor;
    }

    @Override
    public void destroy() {
        for (ThreadPoolExecutor executor : managedExecutors) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(120, TimeUnit.SECONDS)) {
                    log.warn("ThreadPoolExecutor did not terminate in 120s, forcing shutdown: active={}",
                            executor.getActiveCount());
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
