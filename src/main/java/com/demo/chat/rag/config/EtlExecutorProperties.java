package com.demo.chat.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ETL 线程池配置属性
 * <p>
 * IO 密集型池：用于文件读取、MinIO 下载、Embedding API 调用、PGvector 写入<br>
 * CPU 密集型池：用于文本分块、文档解析计算
 * <p>
 * 对应 application.yml 中 app.etl.executor.* 配置项。
 */
@ConfigurationProperties(prefix = "app.etl.executor")
public class EtlExecutorProperties {

    private PoolConfig io = new PoolConfig();
    private PoolConfig cpu = new PoolConfig();

    public PoolConfig getIo() { return io; }
    public void setIo(PoolConfig io) { this.io = io; }

    public PoolConfig getCpu() { return cpu; }
    public void setCpu(PoolConfig cpu) { this.cpu = cpu; }

    /**
     * 单个线程池配置
     */
    public static class PoolConfig {
        /** 核心线程数 */
        private int corePoolSize = 4;
        /** 最大线程数 */
        private int maxPoolSize = 8;
        /** 队列容量 */
        private int queueCapacity = 50;
        /** 线程名前缀 */
        private String threadNamePrefix = "etl-";
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
}
