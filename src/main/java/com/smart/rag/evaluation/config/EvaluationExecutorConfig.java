package com.smart.rag.evaluation.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 评测执行器配置
 * <p>
 * 注册两个独立 bean（评估模块全局恒装载）：
 * <ul>
 *   <li>{@code evalExecutor} —— 评测 run 执行的虚拟线程 executor</li>
 *   <li>{@code evalRunSemaphore} —— 限制同时执行的 run 数（背压）</li>
 * </ul>
 *
 * <h3>虚拟线程（JEP 444 最佳实践）</h3>
 * <ul>
 *   <li>使用 {@link Executors#newVirtualThreadPerTaskExecutor()}：每任务一新虚拟线程，用完即弃，
 *       <b>不池化虚拟线程</b>（JEP 444 明确指出池化是反模式）</li>
 *   <li>executor 对象本身可复用（单例 bean），但其创建的线程从不复用</li>
 *   <li>不依赖 {@code ThreadLocal} 跨任务传递状态——run 上下文通过方法参数显式传递，
 *       避免线程局部变量污染（JEP 444 警告）</li>
 * </ul>
 *
 * <h3>并发限制</h3>
 * 虚拟线程本身无上限（每任务一线程），并发在更高层用 {@link Semaphore} 限制——
 * 符合 JEP 444 "并发限制在更高层做，不靠池大小"原则。防止同时触发多个 run 打爆下游 LLM API。
 *
 * <p>对齐 {@code RagSearchExecutorConfig} 的生命周期模式：{@code @Lazy} + {@code @PreDestroy}
 * 优雅关闭（shutdown + awaitTermination）。
 */
@Configuration
public class EvaluationExecutorConfig implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EvaluationExecutorConfig.class);

    private ExecutorService evalExecutorService;

    /**
     * 评测 run 专用虚拟线程 executor。
     * <p>
     * 评测是 LLM/IO 密集型长任务（单 run 可能跑数十分钟），虚拟线程 per-task 贴合此场景。
     * 资源隔离：独立于 ETL/RAG 的 executor，慢评测不挤占核心入库/检索链路。
     */
    @Lazy
    @Bean("evalExecutor")
    public ExecutorService evalExecutor() {
        evalExecutorService = Executors.newVirtualThreadPerTaskExecutor();
        log.info("Evaluation executor: virtual thread per-task (LLM IO optimized)");
        return evalExecutorService;
    }

    /**
     * 评测并发 run 数的背压信号量。
     * <p>
     * 许可数取自 {@code app.evaluation.runner.max-concurrent-runs}。
     * 虚拟线程 unlimited，靠此信号量限制同时执行的 run 数。
     */
    @Lazy
    @Bean("evalRunSemaphore")
    public Semaphore evalRunSemaphore(EvaluationProperties properties) {
        int permits = properties.getRunner().getMaxConcurrentRuns();
        log.info("Evaluation run semaphore: permits={} (concurrency backpressure)", permits);
        return new Semaphore(permits);
    }

    @Override
    public void destroy() {
        shutdownExecutor(evalExecutorService, "Evaluation executor");
    }

    /**
     * 优雅关闭：先 {@code shutdown()} 拒绝新任务，再等待已提交任务最多 30s 完成，
     * 超时则 {@code shutdownNow()} 强制中断。
     * <p>
     * 注意：评测 run 可能跑很久，30s 通常等不完。应用关闭时未完成的 run 会被强制中断，
     * DB 中这些 run 会停留在 {@code running} 状态——属于已知限制，运维需手动处理或后续加 sweeper。
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        log.info("Shutting down {}...", name);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("{} forced shutdown after 30s timeout", name);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
