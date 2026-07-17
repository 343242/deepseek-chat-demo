package com.smart.rag.agent.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RAG 后处理线程池配置
 * <p>
 * 注册虚拟线程 executor：
 * <ul>
 *   <li>{@code ragPostProcessExecutor} —— RerankThenMmrPostProcessor 的 Rerank⊥distance 并行（LLM/DB 阻塞 IO）</li>
 * </ul>
 * 检索侧（vector+BM25）的并行执行已下沉到 Spring 管理的 {@code ScopedTasks}（见 ScopedTaskAutoConfiguration），
 * 由其内部的 {@code DefaultScopeExecutorFactory} 自建虚拟线程池，因此本类不再注册检索专用 executor。
 * 虚拟线程 per-task 贴合 I/O 密集场景（java-handbook 规则 22）。
 * <p>
 * 不开 {@code spring.threads.virtual.enabled} 全局开关，虚拟线程化范围最小化。
 */
@Configuration
public class RagSearchExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(RagSearchExecutorConfig.class);

    private ExecutorService ragPostProcessExecutorService;

    /**
     * RAG 后处理专用 executor：仅供 {@code RerankThenMmrPostProcessor} 的 Rerank⊥distance 并行。
     * 虚拟线程 per-task 契合 Rerank（LLM IO）/distance（DB IO）阻塞场景。
     */
    @Lazy
    @Bean("ragPostProcessExecutor")
    public ExecutorService ragPostProcessExecutor() {
        ragPostProcessExecutorService = Executors.newVirtualThreadPerTaskExecutor();
        log.info("RAG post-process executor: virtual thread per-task (rerank/mmr parallelism)");
        return ragPostProcessExecutorService;
    }

    @PreDestroy
    public void shutdown() {
        shutdownExecutor(ragPostProcessExecutorService, "RAG post-process executor");
    }

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
