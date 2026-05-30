package com.smart.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MVC 异步线程池配置属性
 * <p>
 * 对应 application.yml 中 app.mvc.async-executor.* 配置项。
 */
@ConfigurationProperties(prefix = "app.mvc.async-executor")
public class MvcExecutorProperties {

    /** 核心线程数 */
    private int corePoolSize = 4;
    /** 最大线程数 */
    private int maxPoolSize = 8;
    /** 队列容量 */
    private int queueCapacity = 100;
    /** 空闲线程存活时间（秒） */
    private int keepAliveSeconds = 60;

    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getKeepAliveSeconds() { return keepAliveSeconds; }
    public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }
}
