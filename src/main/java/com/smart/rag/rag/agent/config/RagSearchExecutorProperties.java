package com.smart.rag.rag.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索线程池配置属性
 * <p>
 * 用于 HybridSearchService 的 vector + BM25 并行检索。
 * 每个请求提交恰好 2 个并行 I/O 任务，并发度取决于同时进行的 agent 请求数。
 */
@ConfigurationProperties(prefix = "app.agent.search-executor")
public class RagSearchExecutorProperties {

    /** 核心线程数 */
    private int corePoolSize = 2;

    /** 最大线程数 */
    private int maxPoolSize = 4;

    /** 队列容量 */
    private int queueCapacity = 20;

    /** 线程名前缀 */
    private String threadNamePrefix = "rag-search-";

    /** 空闲线程存活时间（秒） */
    private int keepAliveSeconds = 60;

    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public String getThreadNamePrefix() { return threadNamePrefix; }
    public void setThreadNamePrefix(String threadNamePrefix) { this.threadNamePrefix = threadNamePrefix; }

    public int getKeepAliveSeconds() { return keepAliveSeconds; }
    public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }
}
