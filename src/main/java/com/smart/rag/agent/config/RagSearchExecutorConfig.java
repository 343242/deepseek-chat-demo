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
 * 注册 {@code ragSearchExecutor} Bean，供 HybridSearchService 做 vector + BM25 并行检索。
 * 使用虚拟线程 executor，贴合 I/O 密集场景（java-handbook 规则 22）。
 * 并发控制由 HybridSearchService.orTimeout(5s) + 底层 DB 连接池共同保障。
 */
@Configuration
public class RagSearchExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(RagSearchExecutorConfig.class);

    private ExecutorService ragSearchExecutorService;

    @Lazy
    @Bean("ragSearchExecutor")
    public ExecutorService ragSearchExecutor() {
        ragSearchExecutorService = Executors.newVirtualThreadPerTaskExecutor();
        log.info("RAG search executor: virtual thread per-task (I/O optimized)");
        return ragSearchExecutorService;
    }

    @PreDestroy
    public void shutdown() {
        if (ragSearchExecutorService != null) {
            log.info("Shutting down RAG search executor...");
            ragSearchExecutorService.shutdown();
            try {
                if (!ragSearchExecutorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    ragSearchExecutorService.shutdownNow();
                    log.warn("RAG search executor forced shutdown after 30s timeout");
                }
            } catch (InterruptedException e) {
                ragSearchExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
