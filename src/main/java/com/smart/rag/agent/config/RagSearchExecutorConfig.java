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
 * RAG 检索线程池配置
 * <p>
 * 注册两个独立的虚拟线程 executor：
 * <ul>
 *   <li>{@code ragSearchExecutor} —— HybridSearchService 的 vector+BM25 并行检索（I/O 密集）</li>
 *   <li>{@code ragPostProcessExecutor} —— RerankThenMmrPostProcessor 的 Rerank⊥distance 并行（LLM/DB 阻塞 IO）</li>
 * </ul>
 * 两者独立（资源隔离：慢 Rerank 不挤占检索并发）。虚拟线程 per-task 贴合 I/O 密集场景（java-handbook 规则 22）。
 * 并发控制：检索侧由 HybridSearchService.orTimeout(5s) + DB 连接池保障；后处理侧由 rerank HTTP 超时 + 降级契约保障。
 * <p>
 * 不开 {@code spring.threads.virtual.enabled} 全局开关，虚拟线程化范围最小化。
 */
@Configuration
public class RagSearchExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(RagSearchExecutorConfig.class);

    private ExecutorService ragSearchExecutorService;
    private ExecutorService ragPostProcessExecutorService;

    @Lazy
    @Bean("ragSearchExecutor")
    public ExecutorService ragSearchExecutor() {
        ragSearchExecutorService = Executors.newVirtualThreadPerTaskExecutor();
        log.info("RAG search executor: virtual thread per-task (I/O optimized)");
        return ragSearchExecutorService;
    }

    /**
     * RAG 后处理专用 executor：仅供 {@code RerankThenMmrPostProcessor} 的 Rerank⊥distance 并行。
     * 独立于 ragSearchExecutor（资源隔离）；虚拟线程 per-task 契合 Rerank（LLM IO）/distance（DB IO）阻塞场景。
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
        shutdownExecutor(ragSearchExecutorService, "RAG search executor");
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
