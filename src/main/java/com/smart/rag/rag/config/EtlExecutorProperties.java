package com.smart.rag.rag.config;

import com.smart.rag.config.ThreadPoolConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ETL 线程池配置属性
 * <p>
 * IO 密集型池：用于文件读取、MinIO 下载、Embedding API 调用、PGvector 写入<br>
 * CPU 密集型池：用于文本分块、文档解析计算
 * <p>
 * 对应 application.yml 中 app.etl.executor.* 配置项。
 * <p>
 * 默认值基于 {@link ThreadPoolConstants#CPU_COUNT} 动态计算，YAML 可覆盖。
 */
@ConfigurationProperties(prefix = "app.etl.executor")
public class EtlExecutorProperties {

    private PoolConfig io = new PoolConfig();
    private PoolConfig cpu = new PoolConfig(
            ThreadPoolConstants.cpuCore(), ThreadPoolConstants.cpuMax(), 50, "etl-", 60);
    private PoolConfig merge = new PoolConfig(
            ThreadPoolConstants.lightCore(), ThreadPoolConstants.lightMax(), 50, "etl-", 60);

    public PoolConfig getIo() { return io; }
    public void setIo(PoolConfig io) { this.io = io; }

    public PoolConfig getCpu() { return cpu; }
    public void setCpu(PoolConfig cpu) { this.cpu = cpu; }

    public PoolConfig getMerge() { return merge; }
    public void setMerge(PoolConfig merge) { this.merge = merge; }

    /**
     * 单个线程池配置
     */
    public static class PoolConfig {
        private int corePoolSize;
        private int maxPoolSize;
        private int queueCapacity;
        private String threadNamePrefix;
        private int keepAliveSeconds;

        /** IO 密集型默认值（供 io 池使用） */
        public PoolConfig() {
            this(ThreadPoolConstants.ioCore(), ThreadPoolConstants.ioMax(), 50, "etl-", 60);
        }

        public PoolConfig(int corePoolSize, int maxPoolSize, int queueCapacity,
                          String threadNamePrefix, int keepAliveSeconds) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queueCapacity = queueCapacity;
            this.threadNamePrefix = threadNamePrefix;
            this.keepAliveSeconds = keepAliveSeconds;
        }

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
}
